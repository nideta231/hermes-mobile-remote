package io.github.nideta231.hermesremote.data

import org.json.JSONArray
import org.json.JSONObject

enum class ToolStatus { RUNNING, OK, FAILED }

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val pending: Boolean = false) : ChatItem
    data class Assistant(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val result: String? = null,
        val status: ToolStatus = ToolStatus.OK,
        val durationSec: Double? = null,
        val callId: String? = null,
    ) : ChatItem
    data class Approval(override val key: String, val request: ApprovalRequest, val decided: String? = null) : ChatItem
    data class Notice(override val key: String, val text: String, val error: Boolean = false) : ChatItem
    /** Output of a slash command that ran on the PC. Local to the app; not part of the transcript. */
    data class CommandOutput(override val key: String, val command: String, val text: String) : ChatItem
}

private const val SKILL_PREFIX = "[IMPORTANT: The user has invoked the "
private const val SKILL_INSTRUCTION = "The user has provided the following instruction alongside the skill invocation: "
private const val PLAN_PREFIX = "[/plan — plan mode]"

/**
 * A user turn as the user typed it. Skills and /plan expand into long prompts before they reach
 * the model; show "/humanizer fix this" instead of the whole skill body, like the desktop app does.
 */
fun displayUserText(text: String): String {
    if (text.startsWith(SKILL_PREFIX)) {
        val name = Regex("^" + Regex.escape(SKILL_PREFIX) + "\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val label = if (name.startsWith("/")) name else "/$name"
        val at = text.lastIndexOf(SKILL_INSTRUCTION)
        val instruction = if (at < 0) "" else text.substring(at + SKILL_INSTRUCTION.length).substringBefore("\n\n[Runtime note:").trim()
        return if (instruction.isEmpty()) label else "$label $instruction"
    }
    if (text.startsWith(PLAN_PREFIX)) {
        val task = text.substringAfter("Task to plan:\n", "").substringBefore("\n\n").trim()
        return if (task.isEmpty()) "/plan" else "/plan $task"
    }
    return text
}

private fun contentText(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is String -> v
    is JSONArray -> (0 until v.length()).mapNotNull { i ->
        val part = v.opt(i)
        when (part) {
            is String -> part
            is JSONObject -> part.str("text") ?: if (part.optString("type").contains("image")) "[image]" else null
            else -> null
        }
    }.joinToString("\n")
    else -> v.toString()
}

/** Human-sized summary of a tool call's arguments (the command for terminal, else compact JSON). */
fun summarizeArgs(name: String, rawArgs: String?): String {
    if (rawArgs.isNullOrBlank()) return ""
    val obj = runCatching { JSONObject(rawArgs) }.getOrNull() ?: return rawArgs.take(300)
    for (k in listOf("command", "path", "url", "query", "name", "goal", "code")) {
        obj.str(k)?.let { return it.take(400) }
    }
    return obj.toString().take(300)
}

/** Converts persisted Hermes history into chat items. Tool results attach to their call by id. */
object HistoryMapper {
    fun map(messages: JSONArray): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        val toolIndex = HashMap<String, Int>()
        for (m in messages.objects()) {
            val id = m.optString("id")
            when (m.optString("role")) {
                "user" -> items += ChatItem.User("h-$id", displayUserText(contentText(m.opt("content"))))
                "assistant" -> {
                    val text = contentText(m.opt("content"))
                    if (text.isNotBlank()) items += ChatItem.Assistant("h-$id", text.trim())
                    m.optJSONArray("tool_calls")?.objects()?.forEachIndexed { i, tc ->
                        val fn = tc.optJSONObject("function")
                        val name = fn?.str("name") ?: tc.str("name") ?: "tool"
                        val callId = tc.str("id") ?: tc.str("call_id")
                        if (callId != null) toolIndex[callId] = items.size
                        items += ChatItem.Tool("h-$id-$i", name, summarizeArgs(name, fn?.str("arguments")), callId = callId)
                    }
                }
                "tool" -> {
                    val result = contentText(m.opt("content"))
                    val idx = m.str("tool_call_id")?.let { toolIndex[it] }
                    val failed = runCatching { JSONObject(result) }.getOrNull()?.let { r ->
                        (r.has("exit_code") && r.optInt("exit_code", 0) != 0) || (r.has("success") && !r.optBoolean("success", true))
                    } ?: false
                    if (idx != null) {
                        val t = items[idx] as ChatItem.Tool
                        items[idx] = t.copy(result = result, status = if (failed) ToolStatus.FAILED else ToolStatus.OK)
                    } else {
                        items += ChatItem.Tool("h-$id", m.str("tool_name") ?: "tool", "", result,
                            if (failed) ToolStatus.FAILED else ToolStatus.OK)
                    }
                }
            }
        }
        return items
    }

    /** History up to (excluding) the last user turn: the in-flight turn is rebuilt from replayed events. */
    fun withoutLastTurn(messages: JSONArray): JSONArray {
        val list = messages.objects()
        val lastUser = list.indexOfLast { it.optString("role") == "user" }
        return if (lastUser < 0) messages else JSONArray(list.subList(0, lastUser))
    }

    fun lastUserText(messages: JSONArray): String? =
        messages.objects().lastOrNull { it.optString("role") == "user" }?.let { displayUserText(contentText(it.opt("content"))) }
}

