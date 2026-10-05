package com.vanu.gifmaker.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure-Kotlin streaming GIF89a encoder (no Android imports, so it is unit-tested on the JVM).
 *
 *  - Frame 0 is a full frame whose palette is also published as the Global Color Table
 *    (some Android gallery thumbnailers show a broken icon for LCT-only GIFs).
 *  - Later frames carry a Local Color Table and (optionally) only the changed rectangle, with
 *    unchanged pixels written as a transparent index over "do not dispose" — the standard
 *    gifsicle/ffmpeg optimisation. It keeps static backgrounds rock-steady and files small.
 *  - Palettes: variance-based median cut on a 15-bit histogram with exact colour means.
 *  - Optional Floyd–Steinberg dithering for smooth gradients (skies, sunsets).
 *  - LZW: the Clear code is emitted at the *current* code width before the width resets
 *    (the GIF Face Swap 1.0.1 fix). Emitting Clear after resetting the width corrupts any frame
 *    whose dictionary fills up, which Gallery shows as a broken image.
 */

class GifException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class GifOptions(
    /** Max palette entries per frame (2..256). */
    val maxColors: Int = 256,
    val dither: Boolean = true,
    /**
     * Per-channel difference (0..255) under which a pixel counts as unchanged and is written as
     * transparent. Negative disables the optimisation (every frame is a full opaque frame).
     */
    val diffThreshold: Int = 6,
    /** 0 = loop forever, -1 = play once (no NETSCAPE2.0 block), n = repeat n extra times. */
    val loopCount: Int = 0,
)

