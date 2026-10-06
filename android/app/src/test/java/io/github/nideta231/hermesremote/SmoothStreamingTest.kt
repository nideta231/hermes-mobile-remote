package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.SseEvent
import io.github.nideta231.hermesremote.data.coalesceDeltas
import io.github.nideta231.hermesremote.data.reuseKeys
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothStreamingTest {
    private fun delta(id: Long, t: String) = SseEvent(id, "message.delta", JSONObject().put("delta", t))

    @Test fun burstOfDeltasBecomesOneEventWithNewestId() = runBlocking<Unit> {
        val out = flow {
            emit(delta(1, "He")); emit(delta(2, "llo")); emit(delta(3, " there"))
        }.coalesceDeltas(windowMs = 10_000).toList()
        assertEquals(1, out.size)
        assertEquals("Hello there", out[0].data.getString("delta"))
        assertEquals(3L, out[0].id)
    }

    @Test fun otherEventsFlushPendingTextFirst() = runBlocking<Unit> {
        val out = flow {
            emit(delta(1, "a")); emit(delta(2, "b"))
            emit(SseEvent(3, "tool.started", JSONObject()))
            emit(delta(4, "c"))
        }.coalesceDeltas(windowMs = 10_000).toList()
        assertEquals(listOf("message.delta", "tool.started", "message.delta"), out.map { it.name })
        assertEquals("ab", out[0].data.getString("delta"))
        assertEquals("c", out[2].data.getString("delta"))
    }

    @Test fun settledHistoryKeepsLiveKeysSoNothingReanimates() {
        val live = listOf(ChatItem.User("u-1", "hi"), ChatItem.Assistant("live-7", "Hello", streaming = false))
        val history = listOf(ChatItem.User("h-1", "hi"), ChatItem.Assistant("h-2", "Hello"))
        assertEquals(listOf("u-1", "live-7"), reuseKeys(live, history).map { it.key })
    }
}
