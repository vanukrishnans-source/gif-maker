package com.vanu.gifmaker.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

/** Independent reference GIF decoder (LZW + compositing) used to check the encoder bit-exactly. */
object RefDecoder {
    class Frame(val canvas: IntArray, val delayCs: Int)

    fun lzwDecode(data: ByteArray, minCode: Int, pixelCount: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val len = IntArray(4096)
        for (i in 0 until clear) { prefix[i] = -1; suffix[i] = i; len[i] = 1 }
        var width = minCode + 1; var next = eoi + 1; var old = -1
        val out = ByteArray(pixelCount); var op = 0
        var acc = 0L; var bits = 0; var p = 0
        val stack = IntArray(4097)
        fun firstOf(c: Int): Int { var x = c; while (prefix[x] >= 0) x = prefix[x]; return suffix[x] }
        while (true) {
            while (bits < width) {
                if (p >= data.size) throw IllegalStateException("LZW ran out of data (no EOI)")
                acc = acc or ((data[p++].toLong() and 0xFF) shl bits); bits += 8
            }
            val code = (acc and ((1L shl width) - 1)).toInt(); acc = acc ushr width; bits -= width
            if (code == clear) { width = minCode + 1; next = eoi + 1; old = -1; continue }
            if (code == eoi) break
            val emitCode: Int
            if (old < 0) {
                require(code < clear) { "first code after clear must be literal: $code" }
                emitCode = code
            } else {
                if (code < next) {
                    emitCode = code
                    if (next < 4096) { prefix[next] = old; suffix[next] = firstOf(code); len[next] = len[old] + 1; next++ }
                } else if (code == next) {
                    if (next < 4096) { prefix[next] = old; suffix[next] = firstOf(old); len[next] = len[old] + 1; next++ }
                    emitCode = code
                } else throw IllegalStateException("bad code $code next=$next width=$width")
                if (next == (1 shl width) && width < 12) width++
            }
            var x = emitCode; var sp = 0
            while (x >= 0) { stack[sp++] = suffix[x]; x = prefix[x] }
            while (sp > 0) { val v = stack[--sp]; if (op < out.size) out[op++] = v.toByte() else throw IllegalStateException("too many pixels") }
            old = emitCode
        }
        if (op != pixelCount) throw IllegalStateException("pixel count $op != $pixelCount")
        return out
    }

    fun decode(d: ByteArray): Triple<Int, Int, List<Frame>> {
        fun u16(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
        val w = u16(6); val h = u16(8)
        val packed = d[10].toInt() and 0xFF
        var i = 13
        var gct: IntArray? = null
        if (packed and 0x80 != 0) {
            val n = 1 shl ((packed and 7) + 1)
            gct = IntArray(n) { k -> ((d[i + 3 * k].toInt() and 0xFF) shl 16) or ((d[i + 3 * k + 1].toInt() and 0xFF) shl 8) or (d[i + 3 * k + 2].toInt() and 0xFF) }
            i += 3 * n
        }
        val canvas = IntArray(w * h)
        val frames = ArrayList<Frame>()
        var delay = 0; var trans = -1; var disposal = 0
        while (true) {
            when (d[i].toInt() and 0xFF) {
                0x3B -> return Triple(w, h, frames)
                0x21 -> {
                    val label = d[i + 1].toInt() and 0xFF; i += 2
                    if (label == 0xF9) {
                        val pk = d[i + 1].toInt() and 0xFF
                        disposal = (pk shr 2) and 7
                        delay = u16(i + 2)
                        trans = if (pk and 1 != 0) d[i + 4].toInt() and 0xFF else -1
                        i += 6
                    } else {
                        while (true) { val n = d[i].toInt() and 0xFF; i++; if (n == 0) break; i += n }
                    }
                }
                0x2C -> {
                    val fx = u16(i + 1); val fy = u16(i + 3); val fw = u16(i + 5); val fh = u16(i + 7)
                    val lp = d[i + 9].toInt() and 0xFF; i += 10
                    var table = gct
                    if (lp and 0x80 != 0) {
                        val n = 1 shl ((lp and 7) + 1)
                        val base = i
                        table = IntArray(n) { k -> ((d[base + 3 * k].toInt() and 0xFF) shl 16) or ((d[base + 3 * k + 1].toInt() and 0xFF) shl 8) or (d[base + 3 * k + 2].toInt() and 0xFF) }
                        i += 3 * n
                    }
                    val mcs = d[i].toInt() and 0xFF; i++
                    val bo = ByteArrayOutputStream()
                    while (true) { val n = d[i].toInt() and 0xFF; i++; if (n == 0) break; bo.write(d, i, n); i += n }
                    val idx = lzwDecode(bo.toByteArray(), mcs, fw * fh)
                    assertEquals("disposal must be 1", 1, disposal)
                    for (y in 0 until fh) for (x in 0 until fw) {
                        val v = idx[y * fw + x].toInt() and 0xFF
                        if (v == trans) continue
                        require(v < table!!.size) { "index $v outside table ${table.size}" }
                        canvas[(fy + y) * w + fx + x] = table[v]
                    }
                    frames.add(Frame(canvas.copyOf(), delay))
                    delay = 0; trans = -1; disposal = 0
                }
                else -> throw IllegalStateException("bad block at $i")
            }
        }
    }
}

class GifEncoderTest {