class GifWriter(
    output: OutputStream,
    val width: Int,
    val height: Int,
    private val opts: GifOptions = GifOptions(),
) {
    init {
        require(width in 1..65535 && height in 1..65535) { "bad size ${width}x$height" }
    }

    private class Counting(val os: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { os.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { os.write(b, off, len); count += len }
        override fun flush() = os.flush()
    }

    private class Pending(val block: ByteArray, val transparentIndex: Int, val timeCs: Int)

    private val os = Counting(output)
    private val ref = IntArray(width * height)
    private var pending: Pending? = null
    private var started = false
    private var finished = false

    /** Frames actually written (unchanged/too-close frames are merged into the previous one). */
    var framesWritten = 0
        private set
    var framesMerged = 0
        private set
    val bytesWritten: Long get() = os.count

    /**
     * Add a frame. [argb] is width*height pixels (alpha ignored). [timeMs] is when this frame
     * should appear, relative to the start of the GIF; it must be non-decreasing.
     */
    fun addFrame(argb: IntArray, timeMs: Long) {
        check(!finished) { "already finished" }
        require(argb.size >= width * height) { "pixel buffer too small" }
        val tcs = (timeMs / 10.0).roundToInt()
        val p = pending
        if (p != null && tcs - p.timeCs < 2) { framesMerged++; return } // GIF delays < 2cs get clamped by viewers
        if (!started) {
            writeFirst(argb, tcs)
            return
        }
        val enc = encodeDelta(argb) ?: run { framesMerged++; return }
        flushPending(tcs)
        pending = Pending(enc.first, enc.second, tcs)
    }

    /** Write the last frame (shown until [endMs]) and the trailer. */
    fun finish(endMs: Long) {
        check(!finished) { "already finished" }
        if (!started) throw GifException("No frames were captured.")
        val p = pending!!
        val endCs = max(p.timeCs + 2, (endMs / 10.0).roundToInt())
        flushPending(endCs)
        os.write(0x3B)
        os.flush()
        finished = true
    }

    private fun flushPending(nextCs: Int) {
        val p = pending ?: return
        val delay = (nextCs - p.timeCs).coerceIn(2, 65535)
        os.write(0x21); os.write(0xF9); os.write(4)
        // disposal 1 (do not dispose) | transparency flag
        os.write((1 shl 2) or (if (p.transparentIndex >= 0) 1 else 0))
        writeShort(os, delay)
        os.write(if (p.transparentIndex >= 0) p.transparentIndex else 0)
        os.write(0)
        os.write(p.block)
        framesWritten++
        pending = null
    }

    private fun writeFirst(argb: IntArray, tcs: Int) {
        val n = width * height
        val all = IntArray(n) { it }
        val palette = Palette.build(argb, all, n, opts.maxColors.coerceIn(2, 256))
        val indices = Palette.map(argb, width, 0, 0, width, height, null, palette, -1, opts.dither)
        for (i in 0 until n) ref[i] = argb[i] and 0xFFFFFF

        val bits = paletteBits(palette.size)
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(os, width); writeShort(os, height)
        os.write(0x80 or (7 shl 4) or bits) // GCT present, colour resolution 8 bits, GCT size
        os.write(0); os.write(0) // background index, pixel aspect
        writeColorTable(os, palette, 1 shl (bits + 1))
        if (opts.loopCount >= 0) {
            os.write(0x21); os.write(0xFF); os.write(11)
            os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            os.write(3); os.write(1); writeShort(os, opts.loopCount.coerceIn(0, 65535)); os.write(0)
        }
        val block = ByteArrayOutputStream(n / 2)
        block.write(0x2C)
        writeShort(block, 0); writeShort(block, 0); writeShort(block, width); writeShort(block, height)
        block.write(0x00) // no LCT: uses the GCT
        val minCode = max(2, bits + 1)
        block.write(minCode)
        Lzw.encode(indices, minCode, block)
        pending = Pending(block.toByteArray(), -1, tcs)
        started = true
    }

    /** Returns (block bytes, transparent index or -1), or null if nothing changed. */
    private fun encodeDelta(argb: IntArray): Pair<ByteArray, Int>? {
        val thr = opts.diffThreshold
        var x0 = 0; var y0 = 0; var x1 = width - 1; var y1 = height - 1
        var mask: BooleanArray? = null
        if (thr >= 0) {
            val m = BooleanArray(width * height)
            x0 = width; y0 = height; x1 = -1; y1 = -1
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val a = argb[i]; val b = ref[i]
                    val d = max(
                        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                        max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
                    )
                    if (d > thr) {
                        m[i] = true
                        if (x < x0) x0 = x; if (x > x1) x1 = x
                        if (y < y0) y0 = y; if (y > y1) y1 = y
                    }
                }
            }
            if (x1 < 0) return null
            mask = m
        }
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        // Collect the pixels that will actually be drawn.
        val sel = IntArray(bw * bh)
        var ns = 0
        var allChanged = true
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                val i = row + x
                if (mask == null || mask[i]) sel[ns++] = i else allChanged = false
            }
        }
        val useTransparency = !allChanged
        val maxC = opts.maxColors.coerceIn(2, 256) - (if (useTransparency) 1 else 0)
        val palette = Palette.build(argb, sel, ns, max(1, maxC))
        val tIndex = if (useTransparency) palette.size else -1
        val indices = Palette.map(argb, width, x0, y0, bw, bh, if (useTransparency) mask else null, palette, tIndex, opts.dither)
        for (k in 0 until ns) { val i = sel[k]; ref[i] = argb[i] and 0xFFFFFF }

        val tableCount = palette.size + (if (useTransparency) 1 else 0)
        val bits = paletteBits(tableCount)
        val block = ByteArrayOutputStream(bw * bh / 2 + 1024)
        block.write(0x2C)
        writeShort(block, x0); writeShort(block, y0); writeShort(block, bw); writeShort(block, bh)
        block.write(0x80 or bits) // LCT present
        writeColorTable(block, palette, 1 shl (bits + 1)) // transparent slot (and padding) = black
        val minCode = max(2, bits + 1)
        block.write(minCode)
        Lzw.encode(indices, minCode, block)
        return block.toByteArray() to tIndex
    }

    companion object {
        /** Smallest b (0..7) with 2^(b+1) >= n. */
        fun paletteBits(n: Int): Int {
            var b = 0
            while ((1 shl (b + 1)) < n && b < 7) b++
            return b
        }

        fun writeShort(os: OutputStream, v: Int) {
            os.write(v and 0xFF); os.write((v shr 8) and 0xFF)
        }

        fun writeColorTable(os: OutputStream, palette: IntArray, count: Int) {
            for (i in 0 until count) {
                val c = if (i < palette.size) palette[i] else 0
                os.write((c shr 16) and 0xFF); os.write((c shr 8) and 0xFF); os.write(c and 0xFF)
            }
        }
    }
}

