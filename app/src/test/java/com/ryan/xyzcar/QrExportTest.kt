package com.ryan.xyzcar

import com.ryan.xyzcar.ui.screens.qrChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

class QrExportTest {
    @Test fun chunksRoundTrip() {
        val text = (1..2000).joinToString("\n") { "第 $it 行 [launch] action=android.intent.action.MAIN multiWindow=false" }
        val chunks = qrChunks(text)
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { i, c -> assertTrue(c.startsWith("XYZ ${i + 1}/${chunks.size} ")) }
        val b64 = chunks.joinToString("") { it.substringAfter(' ').substringAfter(' ') }
        val back = GZIPInputStream(Base64.getDecoder().decode(b64).inputStream()).readBytes().decodeToString()
        assertEquals(text, back)
    }
}