    @Test
    fun lzw_roundTrip_allCodeSizes_dictionaryOverflow() {
        val rnd = Random(7)
        for (mcs in 2..8) {
            for (n in intArrayOf(0, 1, 2, 5, 300, 5000, 70_000, 200_000)) {
                val maxV = 1 shl mcs
                // mix of noise (fills dictionary fast) and runs (long codes)
                val data = ByteArray(n) { k -> (if ((k / 997) % 2 == 0) rnd.nextInt(maxV) else (k / 50) % maxV).toByte() }
                if (n == 0) continue
                val bo = ByteArrayOutputStream()
                Lzw.encode(data, mcs, bo)
                val blocks = bo.toByteArray()
                val raw = ByteArrayOutputStream()
                var i = 0
                while (true) { val l = blocks[i].toInt() and 0xFF; i++; if (l == 0) break; raw.write(blocks, i, l); i += l }
                assertEquals("terminator must be last byte", blocks.size, i)
                val back = RefDecoder.lzwDecode(raw.toByteArray(), mcs, n)
                assertArrayEquals("mcs=$mcs n=$n", data, back)
            }
        }
    }

    private fun synthFrame(w: Int, h: Int, t: Int): IntArray {
        // Sunset-like vertical gradient (static) + a moving ball + noisy band (changing).
        val px = IntArray(w * h)
        val rnd = Random(t.toLong())
        for (y in 0 until h) for (x in 0 until w) {
            val r = (255 * y / h); val g = (90 + 100 * x / w); val b = (200 - 150 * y / h)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val cx = (w * (0.2 + 0.6 * t / 20.0)).toInt(); val cy = (h / 2 + (h / 4) * sin(t / 3.0)).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy < 15 * 15) px[y * w + x] = (0xFF shl 24) or 0xFFEE33
        }
        for (y in h - 12 until h) for (x in 0 until w) px[y * w + x] = (0xFF shl 24) or (rnd.nextInt(0x1000000))
        return px
    }

