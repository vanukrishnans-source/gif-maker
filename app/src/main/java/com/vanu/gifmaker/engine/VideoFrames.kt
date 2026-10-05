package com.vanu.gifmaker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoMeta(
    val name: String,
    val width: Int,          // coded width (before rotation)
    val height: Int,
    val rotation: Int,       // 0/90/180/270
    val durationMs: Long,
    val fps: Float,
    val mime: String?,
) {
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width
}

object VideoProbe {
    fun probe(context: Context, uri: Uri): VideoMeta {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "video"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        } catch (_: Exception) {
        }
        var w = 0; var h = 0; var rot = 0; var durMs = 0L; var frameCount = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (Build.VERSION.SDK_INT >= 28) {
                frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Can't read that video (${e.message ?: "unsupported format"}).")
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        var fps = 0f
        var mime: String? = null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    mime = m
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (_: Exception) {
                            try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0f }
                        }
                    }
                    if (w == 0 && f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                    if (h == 0 && f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (durMs == 0L && f.containsKey(MediaFormat.KEY_DURATION)) durMs = f.getLong(MediaFormat.KEY_DURATION) / 1000
                    if (durMs <= 0L || fps <= 0f) {
                        // Fragmented MP4s (web downloads, some recorders) report no duration/fps:
                        // walk the sample table (no decoding) to measure them.
                        ex.selectTrack(t)
                        var n = 0
                        var first = Long.MAX_VALUE
                        var last = Long.MIN_VALUE
                        while (n < 200_000) {
                            val ts = ex.sampleTime
                            if (ts < 0) break
                            if (ts < first) first = ts
                            if (ts > last) last = ts
                            n++
                            if (!ex.advance()) break
                        }
                        if (n >= 2 && last > first) {
                            val span = last - first
                            val frameUs = span / (n - 1)
                            if (fps <= 0f) fps = (1_000_000.0 / frameUs).toFloat()
                            if (durMs <= 0L) durMs = (last + frameUs) / 1000
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GifMaker", "extractor probe failed", e)
        } finally {
            ex.release()
        }
        android.util.Log.i("GifMaker", "probe $name: ${w}x$h rot=$rot dur=$durMs frames=$frameCount fps=$fps mime=$mime")
        if (mime == null && w == 0) throw IllegalArgumentException("That file has no video track.")
        if (frameCount > 0 && durMs > 0 && fps <= 0f) fps = frameCount * 1000f / durMs
        if (fps <= 0f || fps > 240f) fps = 30f
        if (w <= 0 || h <= 0) throw IllegalArgumentException("Couldn't read the video size.")
        if (durMs <= 0) throw IllegalArgumentException("Couldn't read the video length.")
        return VideoMeta(name, w, h, ((rot % 360) + 360) % 360, durMs, fps, mime)
    }

    /** A display-oriented preview frame (platform applies rotation). */
    fun thumbnail(context: Context, uri: Uri, timeMs: Long, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bmp = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            } else r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            bmp
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

/**
 * Decodes the video between [startMs] and [endMs] and delivers display-oriented ARGB frames of
 * exactly outW x outH, sampled on a fixed grid of 1/fps. Hardware MediaCodec with YUV output
 * (area-averaged downscale + rotation done in one pass); falls back to MediaMetadataRetriever.
 */
class FrameGrabber(
    private val context: Context,
    private val uri: Uri,
    private val meta: VideoMeta,
    private val outW: Int,
    private val outH: Int,
) {
    var usedFallback = false
        private set

    /** onFrame(pixels, slotIndex). Pixels buffer is reused between calls. */
    suspend fun run(startMs: Long, endMs: Long, fps: Float, onFrame: suspend (IntArray, Int) -> Unit) {
        var lastSlot = -1
        var cbError: Throwable? = null
        val ok = try {
            runCodec(startMs, endMs, fps) { px, slot ->
                try { onFrame(px, slot) } catch (t: Throwable) { cbError = t; throw t }
                lastSlot = slot
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cbError?.let { throw it }
            false
        }
        cbError?.let { throw it }
        if (!ok) {
            // Codec unusable (or failed mid-way): continue with the platform retriever.
            usedFallback = true
            runRetriever(startMs, endMs, fps, lastSlot + 1, onFrame)
        }
    }

    // ------------------------------------------------------------------ MediaCodec
    private suspend fun runCodec(startMs: Long, endMs: Long, fps: Float, onFrame: suspend (IntArray, Int) -> Unit): Boolean {
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        val intervalUs = 1_000_000.0 / fps
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lastSlot = -1
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = t; format = f; break }
            }
            if (track < 0 || format == null) return false
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val px = IntArray(outW * outH)
            val conv = YuvConverter()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            var firstPts = Long.MIN_VALUE
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime > endUs + 1_000_000) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    idle = 0
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = info.presentationTimeUs
                    if (firstPts == Long.MIN_VALUE && info.size > 0) firstPts = pts
                    if (info.size > 0 && pts >= endUs) {
                        codec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    var handled = false
                    if (info.size > 0 && pts >= startUs) {
                        val slot = ((pts - startUs) / intervalUs).toInt()
                        if (slot > lastSlot) {
                            val image = codec.getOutputImage(outIdx)
                            if (image != null) {
                                try {
                                    if (image.format == ImageFormat.YUV_420_888) {
                                        conv.convert(image, codec.outputFormat, meta.rotation, px, outW, outH)
                                        handled = true
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (!handled) return false
                            lastSlot = slot
                            onFrame(px, slot)
                        } else codec.releaseOutputBuffer(outIdx, false)
                    } else codec.releaseOutputBuffer(outIdx, false)
                    if (eos) break
                } else if (inputDone) {
                    if (++idle > 600) break
                }
            }
            return lastSlot >= 0
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    // ------------------------------------------------------------------ Fallback
    private suspend fun runRetriever(startMs: Long, endMs: Long, fps: Float, fromSlot: Int, onFrame: suspend (IntArray, Int) -> Unit) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val px = IntArray(outW * outH)
            val n = max(1, ceil((endMs - startMs) * fps / 1000.0).toInt())
            for (slot in fromSlot until n) {
                currentCoroutineContext().ensureActive()
                val tUs = (startMs * 1000 + slot * 1_000_000.0 / fps).toLong()
                val bmp: Bitmap = try {
                    if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH)
                    else r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) { null } ?: continue
                val sized = if (bmp.width == outW && bmp.height == outH) bmp else Bitmap.createScaledBitmap(bmp, outW, outH, true)
                val argb = if (sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, false)
                argb.getPixels(px, 0, outW, 0, 0, outW, outH)
                if (argb !== bmp) argb.recycle()
                if (sized !== bmp && sized !== argb) sized.recycle()
                bmp.recycle()
                onFrame(px, slot)
            }
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

/** YUV_420_888 -> ARGB with rotation and area-averaged downscale in one pass. */
class YuvConverter {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    private fun copy(plane: Image.Plane, cur: ByteArray): ByteArray {
        val bb = plane.buffer
        bb.rewind()
        val n = bb.remaining()
        val arr = if (cur.size >= n) cur else ByteArray(n)
        bb.get(arr, 0, n)
        return arr
    }

    fun convert(image: Image, fmt: MediaFormat?, rotation: Int, out: IntArray, outW: Int, outH: Int) {
        val crop = image.cropRect
        val left = max(0, crop.left)
        val top = max(0, crop.top)
        val sw = min(crop.width(), image.width - left).coerceAtLeast(1)
        val sh = min(crop.height(), image.height - top).coerceAtLeast(1)
        val planes = image.planes
        y = copy(planes[0], y); u = copy(planes[1], u); v = copy(planes[2], v)
        val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        val ySize = planes[0].buffer.limit(); val uSize = planes[1].buffer.limit(); val vSize = planes[2].buffer.limit()

        // Colour matrix: prefer the decoder's declared standard/range, else guess by size.
        var standard = 0; var range = 0
        if (fmt != null) {
            try { if (fmt.containsKey(MediaFormat.KEY_COLOR_STANDARD)) standard = fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) } catch (_: Exception) {}
            try { if (fmt.containsKey(MediaFormat.KEY_COLOR_RANGE)) range = fmt.getInteger(MediaFormat.KEY_COLOR_RANGE) } catch (_: Exception) {}
        }
        val bt709 = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> true
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> false
            else -> sh >= 720 || sw >= 1280
        }
        val full = range == MediaFormat.COLOR_RANGE_FULL
        val ky = if (full) 1f else 1.164f
        val yOff = if (full) 0 else 16
        val crv: Float; val cgu: Float; val cgv: Float; val cbu: Float
        if (bt709) {
            if (full) { crv = 1.575f; cgu = 0.187f; cgv = 0.468f; cbu = 1.856f } else { crv = 1.793f; cgu = 0.213f; cgv = 0.533f; cbu = 2.112f }
        } else {
            if (full) { crv = 1.402f; cgu = 0.344f; cgv = 0.714f; cbu = 1.772f } else { crv = 1.596f; cgu = 0.392f; cgv = 0.813f; cbu = 2.017f }
        }

        val dispW = if (rotation % 180 == 0) sw else sh
        val dispH = if (rotation % 180 == 0) sh else sw
        val k = ceil(max(dispW.toFloat() / outW, dispH.toFloat() / outH)).toInt().coerceIn(1, 4)
        // Display-space sample coordinates per output column / row.
        val dxs = IntArray(outW * k) { idx -> (((idx / k) + ((idx % k) + 0.5f) / k) * dispW / outW).toInt().coerceIn(0, dispW - 1) }
        val dys = IntArray(outH * k) { idx -> (((idx / k) + ((idx % k) + 0.5f) / k) * dispH / outH).toInt().coerceIn(0, dispH - 1) }
        val kk = k * k
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                var sy0 = 0; var su = 0; var sv = 0
                for (j in 0 until k) {
                    val dy = dys[oy * k + j]
                    for (i in 0 until k) {
                        val dx = dxs[ox * k + i]
                        val sx: Int; val syy: Int
                        when (rotation) {
                            90 -> { sx = dy; syy = sh - 1 - dx }
                            180 -> { sx = sw - 1 - dx; syy = sh - 1 - dy }
                            270 -> { sx = sw - 1 - dy; syy = dx }
                            else -> { sx = dx; syy = dy }
                        }
                        val ax = left + sx; val ay = top + syy
                        val yi = ay * yRow + ax * yPix
                        val ui = (ay shr 1) * uRow + (ax shr 1) * uPix
                        val vi = (ay shr 1) * vRow + (ax shr 1) * vPix
                        sy0 += if (yi < ySize) y[yi].toInt() and 0xFF else 16
                        su += if (ui < uSize) u[ui].toInt() and 0xFF else 128
                        sv += if (vi < vSize) v[vi].toInt() and 0xFF else 128
                    }
                }
                val yy = ky * (sy0.toFloat() / kk - yOff)
                val uu = su.toFloat() / kk - 128f
                val vv = sv.toFloat() / kk - 128f
                val r = (yy + crv * vv).roundToInt().coerceIn(0, 255)
                val g = (yy - cgu * uu - cgv * vv).roundToInt().coerceIn(0, 255)
                val b = (yy + cbu * uu).roundToInt().coerceIn(0, 255)
                out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
