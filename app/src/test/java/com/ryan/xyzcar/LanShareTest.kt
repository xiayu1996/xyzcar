package com.ryan.xyzcar

import com.ryan.xyzcar.data.LanShare
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

class LanShareTest {
    @Test fun servesOnlyTokenPath() {
        val s = LanShare.start("hello 报告\n")
        assumeTrue("本机没有局域网地址", s != null)
        try {
            val url = s!!.urls.first()
            assertEquals("hello 报告\n", URL(url).readText())
            val bad = URL(url.substringBeforeLast('/') + "/wrong").openConnection() as HttpURLConnection
            assertEquals(404, bad.responseCode)
            try { bad.inputStream.close() } catch (_: FileNotFoundException) {}
        } finally {
            LanShare.stop()
        }
    }
}
