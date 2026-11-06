package com.ryan.xyzcar

import com.ryan.xyzcar.data.Updater
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class UpdaterTest {
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val release = """{"tag_name":"v0.0.3","body":"## What's Changed\n- feat(ui): add update by @a in https://x/1\n\n**Full Changelog**: https://x"}"""

    private lateinit var gitee: MockWebServer
    private lateinit var github: MockWebServer
    private lateinit var dir: File

    @Before fun setUp() {
        gitee = MockWebServer().apply { start() }
        github = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("upd").toFile()
    }

    @After fun tearDown() {
        gitee.shutdown(); github.shutdown(); dir.deleteRecursively()
    }

    private fun updater() = Updater(
        OkHttpClient(),
        dir,
        listOf(
            Updater.Source("Gitee", gitee.url("/latest").toString(), gitee.url("/dl").toString().trimEnd('/')),
            Updater.Source("GitHub", github.url("/latest").toString(), github.url("/dl").toString().trimEnd('/')),
        ),
    )

    private fun serve(server: MockWebServer, latest: Int = 200, shaText: String = "$sha  xyzcar-0.0.3.apk\n", body: ByteArray = apk) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/latest" -> MockResponse().setResponseCode(latest).setBody(release)
                "/dl/v0.0.3/xyzcar-0.0.3.apk.sha256" -> MockResponse().setBody(shaText)
                "/dl/v0.0.3/xyzcar-0.0.3.apk" -> MockResponse().setBody(Buffer().write(body))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun latestFallsBackToSecondSource() {
        serve(gitee, latest = 503)
        serve(github)
        val r = updater().latest()
        assertEquals("v0.0.3", r.tag)
        assertEquals("• feat(ui): add update", r.notes)
    }

    @Test fun latestFailsWithBothReasons() {
        serve(gitee, latest = 503)
        serve(github, latest = 404)
        try {
            updater().latest(); fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Gitee：HTTP 503"))
            assertTrue(e.message!!.contains("GitHub：HTTP 404"))
        }
    }

    @Test fun downloadRejectsTamperedFileAndUsesNextSource() {
        serve(gitee, body = apk.copyOf().also { it[0] = 99 })
        serve(github)
        var last = 0f
        val f = updater().download(Updater.Release("v0.0.3", "")) { last = it }
        assertEquals(sha, Updater.sha256(f))
        assertEquals(1f, last)
        assertEquals(listOf("xyzcar-0.0.3.apk"), dir.list()!!.toList())
    }

    @Test fun downloadFailsWhenNoSourceMatches() {
        serve(gitee, shaText = "not-a-hash")
        serve(github, body = ByteArray(10))
        try {
            updater().download(Updater.Release("v0.0.3", "")) {}; fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("校验"))
        }
        assertFalse(File(dir, "xyzcar-0.0.3.apk").exists())
    }

    @Test fun versionCompare() {
        assertTrue(Updater.isNewer("v0.0.3", "0.0.2"))
        assertTrue(Updater.isNewer("0.0.10", "0.0.9"))
        assertTrue(Updater.isNewer("0.1", "0.0.9"))
        assertFalse(Updater.isNewer("v0.0.2", "0.0.2"))
        assertFalse(Updater.isNewer("0.0.1", "0.0.2"))
    }

    @Test fun parsing() {
        assertNull(Updater.parseLatest("""{"message":"404 Not Found"}"""))
        assertNull(Updater.parseSha("abc"))
        assertEquals(sha, Updater.parseSha("${sha.uppercase()}  file.apk"))
    }
}
