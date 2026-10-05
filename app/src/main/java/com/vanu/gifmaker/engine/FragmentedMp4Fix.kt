package com.vanu.gifmaker.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Android's MPEG4Extractor stops at the first movie fragment ('moof') that carries no samples
 * for the selected track. Fragmented MP4s that put a timecode/data track in its own fragment
 * (common in web downloads and some editors) therefore look truncated to MediaExtractor,
 * MediaMetadataRetriever and MediaPlayer alike (e.g. a 16.4 s clip reads as 10.4 s).
 *
 * If — and only if — a file has such fragments, this writes a cleaned copy to the cache with
 * those video-less fragments (and their mdat) removed. Fragments are copied byte-for-byte as
 * contiguous moof+mdat units, so relative data offsets stay valid. Files whose fragments use
 * absolute base-data-offsets are left alone.
 */
object FragmentedMp4Fix {
    private const val MAX_BYTES = 1_500L * 1024 * 1024

    private class Box(val type: String, val pos: Long, val size: Long, val header: Int) {
        val contentPos get() = pos + header
        val end get() = pos + size
    }

    fun cacheDir(context: Context) = File(context.cacheDir, "fixed").apply { mkdirs() }

    fun fixIfNeeded(context: Context, uri: Uri): File? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val dir = cacheDir(context)
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "fixed_${System.currentTimeMillis()}.mp4")
                    if (fixChannel(ch, out)) out else { out.delete(); null }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Pure (JVM-testable) core: returns true if [out] was written with the cleaned file. */
    fun fixChannel(ch: FileChannel, out: File): Boolean = fixInner(ch, out) != null

    private fun fixInner(ch: FileChannel, out: File): File? {
        val len = ch.size()
        if (len < 16 || len > MAX_BYTES) return null
        val top = children(ch, 0, len) ?: return null
        if (top.none { it.type == "moof" }) return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val videoId = videoTrackId(ch, moov) ?: return null

        var needsFix = false
        for (b in top) {
            if (b.type != "moof") continue
            val info = moofInfo(ch, b) ?: return null
            if (videoId !in info.first) needsFix = true
            else if (info.second) return null // absolute offsets: can't move fragments safely
        }
        if (!needsFix) return null

        FileOutputStream(out).channel.use { oc ->
            var dropping = false
            for (b in top) {
                when (b.type) {
                    "moof" -> { dropping = videoId !in (moofInfo(ch, b)?.first ?: emptySet()); if (dropping) continue }
                    "mdat" -> if (dropping) continue
                    "sidx", "mfra" -> continue // indexes would point at removed fragments
                    "styp", "prft", "emsg" -> if (dropping) continue
                    else -> dropping = false
                }
                var p = b.pos
                while (p < b.end) p += ch.transferTo(p, b.end - p, oc).also { if (it <= 0) throw IllegalStateException("copy") }
            }
        }
        return out
    }

    private fun readBuf(ch: FileChannel, pos: Long, n: Int): ByteBuffer? {
        val bb = ByteBuffer.allocate(n)
        var p = pos
        while (bb.hasRemaining()) {
            val r = ch.read(bb, p)
            if (r <= 0) return null
            p += r
        }
        bb.flip()
        return bb
    }

    private fun children(ch: FileChannel, start: Long, end: Long): List<Box>? {
        val list = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            val h = readBuf(ch, p, 8) ?: return null
            var size = h.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                val l = readBuf(ch, p + 8, 8) ?: return null
                size = l.long; header = 16
            } else if (size == 0L) size = end - p
            if (size < header || p + size > end) return null
            list.add(Box(type, p, size, header))
            p += size
        }
        return list
    }

    private fun videoTrackId(ch: FileChannel, moov: Box): Int? {
        for (trak in children(ch, moov.contentPos, moov.end) ?: return null) {
            if (trak.type != "trak") continue
            val kids = children(ch, trak.contentPos, trak.end) ?: continue
            val tkhd = kids.firstOrNull { it.type == "tkhd" } ?: continue
            val mdia = kids.firstOrNull { it.type == "mdia" } ?: continue
            val hdlr = children(ch, mdia.contentPos, mdia.end)?.firstOrNull { it.type == "hdlr" } ?: continue
            val hb = readBuf(ch, hdlr.contentPos + 8, 4) ?: continue
            if (String(ByteArray(4).also { hb.get(it) }, Charsets.ISO_8859_1) != "vide") continue
            val vb = readBuf(ch, tkhd.contentPos, 1) ?: continue
            val off = if (vb.get().toInt() == 1) 20 else 12
            return readBuf(ch, tkhd.contentPos + off, 4)?.int
        }
        return null
    }

    /** (track ids present in this moof, any tfhd with explicit base-data-offset) */
    private fun moofInfo(ch: FileChannel, moof: Box): Pair<Set<Int>, Boolean>? {
        val ids = HashSet<Int>()
        var absolute = false
        for (traf in children(ch, moof.contentPos, moof.end) ?: return null) {
            if (traf.type != "traf") continue
            val tfhd = children(ch, traf.contentPos, traf.end)?.firstOrNull { it.type == "tfhd" } ?: continue
            val b = readBuf(ch, tfhd.contentPos, 8) ?: return null
            val flags = b.int and 0xFFFFFF
            ids.add(b.int)
            if (flags and 0x1 != 0) absolute = true
        }
        return ids to absolute
    }
}
