package com.vanu.gifmaker.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Host-side trial: encodes raw RGB24 frames produced by ffmpeg (same sampling grid as the app)
 * with the app's encoder. Skipped unless GM_TRIAL_RAW is set.
 *   GM_TRIAL_RAW=frames.rgb GM_TRIAL_W=480 GM_TRIAL_H=270 GM_TRIAL_FPS=10 GM_TRIAL_OUT=out.gif
 */
class HostTrialTest {
    @Test
    fun encodeRawFrames() {
        val raw = System.getenv("GM_TRIAL_RAW")
        assumeTrue(raw != null && File(raw).isFile)
        val w = System.getenv("GM_TRIAL_W")!!.toInt()
        val h = System.getenv("GM_TRIAL_H")!!.toInt()
        val fps = System.getenv("GM_TRIAL_FPS")!!.toDouble()
        val q = Quality.valueOf(System.getenv("GM_TRIAL_Q") ?: "BALANCED")
        val dither = (System.getenv("GM_TRIAL_DITHER") ?: "1") == "1"
        val out = File(System.getenv("GM_TRIAL_OUT")!!)
        val bytes = File(raw!!).readBytes()
        val n = bytes.size / (w * h * 3)
        val t0 = System.currentTimeMillis()
        out.outputStream().buffered().use { os ->
            val gw = GifWriter(os, w, h, GifOptions(q.maxColors, dither, q.diffThreshold, 0))
            val px = IntArray(w * h)
            for (f in 0 until n) {
                val base = f * w * h * 3
                for (i in 0 until w * h) {
                    val o = base + i * 3
                    px[i] = (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
                }
                gw.addFrame(px, Math.round(f * 1000.0 / fps))
            }
            gw.finish(Math.round(n * 1000.0 / fps))
            println("TRIAL ${out.name}: $n frames in, ${gw.framesWritten} written, ${out.length()} bytes, ${System.currentTimeMillis() - t0} ms")
        }
        GifValidator.validate(out)
    }
}
