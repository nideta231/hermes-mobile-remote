package io.github.nideta231.hermesremote.data

import org.json.JSONArray
import org.json.JSONObject

enum class ToolStatus { RUNNING, OK, FAILED }

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val pending: Boolean = false) : ChatItem
    data class Assistant(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    /** The model's reasoning for the reply that follows; shown collapsed. */
    data class Thinking(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val result: String? = null,
        val status: ToolStatus = ToolStatus.OK,
        val durationSec: Double? = null,
        val callId: String? = null,
    ) : ChatItem
    /** [decided]: the choice made (here or on another screen), null while it waits. */
    data class Approval(override val key: String, val request: ApprovalRequest, val decided: String? = null) : ChatItem
    data class Clarify(override val key: String, val request: ClarifyRequest, val answer: String? = null) : ChatItem
    data class Notice(override val key: String, val text: String, val error: Boolean = false) : ChatItem
    /** Output of a slash command or a /btw side answer. Local to the app; not part of the transcript. */
    data class CommandOutput(override val key: String, val command: String, val text: String) : ChatItem
}

private const val SKILL_PREFIX = "[IMPORTANT: The user has invoked the "
private const val SKILL_INSTRUCTION = "The user has provided the following instruction alongside the skill invocation: "
private const val PLAN_PREFIX = "[/plan — plan mode]"
private const val STEER_OPEN = "[OUT-OF-BAND USER MESSAGE"
private const val STEER_CLOSE = "[/OUT-OF-BAND USER MESSAGE]"

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
    // A mid-turn steer is stored inside Hermes' marker wrapper; show the user's own words, as the desktop does.
    val steerOpen = text.indexOf(STEER_OPEN)
    if (steerOpen >= 0) {
        val bodyStart = text.indexOf('\n', steerOpen).let { if (it < 0) return text else it + 1 }
        val bodyEnd = text.indexOf(STEER_CLOSE, bodyStart)
        if (bodyEnd >= 0) return text.substring(bodyStart, bodyEnd).trim()
    }
    return text
}

/** Human-sized summary of a tool call's arguments (the command for terminal, else compact JSON). */
fun summarizeArgs(args: JSONObject?): String {
    args ?: return ""
    for (k in listOf("command", "path", "url", "query", "name", "goal", "code", "question")) {
        args.str(k)?.takeIf { it.isNotBlank() }?.let { return it.take(400) }
    }
    return if (args.length() == 0) "" else args.toString().take(300)
}

/** A tool result reads as a failure when it says so: non-zero exit, an error, success=false. */
fun toolFailed(result: Any?): Boolean {
    val o = when (result) {
        is JSONObject -> result
        is String -> runCatching { JSONObject(result) }.getOrNull()
        else -> null
    } ?: return false
    return (o.has("exit_code") && !o.isNull("exit_code") && o.optInt("exit_code", 0) != 0) ||
        (o.has("success") && !o.optBoolean("success", true)) ||
        (o.has("error") && !o.isNull("error") && o.opt("error").let { it == true || (it is String && it.isNotBlank()) })
}

private fun resultPreview(p: JSONObject): String? {
    p.str("summary")?.takeIf { it.isNotBlank() }?.let { return it }
    p.str("result_text")?.takeIf { it.isNotBlank() }?.let { return it }
    return when (val r = p.opt("result")) {
        null, JSONObject.NULL -> null
        is JSONObject -> r.str("output")?.takeIf { it.isNotBlank() } ?: r.toString()
        else -> r.toString()
    }?.take(4000)
}

/** What a synthetic user row (written by Hermes, not typed by the user) shows as. */
sealed interface SyntheticRow {
    /** Not shown at all: a compaction restatement or a model-facing note. */
    data object Drop : SyntheticRow
    /** A one-line timeline notice, like the desktop's "background process finished". */
    data class Notice(val text: String) : SyntheticRow
}

