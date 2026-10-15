package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentReducer
import io.github.nideta231.hermesremote.ui.detail
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTest {
    private fun ev(list: List<Subagent>, type: String, json: String) = SubagentReducer.apply(list, type, JSONObject(json), 1_000L)

    @Test fun followsAChildFromSpawnToItsEnd() {
        var l = ev(emptyList(), "subagent.spawn_requested", """{"subagent_id": "s1", "goal": "Read the docs", "task_count": 1, "task_index": 0}""")
        assertEquals("queued", l.single().status)
        assertTrue(l.single().running)
        l = ev(l, "subagent.start", """{"subagent_id": "s1", "goal": "Read the docs", "model": "sonnet", "depth": 0}""")
        assertEquals("running", l.single().status)
        assertEquals("sonnet", l.single().model)
        l = ev(l, "subagent.tool", """{"subagent_id": "s1", "goal": "Read the docs", "tool_name": "read_file", "text": "README.md"}""")
        l = ev(l, "subagent.tool", """{"subagent_id": "s1", "goal": "Read the docs", "tool_name": "search_files", "tool_count": 5}""")
        assertEquals("search_files", l.single().lastTool)
        assertEquals(5, l.single().toolCount)  // Hermes' count wins over ours
        l = ev(l, "subagent.complete", """{"subagent_id": "s1", "goal": "Read the docs", "status": "completed",
            "summary": "  Found 3 pages.  ", "duration_seconds": 65.2}""")
        val s = l.single()
        assertFalse(s.running)
        assertFalse(s.failed)
        assertEquals("Found 3 pages.", s.summary)
        assertEquals("done in 1:05 · 5 tools · sonnet", detail(s, null))
    }

    @Test fun keepsParallelChildrenApartAndMarksFailures() {
        var l = ev(emptyList(), "subagent.start", """{"subagent_id": "a", "goal": "A"}""")
        l = ev(l, "subagent.start", """{"subagent_id": "b", "goal": "B", "depth": 1}""")
        l = ev(l, "subagent.complete", """{"subagent_id": "b", "goal": "B", "status": "timeout"}""")
        assertEquals(listOf("a", "b"), l.map { it.id })
        assertTrue(l[0].running)
        assertTrue(l[1].failed)
        assertEquals(1, l[1].depth)
        assertEquals("timeout", detail(l[1], null))
    }

    @Test fun eventsWithoutAnIdAndOtherEventsAreIgnored() {
        val l = listOf(Subagent("a", "A"))
        assertEquals(l, ev(l, "subagent.start", """{"goal": "old Hermes"}"""))
        assertEquals(l, ev(l, "message.delta", """{"subagent_id": "a", "text": "x"}"""))
        assertFalse(SubagentReducer.handles("message.delta"))
        assertTrue(SubagentReducer.handles("subagent.tool"))
    }

    @Test fun aRosterCatchesUpAfterAttachingMidRun() {
        val known = listOf(Subagent("old", "ended", status = "completed"), Subagent("gone", "lost its end", status = "running"),
            Subagent("a", "A", toolCount = 2))
        val roster = JSONObject("""{"subagents": [
            {"subagent_id": "a", "goal": "A", "status": "running", "tool_count": 4, "last_tool": "terminal", "started_at": 12.5},
            {"subagent_id": "c", "goal": "C", "status": "queued"}
        ], "delegations": []}""")
        val l = SubagentReducer.fromRoster(known, roster)
        // Ended ones stay; a "running" one Hermes no longer lists is dropped; live ones come from the roster.
        assertEquals(listOf("old", "a", "c"), l.map { it.id })
        assertEquals(4, l[1].toolCount)
        assertEquals("terminal", l[1].lastTool)
        assertEquals(12_500L, l[1].startedAt)
        assertEquals("queued", detail(l[2], null))
    }

    @Test fun aNewTurnClearsTheEndedOnes() {
        val l = listOf(Subagent("a", "A", status = "completed"), Subagent("b", "B", status = "running"))
        assertEquals(listOf("b"), SubagentReducer.newTurn(l).map { it.id })
    }

    @Test fun theLineReadsWellWhileRunning() {
        assertEquals("terminal · 3 tools · gpt-5 · 0:42", detail(Subagent("a", "A", lastTool = "terminal", toolCount = 3, model = "gpt-5"), 42))
        assertEquals("running · 1:02:03", detail(Subagent("a", "A"), 3723))
        assertNull(Subagent("a", "A", status = "completed").summary)
    }
}
