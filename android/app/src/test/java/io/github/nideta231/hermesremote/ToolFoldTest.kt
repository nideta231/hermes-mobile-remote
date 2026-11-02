package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.FoldedItem
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.data.foldTools
import io.github.nideta231.hermesremote.data.stepsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFoldTest {
    private fun think(k: String, streaming: Boolean = false) = ChatItem.Thinking(k, "hm", streaming)
    private fun tool(k: String, status: ToolStatus = ToolStatus.OK, dur: Double? = null, name: String = "read_file") =
        ChatItem.Tool(k, name, "{}", status = status, durationSec = dur)
    private fun user(k: String) = ChatItem.User(k, "hi")
    private fun text(k: String) = ChatItem.Assistant(k, "ok")
    private fun groups(l: List<FoldedItem>) = l.filterIsInstance<FoldedItem.Group>()

    @Test fun twoRowsFoldLoneToolAndThoughtsStay() {
        val folded = foldTools(listOf(think("a"), tool("b")), busy = false)
        assertEquals(1, folded.size)
        assertEquals(2, groups(folded)[0].items.size)
        assertEquals(listOf<FoldedItem>(FoldedItem.Item(tool("b"))), foldTools(listOf(tool("b")), false))
        val thoughts = listOf(think("a"), think("b"))
        assertEquals(thoughts.map { FoldedItem.Item(it) }, foldTools(thoughts, false))
    }

    @Test fun textUserAndDelegateSplitStretches() {
        val items = listOf(tool("1"), tool("2"), text("x"), tool("3"), tool("4"), user("u"), tool("5"), tool("6"),
            tool("d", name = Delegate.TOOL), tool("7"), tool("8"))
        val folded = foldTools(items, false)
        assertEquals(4, groups(folded).size)
        assertTrue(folded.contains(FoldedItem.Item(tool("d", name = Delegate.TOOL))))
        assertEquals(items.size - 8 + 4 + 0, folded.size)
    }

    @Test fun countsForMixedGroup() {
        val g = groups(foldTools(listOf(think("a"), tool("b", dur = 1.5), tool("c", ToolStatus.FAILED, 2.0), tool("d"),
            think("e")), false))[0]
        assertEquals(3, g.toolCount)
        assertEquals(2, g.thoughtCount)
        assertEquals(1, g.failedCount)
        assertEquals(3.5, g.durationSec, 0.0001)
    }

    @Test fun runningFlag() {
        val done = listOf(tool("a"), tool("b"))
        assertFalse(groups(foldTools(done + text("t") + done.map { it.copy(key = it.key + "2") }, false))[0].running)
        assertTrue(groups(foldTools(listOf(tool("a", ToolStatus.RUNNING), tool("b"), text("t")), false))[0].running)
        assertTrue(groups(foldTools(listOf(think("a", streaming = true), tool("b"), text("t")), false))[0].running)
        val two = foldTools(done + text("t") + done.map { it.copy(key = it.key + "2") }, true)
        assertFalse(groups(two)[0].running)
        assertTrue(groups(two)[1].running)
    }

    @Test fun lastTurnOnlyAfterLastUser() {
        val g = groups(foldTools(listOf(tool("a"), tool("b"), user("u"), tool("c"), tool("d"), text("t"), tool("e"), tool("f")), false))
        assertEquals(listOf(false, true, true), g.map { it.lastTurn })
    }

    @Test fun stepsSplitOnThoughts() {
        val steps = stepsOf(listOf(tool("a", ToolStatus.FAILED), think("t1"), tool("b", ToolStatus.RUNNING), tool("c"),
            think("t2", streaming = true)))
        assertEquals(3, steps.size)
        assertNull(steps[0].thought)
        assertEquals(1, steps[0].failedCount)
        assertFalse(steps[0].running)
        assertEquals("t1", steps[1].thought?.key)
        assertEquals(2, steps[1].tools.size)
        assertTrue(steps[1].running)
        assertEquals(0, steps[1].failedCount)
        assertEquals("t2", steps[2].thought?.key)
        assertTrue(steps[2].tools.isEmpty())
    }

    @Test fun inputUntouchedAndKeysStable() {
        val items = listOf(think("a"), tool("b"), text("t"))
        val copy = items.toList()
        val one = foldTools(items, false)
        assertEquals(copy, items)
        assertEquals(groups(one)[0].key, groups(foldTools(items, false))[0].key)
        assertEquals(one, foldTools(items, false))
    }
}