private fun displayText(metadata: Any?): String? {
    val o = when (metadata) {
        is JSONObject -> metadata
        is String -> runCatching { JSONObject(metadata) }.getOrNull()
        else -> null
    } ?: return null
    return o.str("display_text")?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Hermes stores its own notices (finished background processes and agents, model switches,
 * the request it restates after compacting context) as user rows so strict providers accept
 * them. They are not the user's words: classify them by `display_kind`, or by their fixed
 * prefix for rows written before Hermes typed them. Null: a real user message.
 */
fun syntheticUserRow(text: String, kind: String?, metadata: Any? = null): SyntheticRow? {
    when (kind) {
        "hidden" -> return SyntheticRow.Drop
        "model_switch" -> return SyntheticRow.Notice("Model changed")
        "personality_switch" -> return SyntheticRow.Notice("Personality changed")
        "auto_continue" -> return SyntheticRow.Notice("Resumed interrupted turn")
        "async_delegation_complete" -> return SyntheticRow.Notice(displayText(metadata) ?: "Background agent work finished")
        "process_complete", "internal_notification" -> return SyntheticRow.Notice(displayText(metadata) ?: "Background process finished")
    }
    val t = text.trimStart()
    return when {
        t.startsWith("[CONTEXT COMPACTION") || t.startsWith("[STILL IN PROGRESS") ||
            t.startsWith("[System:") || t.startsWith("[System note:") -> SyntheticRow.Drop
        t.startsWith("[IMPORTANT: Background process") || Regex("^\\[IMPORTANT: \\d+ background processes").containsMatchIn(t) ->
            SyntheticRow.Notice("Background process finished")
        t.startsWith("[ASYNC DELEGATION") -> SyntheticRow.Notice("Background agent work finished")
        else -> null
    }
}

/** Hermes' display transcript (`session.resume` / `session.history` messages) as chat items. */
object HistoryMapper {
    fun map(messages: JSONArray?): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        messages.objects().forEachIndexed { i, m ->
            if (m.str("display_kind") == "hidden") return@forEachIndexed
            val id = if (m.has("row_id") && !m.isNull("row_id")) "r${m.optLong("row_id")}" else "i$i"
            val text = m.str("text").orEmpty()
            when (m.optString("role")) {
                "user" -> if (text.isNotBlank()) when (val s = syntheticUserRow(text, m.str("display_kind"), m.opt("display_metadata"))) {
                    SyntheticRow.Drop -> {}
                    is SyntheticRow.Notice -> items += ChatItem.Notice("h-$id", s.text)
                    null -> items += ChatItem.User("h-$id", displayUserText(text))
                }
                "assistant" -> {
                    m.str("reasoning")?.takeIf { it.isNotBlank() }?.let { items += ChatItem.Thinking("h-$id-r", it.trim()) }
                    if (text.isNotBlank()) items += ChatItem.Assistant("h-$id", text.trim())
                }
                "tool" -> {
                    val name = m.str("name") ?: "tool"
                    val args = m.optJSONObject("args")
                    items += ChatItem.Tool("h-$id", name, m.str("context")?.takeIf { it.isNotBlank() } ?: summarizeArgs(args),
                        callId = m.str("tool_call_id"))
                }
                "system" -> if (text.isNotBlank()) items += ChatItem.Notice("h-$id", text.trim())
            }
        }
        return items
    }
}

/**
 * Folds one live Hermes event into the chat. Pure. [key] must be unique per event (the caller
 * uses the event's seq), so a list diff never confuses two items.
 */
