package com.vanu.gifmaker.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class Mp4FixTest {
    @Test
    fun fixesFragmentedFileWithDataOnlyFragment() {
        val src = File(System.getenv("GM_FMP4") ?: "/tmp/mountain.mp4")
        assumeTrue(src.isFile)
        val out = File("/tmp/gm_fixed.mp4")
        val ok = RandomAccessFile(src, "r").channel.use { FragmentedMp4Fix.fixChannel(it, out) }
        println("FIX ok=$ok in=${src.length()} out=${out.length()}")
        assertTrue(ok)
    }
}
