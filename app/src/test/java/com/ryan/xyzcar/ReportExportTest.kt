package com.ryan.xyzcar

import com.ryan.xyzcar.data.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportTest {
    @Test fun compactMergesRepeatsAndKeepsTail() {
        val log = buildString {
            repeat(5) { append("11-11 10:00:0$it.000 [media] media_button from=com.byd.mediacenter key=85\n") }
            append("11-11 10:00:09.000 [start] process app=0.0.5\n")
            repeat(500) { append("11-11 10:01:00.000 [snap] k$it=v\n") }
        }
        val out = DebugLog.compact(log, maxLines = 400).trimEnd().lines()
        assertEquals(400, out.size)
        assertEquals("11-11 10:01:00.000 [snap] k499=v", out.last())
        val head = DebugLog.compact(log.lines().take(6).joinToString("\n")).trimEnd().lines()
        assertEquals(2, head.size)
        assertTrue(head[0].endsWith(" x5"))
    }

    @Test fun redactMasksSecrets() {
        val s = DebugLog.redact("error=HTTP 401 x-jike-access-token: abc.def token=xyz123&a=1 phone=13800138000 imei 860000000000001 jwt eyJhbGciOiJI.eyJzdWIiOiIx.sig ok pkg=com.byd.mediacenter uid=1000")
        assertFalse(s.contains("abc.def"))
        assertFalse(s.contains("xyz123"))
        assertFalse(s.contains("13800138000"))
        assertFalse(s.contains("860000000000001"))
        assertFalse(s.contains("eyJhbGciOiJI"))
        assertTrue(s.contains("pkg=com.byd.mediacenter uid=1000"))
    }

    @Test fun kvQuotesValuesWithSpaces() {
        assertEquals("a", DebugLog.fmtValue("a"))
        assertEquals("\"a b\"", DebugLog.fmtValue("a b"))
        assertEquals("\"\"", DebugLog.fmtValue(""))
        assertEquals("null", DebugLog.fmtValue(null))
    }
}