object LiveReducer {
    fun apply(items: List<ChatItem>, type: String, p: JSONObject, key: String): List<ChatItem> {
        val out = items.toMutableList()
        fun seal() {
            for (i in out.indices.reversed()) {
                when (val it = out[i]) {
                    is ChatItem.Assistant -> if (it.streaming) {
                        if (it.text.isBlank()) out.removeAt(i) else out[i] = it.copy(text = it.text.trim(), streaming = false)
                    }
                    is ChatItem.Thinking -> if (it.streaming) out[i] = it.copy(text = it.text.trim(), streaming = false)
                    else -> {}
                }
                if (out.getOrNull(i) is ChatItem.User) break
            }
        }
        fun settleTools(to: ToolStatus) = out.replaceAll { if (it is ChatItem.Tool && it.status == ToolStatus.RUNNING) it.copy(status = to) else it }
        when (type) {
            "message.delta" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return items
                val last = out.lastOrNull()
                if (last is ChatItem.Assistant && last.streaming) out[out.lastIndex] = last.copy(text = last.text + text)
                else {
                    if (last is ChatItem.Thinking && last.streaming) out[out.lastIndex] = last.copy(streaming = false)
                    out += ChatItem.Assistant("live-$key", text.trimStart(), streaming = true)
                }
            }
            // thinking.delta is the spinner's status text ("( ˘⌣˘)♡ reasoning..."), not reasoning:
            // the desktop keeps it out of the transcript, and so does this reducer.
            "reasoning.delta" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return items
                val last = out.lastOrNull()
                if (last is ChatItem.Thinking && last.streaming) out[out.lastIndex] = last.copy(text = last.text + text)
                else out += ChatItem.Thinking("live-$key", text.trimStart(), streaming = true)
            }
            "reasoning.available" -> {
                val text = p.optString("text").trim()
                if (text.isNotEmpty() && out.lastOrNull() !is ChatItem.Thinking) out += ChatItem.Thinking("live-$key", text)
            }
            "message.interim" -> {
                val text = p.optString("text").trim()
                if (p.optBoolean("already_streamed")) seal()
                else if (text.isNotEmpty()) { seal(); out += ChatItem.Assistant("live-$key", text) }
            }
            "tool.start" -> {
                seal()
                val name = p.str("name") ?: "tool"
                val label = p.str("context")?.takeIf { it.isNotBlank() } ?: p.str("preview")?.takeIf { it.isNotBlank() }
                    ?: summarizeArgs(p.optJSONObject("args"))
                val callId = p.str("tool_id")
                if (callId != null && out.any { it is ChatItem.Tool && it.callId == callId }) return out
                out += ChatItem.Tool("live-$key", name, label, status = ToolStatus.RUNNING, callId = callId)
            }
            "tool.complete" -> {
                val callId = p.str("tool_id")
                val name = p.str("name")
                var idx = if (callId != null) out.indexOfLast { it is ChatItem.Tool && it.callId == callId } else -1
                if (idx < 0) idx = out.indexOfLast { it is ChatItem.Tool && it.status == ToolStatus.RUNNING && (name == null || it.name == name) }
                val failed = toolFailed(p.opt("result"))
                if (idx >= 0) {
                    val t = out[idx] as ChatItem.Tool
                    out[idx] = t.copy(status = if (failed) ToolStatus.FAILED else ToolStatus.OK,
                        result = resultPreview(p) ?: t.result, durationSec = p.dbl("duration_s"))
                } else {
                    out += ChatItem.Tool("live-$key", name ?: "tool", summarizeArgs(p.optJSONObject("args")), resultPreview(p),
                        if (failed) ToolStatus.FAILED else ToolStatus.OK, p.dbl("duration_s"), callId)
                }
            }
            "message.complete" -> {
                seal()
                val text = p.str("text")?.trim().orEmpty()
                val sinceUser = out.subList(out.indexOfLast { it is ChatItem.User } + 1, out.size)
                if (text.isNotEmpty() && sinceUser.none { it is ChatItem.Assistant && it.text.trim() == text } &&
                    out.lastOrNull() !is ChatItem.Assistant) {
                    out += ChatItem.Assistant("live-$key", text)
                }
                when (p.str("status")) {
                    "error" -> {
                        settleTools(ToolStatus.FAILED)
                        out += ChatItem.Notice("live-$key-n", p.str("error") ?: p.str("failure_reason") ?: "The turn failed.", error = true)
                    }
                    "interrupted" -> { settleTools(ToolStatus.FAILED); out += ChatItem.Notice("live-$key-n", "Stopped.") }
                    else -> settleTools(ToolStatus.OK)
                }
                p.str("warning")?.takeIf { it.isNotBlank() }?.let { out += ChatItem.Notice("live-$key-w", it) }
            }
            "error" -> out += ChatItem.Notice("live-$key", p.str("message") ?: "Hermes reported an error.", error = true)
            "btw.complete" -> out += ChatItem.CommandOutput("live-$key", "/btw ${p.str("question").orEmpty()}".trim(), p.optString("text"))
            "background.complete" -> out += ChatItem.CommandOutput("live-$key", "/background", p.optString("text"))
            else -> return items
        }
        return out
    }

    /** A question Hermes asked arrived (or was replayed on resume): add it once. */
    fun withApproval(items: List<ChatItem>, r: ApprovalRequest): List<ChatItem> =
        if (items.any { it is ChatItem.Approval && it.request.id == r.id }) items else sealAll(items) + ChatItem.Approval("ask-${r.id}", r)

    fun withClarify(items: List<ChatItem>, r: ClarifyRequest): List<ChatItem> =
        if (items.any { it is ChatItem.Clarify && it.request.id == r.id }) items else sealAll(items) + ChatItem.Clarify("ask-${r.id}", r)

    /** The question [id] was answered (here, on the desktop) or withdrawn. */
    fun resolve(items: List<ChatItem>, id: String, outcome: String): List<ChatItem> = items.map {
        when {
            it is ChatItem.Approval && it.request.id == id && it.decided == null -> it.copy(decided = outcome)
            it is ChatItem.Clarify && it.request.id == id && it.answer == null -> it.copy(answer = outcome)
            else -> it
        }
    }

    /** Questions still open: what the dock shows. */
    fun openApproval(items: List<ChatItem>) = items.lastOrNull { it is ChatItem.Approval && it.decided == null } as ChatItem.Approval?
    fun openClarify(items: List<ChatItem>) = items.lastOrNull { it is ChatItem.Clarify && it.answer == null } as ChatItem.Clarify?

    private fun sealAll(items: List<ChatItem>) = items.map {
        when (it) {
            is ChatItem.Assistant -> if (it.streaming) it.copy(streaming = false) else it
            is ChatItem.Thinking -> if (it.streaming) it.copy(streaming = false) else it
            else -> it
        }
    }
}

