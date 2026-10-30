package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.TextReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReadingTest {
    @Test fun markdownAndTextFilesReadInTheApp() {
        assertTrue(TextReading.isMarkdown("Data/x/report.MD"))
        assertFalse(TextReading.isMarkdown("notes.txt"))
        assertTrue(TextReading.readsInApp("/home/u/report.md"))
        assertTrue(TextReading.readsInApp("log.txt"))
        assertFalse(TextReading.readsInApp("app.apk"))
        assertFalse(TextReading.readsInApp("shot.png"))
    }

    @Test fun prepareNormalisesAndCutsOnALineBreak() {
        assertEquals(TextReading.Loaded("a\nb", false), TextReading.prepare("\uFEFFa\r\nb"))
        val long = (1..100).joinToString("\n") { "line $it" }
        val r = TextReading.prepare(long, max = 50)
        assertTrue(r.cut)
        assertTrue(r.text.length <= 50)
        assertFalse(r.text.endsWith("\n"))
    }
}