object Palette {
    /**
     * Variance-based median cut. [sel] holds the indices (into [px]) of the [n] pixels to
     * consider. Returns 1..[maxColors] colours packed 0xRRGGBB.
     */
    fun build(px: IntArray, sel: IntArray, n: Int, maxColors: Int): IntArray {
        val cnt = IntArray(32768)
        val sr = LongArray(32768); val sg = LongArray(32768); val sb = LongArray(32768)
        for (k in 0 until n) {
            val c = px[sel[k]]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            cnt[key]++; sr[key] += r.toLong(); sg[key] += g.toLong(); sb[key] += b.toLong()
        }
        var nb = 0
        for (i in 0 until 32768) if (cnt[i] > 0) nb++
        if (nb == 0) return intArrayOf(0)
        val bins = IntArray(nb)
        nb = 0
        for (i in 0 until 32768) if (cnt[i] > 0) bins[nb++] = i
        // bin means
        val mr = FloatArray(32768); val mg = FloatArray(32768); val mb = FloatArray(32768)
        for (b in bins) { val c = cnt[b].toFloat(); mr[b] = sr[b] / c; mg[b] = sg[b] / c; mb[b] = sb[b] / c }

        class Box(val lo: Int, val hi: Int) { // [lo, hi) into bins
            var sse = 0.0
            var axis = 0
            init {
                var c = 0.0; var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var q0 = 0.0; var q1 = 0.0; var q2 = 0.0
                for (k in lo until hi) {
                    val b = bins[k]; val w = cnt[b].toDouble()
                    c += w
                    s0 += w * mr[b]; s1 += w * mg[b]; s2 += w * mb[b]
                    q0 += w * mr[b] * mr[b]; q1 += w * mg[b] * mg[b]; q2 += w * mb[b] * mb[b]
                }
                val v0 = q0 - s0 * s0 / c; val v1 = q1 - s1 * s1 / c; val v2 = q2 - s2 * s2 / c
                sse = v0 + v1 + v2
                axis = if (v0 >= v1 && v0 >= v2) 0 else if (v1 >= v2) 1 else 2
            }
        }

        val boxes = ArrayList<Box>()
        boxes.add(Box(0, nb))
        val target = min(maxColors, nb)
        while (boxes.size < target) {
            var bi = -1; var best = 0.0
            for (i in boxes.indices) {
                val bx = boxes[i]
                if (bx.hi - bx.lo >= 2 && bx.sse > best) { best = bx.sse; bi = i }
            }
            if (bi < 0) break
            val bx = boxes.removeAt(bi)
            val arr = bins.copyOfRange(bx.lo, bx.hi).toTypedArray()
            when (bx.axis) {
                0 -> arr.sortBy { mr[it] }
                1 -> arr.sortBy { mg[it] }
                else -> arr.sortBy { mb[it] }
            }
            for (k in arr.indices) bins[bx.lo + k] = arr[k]
            var total = 0L
            for (k in bx.lo until bx.hi) total += cnt[bins[k]]
            var acc = 0L
            var cut = bx.lo + 1
            for (k in bx.lo until bx.hi - 1) {
                acc += cnt[bins[k]]
                cut = k + 1
                if (acc * 2 >= total) break
            }
            boxes.add(Box(bx.lo, cut))
            boxes.add(Box(cut, bx.hi))
        }
        val out = IntArray(boxes.size)
        for ((i, bx) in boxes.withIndex()) {
            var c = 0L; var r = 0L; var g = 0L; var b = 0L
            for (k in bx.lo until bx.hi) { val e = bins[k]; c += cnt[e]; r += sr[e]; g += sg[e]; b += sb[e] }
            val rr = ((r + c / 2) / c).toInt().coerceIn(0, 255)
            val gg = ((g + c / 2) / c).toInt().coerceIn(0, 255)
            val bb = ((b + c / 2) / c).toInt().coerceIn(0, 255)
            out[i] = (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }

    private fun dist(r: Int, g: Int, b: Int, c: Int): Int {
        val dr = r - ((c shr 16) and 0xFF); val dg = g - ((c shr 8) and 0xFF); val db = b - (c and 0xFF)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    private class Lut(val palette: IntArray) {
        val cells = IntArray(1 shl 18).also { it.fill(-1) }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
            val hit = cells[key]
            if (hit >= 0) return hit
            val cr = (r and 0xFC) or 2; val cg = (g and 0xFC) or 2; val cb = (b and 0xFC) or 2
            var best = 0; var bd = Int.MAX_VALUE
            for (i in palette.indices) {
                val d = dist(cr, cg, cb, palette[i])
                if (d < bd) { bd = d; best = i }
            }
            cells[key] = best
            return best
        }
    }

    /**
     * Map the rectangle (x0,y0,bw,bh) of [px] (row stride [stride]) to palette indices.
     * Pixels with mask[i] == false get [tIndex]. Error diffusion only flows into drawn pixels.
     */
    fun map(
        px: IntArray, stride: Int, x0: Int, y0: Int, bw: Int, bh: Int,
        mask: BooleanArray?, palette: IntArray, tIndex: Int, dither: Boolean,
    ): ByteArray {
        val out = ByteArray(bw * bh)
        val lut = Lut(palette)
        if (!dither || palette.size < 2) {
            for (y in 0 until bh) {
                val row = (y0 + y) * stride + x0
                for (x in 0 until bw) {
                    val i = row + x
                    out[y * bw + x] = if (mask != null && !mask[i]) tIndex.toByte() else {
                        val c = px[i]
                        lut.nearest((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toByte()
                    }
                }
            }
            return out
        }
        // Floyd–Steinberg, errors in 1/16 units, slightly damped (15/16) to limit frame-to-frame shimmer.
        var cur = IntArray((bw + 2) * 3)
        var nxt = IntArray((bw + 2) * 3)
        for (y in 0 until bh) {
            val row = (y0 + y) * stride + x0
            java.util.Arrays.fill(nxt, 0)
            for (x in 0 until bw) {
                val i = row + x
                val o = y * bw + x
                if (mask != null && !mask[i]) { out[o] = tIndex.toByte(); continue }
                val c = px[i]
                val e = (x + 1) * 3
                val r = (((c shr 16) and 0xFF) + cur[e] / 16).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + cur[e + 1] / 16).coerceIn(0, 255)
                val b = ((c and 0xFF) + cur[e + 2] / 16).coerceIn(0, 255)
                val idx = lut.nearest(r, g, b)
                out[o] = idx.toByte()
                val pc = palette[idx]
                val er = (r - ((pc shr 16) and 0xFF)) * 15 / 16
                val eg = (g - ((pc shr 8) and 0xFF)) * 15 / 16
                val eb = (b - (pc and 0xFF)) * 15 / 16
                val okR = x + 1 < bw && (mask == null || mask[i + 1])
                val okDL = y + 1 < bh && x > 0 && (mask == null || mask[i + stride - 1])
                val okD = y + 1 < bh && (mask == null || mask[i + stride])
                val okDR = y + 1 < bh && x + 1 < bw && (mask == null || mask[i + stride + 1])
                if (okR) { cur[e + 3] += er * 7; cur[e + 4] += eg * 7; cur[e + 5] += eb * 7 }
                if (okDL) { nxt[e - 3] += er * 3; nxt[e - 2] += eg * 3; nxt[e - 1] += eb * 3 }
                if (okD) { nxt[e] += er * 5; nxt[e + 1] += eg * 5; nxt[e + 2] += eb * 5 }
                if (okDR) { nxt[e + 3] += er; nxt[e + 4] += eg; nxt[e + 5] += eb }
            }
            val t = cur; cur = nxt; nxt = t
        }
        return out
    }
}

object Lzw {
    /** GIF LZW. Writes data sub-blocks and the 0 terminator (caller writes minCodeSize first). */
    fun encode(index: ByteArray, minCodeSize: Int, os: OutputStream) {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val initWidth = minCodeSize + 1
        // Open-addressing hash: key = (prefix << 8 | k) + 1
        val hKeys = IntArray(8192)
        val hVals = IntArray(8192)

        var nextCode = eoi + 1
        var width = initWidth
        val buf = ByteArray(255)
        var bufLen = 0
        var acc = 0L
        var accBits = 0

        fun putByte(b: Int) {
            buf[bufLen++] = b.toByte()
            if (bufLen == 255) { os.write(255); os.write(buf, 0, 255); bufLen = 0 }
        }
        fun emit(code: Int) {
            acc = acc or (code.toLong() shl accBits)
            accBits += width
            while (accBits >= 8) { putByte((acc and 0xFF).toInt()); acc = acc ushr 8; accBits -= 8 }
        }
        fun clearTable() {
            emit(clear) // at the CURRENT width — then reset
            java.util.Arrays.fill(hKeys, 0)
            nextCode = eoi + 1
            width = initWidth
        }

        clearTable()
        if (index.isEmpty()) {
            emit(eoi)
        } else {
            var prefix = index[0].toInt() and 0xFF
            for (p in 1 until index.size) {
                val k = index[p].toInt() and 0xFF
                val key = ((prefix shl 8) or k) + 1
                var h = ((key * -0x61c88647) ushr 19) and 8191
                var found = -1
                while (true) {
                    val hk = hKeys[h]
                    if (hk == 0) break
                    if (hk == key) { found = hVals[h]; break }
                    h = (h + 1) and 8191
                }
                if (found >= 0) {
                    prefix = found
                } else {
                    emit(prefix)
                    if (nextCode < 4096) {
                        hKeys[h] = key; hVals[h] = nextCode
                        if (nextCode == (1 shl width) && width < 12) width++
                        nextCode++
                    } else {
                        clearTable()
                    }
                    prefix = k
                }
            }
            emit(prefix)
            emit(eoi)
        }
        if (accBits > 0) putByte((acc and 0xFF).toInt())
        if (bufLen > 0) { os.write(bufLen); os.write(buf, 0, bufLen) }
        os.write(0)
    }
}

/** Structural GIF validator (header, blocks, LZW sub-block chains, trailer). */
object GifValidator {
    data class Result(val width: Int, val height: Int, val frames: Int, val durationMs: Int, val loops: Boolean)

    fun validate(file: File): Result {
        if (!file.isFile) throw GifException("GIF file missing.")
        if (file.length() < 32) throw GifException("GIF too small (${file.length()} bytes).")
        return validate(file.readBytes())
    }

    fun validate(d: ByteArray): Result {
        fun u8(i: Int): Int { if (i >= d.size) throw GifException("Truncated GIF at $i."); return d[i].toInt() and 0xFF }
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        if (d.size < 13 || String(d, 0, 6, Charsets.US_ASCII) != "GIF89a") throw GifException("Not a GIF89a file.")
        val w = u16(6); val h = u16(8)
        if (w == 0 || h == 0) throw GifException("Invalid GIF size.")
        val packed = u8(10)
        var i = 13
        val hasGct = packed and 0x80 != 0
        if (hasGct) i += 3 * (1 shl ((packed and 7) + 1))
        var frames = 0; var dur = 0; var pendingDelay = 0; var loops = false
        fun skipSub() { while (true) { val n = u8(i); i++; if (n == 0) break; i += n } }
        while (true) {
            when (u8(i)) {
                0x3B -> {
                    if (frames == 0) throw GifException("GIF has no frames.")
                    return Result(w, h, frames, dur, loops)
                }
                0x21 -> {
                    val label = u8(i + 1); i += 2
                    if (label == 0xF9) {
                        if (u8(i) != 4) throw GifException("Bad graphic control block.")
                        pendingDelay = u16(i + 2) * 10
                        i += 5
                        if (u8(i) != 0) throw GifException("Bad GCE terminator.")
                        i++
                    } else {
                        if (label == 0xFF && u8(i) == 11 && String(d, i + 1, 8, Charsets.US_ASCII) == "NETSCAPE") loops = true
                        skipSub()
                    }
                }
                0x2C -> {
                    val fx = u16(i + 1); val fy = u16(i + 3); val fw = u16(i + 5); val fh = u16(i + 7)
                    if (fw == 0 || fh == 0 || fx + fw > w || fy + fh > h) throw GifException("Frame ${frames + 1} outside canvas.")
                    val lp = u8(i + 9); i += 10
                    if (lp and 0x80 != 0) i += 3 * (1 shl ((lp and 7) + 1))
                    else if (!hasGct) throw GifException("Frame ${frames + 1} has no colour table.")
                    val mcs = u8(i); i++
                    if (mcs < 2 || mcs > 8) throw GifException("Bad LZW code size $mcs.")
                    skipSub()
                    frames++; dur += pendingDelay; pendingDelay = 0
                }
                else -> throw GifException("Corrupt GIF block 0x%02x at %d.".format(u8(i), i))
            }
        }
    }
}
