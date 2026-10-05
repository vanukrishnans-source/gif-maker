package com.vanu.gifmaker.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class Quality(val label: String, val maxColors: Int, val diffThreshold: Int) {
    BEST("Best", 256, 3),
    BALANCED("Balanced", 256, 8),
    SMALL("Smaller file", 128, 16),
}

data class GifSettings(
    val startMs: Long,
    val endMs: Long,
    val fps: Int = 10,
    val maxSide: Int = 480,
    val speed: Float = 1f,
    val quality: Quality = Quality.BALANCED,
    val loop: Boolean = true,
    val dither: Boolean = true,
) {
    val lengthMs get() = endMs - startMs
    val frameCount get() = max(1, ceil(lengthMs * fps / 1000.0).toInt())
    /** Playback length of the GIF (after speed). */
    val gifLengthMs get() = (frameCount * 1000.0 / fps / speed).roundToInt()
}

data class GifResult(
    val file: File,
    val width: Int,
    val height: Int,
    val frames: Int,
    val durationMs: Int,
    val bytes: Long,
    val usedFallback: Boolean,
    val elapsedMs: Long,
)

object GifMaker {
    const val MAX_LENGTH_MS = 30_000L
    const val MIN_LENGTH_MS = 200L
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun outputSize(meta: VideoMeta, maxSide: Int): Pair<Int, Int> {
        val dw = meta.displayWidth; val dh = meta.displayHeight
        val long = max(dw, dh)
        val scale = if (long > maxSide) maxSide.toFloat() / long else 1f
        return max(2, (dw * scale).roundToInt()) to max(2, (dh * scale).roundToInt())
    }

    fun gifDir(context: Context) = File(context.cacheDir, "gifs").apply { mkdirs() }

    fun outputName(meta: VideoMeta): String {
        val base = meta.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]+"), "_").take(32).ifBlank { "video" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "GIF_${base}_$ts.gif"
    }

    suspend fun make(
        context: Context,
        uri: Uri,
        meta: VideoMeta,
        s: GifSettings,
        onProgress: (done: Int, total: Int) -> Unit,
    ): GifResult {
        val t0 = System.currentTimeMillis()
        require(s.lengthMs >= MIN_LENGTH_MS) { "Pick at least ${MIN_LENGTH_MS / 1000.0} s." }
        val (w, h) = outputSize(meta, s.maxSide)
        val dir = gifDir(context)
        // Only one GIF is kept in the cache (the current result); older ones were already replaced.
        dir.listFiles()?.forEach { if (it.name.endsWith(".part") || it.name.endsWith(".gif")) it.delete() }
        val out = File(dir, outputName(meta))
        val tmp = File(dir, out.name + ".part")
        val total = s.frameCount
        val stepMs = 1000.0 / s.fps / s.speed
        val opts = GifOptions(
            maxColors = s.quality.maxColors,
            dither = s.dither,
            diffThreshold = s.quality.diffThreshold,
            loopCount = if (s.loop) 0 else -1,
        )
        var grabber: FrameGrabber? = null
        try {
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { os ->
                val gw = GifWriter(os, w, h, opts)
                grabber = FrameGrabber(context, uri, meta, w, h)
                var done = 0
                grabber!!.run(s.startMs, s.endMs, s.fps.toFloat()) { px, slot ->
                    if (slot < total) {
                        gw.addFrame(px, (slot * stepMs).roundToLong())
                        done++
                        onProgress(minOf(slot + 1, total), total)
                        if (gw.bytesWritten > MAX_BYTES)
                            throw GifException("The GIF is over ${MAX_BYTES / (1024 * 1024)} MB — choose a smaller size, lower FPS or a shorter clip.")
                    }
                }
                if (done == 0) throw GifException("Couldn't decode any frames in that range.")
                gw.finish((total * stepMs).roundToLong())
            }
            val v = GifValidator.validate(tmp)
            // Platform decode check (same decoder family Gallery thumbnailers use).
            val bo = BitmapFactory.Options().apply { inSampleSize = 1 }
            val first = BitmapFactory.decodeFile(tmp.absolutePath, bo)
                ?: throw GifException("Android couldn't decode the encoded GIF.")
            first.recycle()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return GifResult(out, v.width, v.height, v.frames, v.durationMs, out.length(), grabber?.usedFallback == true,
                System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            tmp.delete(); out.delete()
            throw e
        }
    }

    private fun Double.roundToLong() = Math.round(this)
}

object GifSaver {
    const val ALBUM = "GifMaker"

    /** Copies the GIF into Pictures/GifMaker via MediaStore. Returns the content Uri. */
    fun save(context: Context, gif: File): Uri {
        GifValidator.validate(gif)
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val collection: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.DISPLAY_NAME, gif.name)
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            var f = File(dir, gif.name)
            var k = 1
            while (f.exists()) { f = File(dir, gif.nameWithoutExtension + "_$k.gif"); k++ }
            values.put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.DATA, f.absolutePath)
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = cr.insert(collection, values) ?: throw GifException("Couldn't create the file in Pictures.")
        try {
            cr.openOutputStream(uri, "w")?.use { os -> gif.inputStream().use { it.copyTo(os, 1 shl 16) } }
                ?: throw GifException("Couldn't open Pictures for writing.")
            // Read back: size + magic, so Gallery never gets a truncated/broken entry.
            val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (size >= 0 && size != gif.length()) throw GifException("Saved file is incomplete ($size of ${gif.length()} bytes).")
            val head = ByteArray(6)
            cr.openInputStream(uri)?.use { it.read(head) }
            if (String(head, Charsets.US_ASCII) != "GIF89a") throw GifException("Saved file failed the GIF check.")
            if (Build.VERSION.SDK_INT >= 29) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}
