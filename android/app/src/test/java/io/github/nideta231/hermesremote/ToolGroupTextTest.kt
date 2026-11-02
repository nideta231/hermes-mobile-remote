package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.failedText
import io.github.nideta231.hermesremote.ui.groupSummary
import io.github.nideta231.hermesremote.ui.groupTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolGroupTextTest {
    @Test fun titleRunningFinishedAndNoDuration() {
        assertEquals("Working", groupTitle(running = true, durationSec = 3.0))
        assertEquals("Worked for 12.3s", groupTitle(running = false, durationSec = 12.3))
        assertEquals("Worked", groupTitle(running = false, durationSec = 0.0))
    }

    @Test fun summarySingularAndPlural() {
        assertEquals("1 thought · 1 tool call", groupSummary(thoughts = 1, tools = 1))
        assertEquals("2 thoughts · 5 tool calls", groupSummary(thoughts = 2, tools = 5))
        assertEquals("3 tool calls", groupSummary(thoughts = 0, tools = 3))
    }

    @Test fun failedText() {
        assertNull(failedText(0))
        assertEquals("2 failed", failedText(2))
    }
}