/**
 * The in-flight turn of a session the phone attaches to mid-run (`inflight` from session.resume):
 * the user's message and the reply so far, appended after the persisted history.
 */
fun withInflight(history: List<ChatItem>, inflight: JSONObject?): List<ChatItem> {
    inflight ?: return history
    val out = history.toMutableList()
    val raw = inflight.str("user").orEmpty()
    when (val s = syntheticUserRow(raw, inflight.str("display_kind"), inflight.opt("display_metadata"))) {
        SyntheticRow.Drop -> {}
        is SyntheticRow.Notice -> if ((out.lastOrNull() as? ChatItem.Notice)?.text != s.text) out += ChatItem.Notice("inflight-u", s.text)
        null -> {
            val user = displayUserText(raw).trim()
            if (user.isNotEmpty() && (out.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.text?.trim() != user) {
                out += ChatItem.User("inflight-u", user)
            }
        }
    }
    val reply = inflight.str("assistant").orEmpty()
    if (reply.isNotBlank()) out += ChatItem.Assistant("inflight-a", reply.trimStart(), streaming = inflight.optBoolean("streaming", true))
    return out
}

/**
 * A rebuilt list (resume after a reconnect, turn end) replaces the live one. Keep each item's key
 * from the item it replaces, otherwise the list sees every message as removed + new and
 * re-animates it (the visible flash).
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
            is ChatItem.Assistant -> take { it is ChatItem.Assistant && samePrefix(it.text, n.text) }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.User -> take { it is ChatItem.User && it.text == n.text }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.Thinking -> take { it is ChatItem.Thinking }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.Tool -> (take { it is ChatItem.Tool && (n.callId != null && it.callId == n.callId || it.name == n.name) } as ChatItem.Tool?)
                ?.let { old -> n.copy(key = old.key, result = n.result ?: old.result, durationSec = n.durationSec ?: old.durationSec,
                    status = if (old.status == ToolStatus.FAILED) ToolStatus.FAILED else n.status) } ?: n
            else -> n
        }
    }.distinctBy { it.key }
}

/** Same reply at two moments (streamed so far vs. final): one is a prefix of the other. */
private fun samePrefix(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    val k = minOf(40, x.length, y.length)
    return k > 0 && x.regionMatches(0, y, 0, k)
}