    private fun meanErr(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) {
            s += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)) +
                abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)) + abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return s / (3.0 * a.size)
    }

    private fun runAnim(name: String, opts: GifOptions, w: Int = 200, h: Int = 150, n: Int = 20, stepMs: Long = 100) {
        val f = File("/tmp/gifmaker_test_$name.gif")
        val src = (0 until n).map { synthFrame(w, h, it) }
        f.outputStream().buffered().use { os ->
            val gw = GifWriter(os, w, h, opts)
            src.forEachIndexed { k, px -> gw.addFrame(px, k * stepMs) }
            gw.finish(n * stepMs)
        }
        val bytes = f.readBytes()
        val v = GifValidator.validate(bytes)
        assertEquals(w, v.width); assertEquals(h, v.height)
        assertEquals(n * stepMs.toInt(), v.durationMs)
        val (dw, dh, frames) = RefDecoder.decode(bytes)
        assertEquals(w, dw); assertEquals(h, dh)
        assertEquals(n, frames.size)
        for (k in 0 until n) {
            val e = meanErr(src[k], frames[k].canvas)
            assertTrue("$name frame $k mean error $e too high", e < 12.0)
        }
        println("$name: ${bytes.size} bytes, ${frames.size} frames")
    }

    @Test fun anim_default() = runAnim("default", GifOptions())
    @Test fun anim_noDither_noTransparency() = runAnim("plain", GifOptions(dither = false, diffThreshold = -1))
    @Test fun anim_lossy_small() = runAnim("small", GifOptions(maxColors = 128, diffThreshold = 16, loopCount = -1))
    @Test fun anim_15fps_rounding() = runAnim("fps15", GifOptions(), n = 30, stepMs = 67)

    @Test
    fun noise_fullFrames_bitExactPalette() {
        // Large noisy frames fill the LZW dictionary many times per frame (the GIF Face Swap bug case).
        val rnd = Random(42)
        val w = 320; val h = 240
        val f = File("/tmp/gifmaker_test_noise.gif")
        val src = (0 until 4).map { IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0x1000000) } }
        f.outputStream().buffered().use { os ->
            val gw = GifWriter(os, w, h, GifOptions(dither = false))
            src.forEachIndexed { k, px -> gw.addFrame(px, k * 80L) }
            gw.finish(320)
        }
        val (_, _, frames) = RefDecoder.decode(f.readBytes())
        assertEquals(4, frames.size)
        assertEquals(4, GifValidator.validate(f).frames)
    }

    @Test
    fun identicalFrames_areMerged_andTinyIntervalsDropped() {
        val w = 64; val h = 48
        val px = synthFrame(w, h, 3)
        val bo = ByteArrayOutputStream()
        val gw = GifWriter(bo, w, h, GifOptions())
        gw.addFrame(px, 0); gw.addFrame(px, 100); gw.addFrame(px, 200)
        gw.addFrame(synthFrame(w, h, 9), 300)
        gw.addFrame(synthFrame(w, h, 12), 305) // < 2cs later: merged
        gw.finish(400)
        val v = GifValidator.validate(bo.toByteArray())
        assertEquals(2, v.frames)
        assertEquals(400, v.durationMs)
        val (_, _, frames) = RefDecoder.decode(bo.toByteArray())
        assertEquals(30, frames[0].delayCs); assertEquals(10, frames[1].delayCs)
    }

    @Test
    fun solidColour_andOnePixel() {
        for ((w, h) in listOf(1 to 1, 7 to 3, 100 to 100)) {
            val bo = ByteArrayOutputStream()
            val gw = GifWriter(bo, w, h, GifOptions())
            gw.addFrame(IntArray(w * h) { 0xFF336699.toInt() }, 0)
            gw.addFrame(IntArray(w * h) { 0xFF996633.toInt() }, 100)
            gw.finish(200)
            val (_, _, frames) = RefDecoder.decode(bo.toByteArray())
            assertEquals(2, frames.size)
            assertEquals(0x996633, frames[1].canvas[0])
        }
    }

    @Test(expected = GifException::class)
    fun validator_rejectsTruncated() {
        val bo = ByteArrayOutputStream()
        val gw = GifWriter(bo, 50, 50, GifOptions())
        gw.addFrame(synthFrame(50, 50, 1), 0); gw.finish(100)
        val b = bo.toByteArray()
        GifValidator.validate(b.copyOf(b.size - 20))
    }
}