/** Folds live run events into the chat. Pure; event ids already de-duplicated by the caller. */
object LiveReducer {
    fun apply(items: List<ChatItem>, ev: SseEvent): List<ChatItem> {
        val out = items.toMutableList()
        val d = ev.data
        fun closeStreaming() {
            val last = out.lastOrNull()
            if (last is ChatItem.Assistant && last.streaming) {
                if (last.text.isBlank()) out.removeAt(out.lastIndex) else out[out.lastIndex] = last.copy(text = last.text.trim(), streaming = false)
            }
        }
        when (ev.name) {
            "message.delta" -> {
                val delta = d.optString("delta")
                val last = out.lastOrNull()
                if (last is ChatItem.Assistant && last.streaming) out[out.lastIndex] = last.copy(text = last.text + delta)
                else out += ChatItem.Assistant("live-${ev.id}", delta.trimStart(), streaming = true)
            }
            "message.interim" -> if (!d.optBoolean("already_streamed")) {
                closeStreaming()
                d.str("text")?.takeIf { it.isNotBlank() }?.let { out += ChatItem.Assistant("live-${ev.id}", it.trim()) }
            }
            "tool.started" -> {
                closeStreaming()
                val name = d.str("tool") ?: d.str("tool_name") ?: "tool"
                out += ChatItem.Tool("live-${ev.id}", name, d.str("preview").orEmpty(), status = ToolStatus.RUNNING)
            }
            "tool.completed", "tool.failed" -> {
                val name = d.str("tool") ?: d.str("tool_name")
                val idx = out.indexOfLast { it is ChatItem.Tool && it.status == ToolStatus.RUNNING && (name == null || it.name == name) }
                val failed = ev.name == "tool.failed" || (d.has("error") && d.opt("error").let { it == true || (it is String && it.isNotEmpty()) })
                if (idx >= 0) {
                    val t = out[idx] as ChatItem.Tool
                    out[idx] = t.copy(status = if (failed) ToolStatus.FAILED else ToolStatus.OK,
                        result = d.str("preview") ?: t.result, durationSec = d.dbl("duration"))
                }
            }
            "approval.request" -> {
                closeStreaming()
                parseApproval(d)?.let { out += ChatItem.Approval("live-${ev.id}", it) }
            }
            "approval.responded" -> {
                val idx = out.indexOfLast { it is ChatItem.Approval && it.decided == null }
                if (idx >= 0) out[idx] = (out[idx] as ChatItem.Approval).copy(decided = d.str("choice") ?: "answered")
            }
            "subagent.start" -> out += ChatItem.Notice("live-${ev.id}", "Subagent started: ${d.str("goal") ?: d.str("preview") ?: ""}".trim())
            "subagent.complete" -> out += ChatItem.Notice("live-${ev.id}", "Subagent ${d.str("status") ?: "finished"}: ${d.str("summary")?.take(200) ?: ""}".trim())
            "run.completed" -> {
                closeStreaming()
                val output = d.str("output")
                if (out.lastOrNull() !is ChatItem.Assistant && !output.isNullOrBlank()) out += ChatItem.Assistant("live-${ev.id}", output.trim())
                out.replaceAll { if (it is ChatItem.Tool && it.status == ToolStatus.RUNNING) it.copy(status = ToolStatus.OK) else it }
            }
            "run.failed", "run.cancelled", "run.interrupted" -> {
                closeStreaming()
                out.replaceAll { if (it is ChatItem.Tool && it.status == ToolStatus.RUNNING) it.copy(status = ToolStatus.FAILED) else it }
                val label = ev.name.removePrefix("run.")
                out += ChatItem.Notice("live-${ev.id}", "Run $label" + (d.str("error")?.let { ": $it" } ?: ""), error = ev.name != "run.cancelled")
            }
        }
        return out
    }
}

/**
 * Persisted history replaces the live items when a run settles. Keep each message's key from the
 * live item it replaces, otherwise the list sees every reply as removed + new and re-animates it
 * (the visible flash at the end of a response).
 */
fun reuseKeys(old: List<ChatItem>, fresh: List<ChatItem>): List<ChatItem> {
    val used = BooleanArray(old.size)
    fun take(match: (ChatItem) -> Boolean): ChatItem? {
        val i = old.indices.firstOrNull { !used[it] && match(old[it]) } ?: return null
        used[i] = true
        return old[i]
    }
    return fresh.map { n ->
        when (n) {
            is ChatItem.Assistant -> take { it is ChatItem.Assistant && it.text.trim() == n.text.trim() }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.User -> take { it is ChatItem.User && it.text == n.text }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.Tool -> take { it is ChatItem.Tool && it.name == n.name }?.let { n.copy(key = it.key) } ?: n
            else -> n
        }
    }.distinctBy { it.key }
}
