package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.ChatWidth
import io.github.nideta231.hermesremote.ui.ToolDisplay
import io.github.nideta231.hermesremote.ui.chatWidthOf
import io.github.nideta231.hermesremote.ui.toolDisplayOf
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPrefsTest {
    @Test fun toolDisplayParses() {
        assertEquals(ToolDisplay.CLASSIC, toolDisplayOf(null))
        assertEquals(ToolDisplay.CLASSIC, toolDisplayOf(""))
        assertEquals(ToolDisplay.CLASSIC, toolDisplayOf("nonsense"))
        assertEquals(ToolDisplay.CLASSIC, toolDisplayOf("CLASSIC"))
        assertEquals(ToolDisplay.TREE, toolDisplayOf("TREE"))
    }

    @Test fun chatWidthParses() {
        assertEquals(ChatWidth.CLASSIC, chatWidthOf(null))
        assertEquals(ChatWidth.CLASSIC, chatWidthOf(""))
        assertEquals(ChatWidth.CLASSIC, chatWidthOf("nonsense"))
        assertEquals(ChatWidth.CLASSIC, chatWidthOf("CLASSIC"))
        assertEquals(ChatWidth.CENTERED, chatWidthOf("CENTERED"))
    }
}
