package io.github.nideta231.hermesremote.data

import org.json.JSONArray
import org.json.JSONObject

data class Pairing(
    val url: String,
    val device: String,
    val token: String,
    /** Other addresses from the pairing code, so the first connect can fall back. */
    val alternates: List<String> = emptyList(),
    /** SHA-256 of the bridge's TLS certificate (pairing code v2). Required for the LAN. */
    val pin: String? = null,
)

/** One row of the desktop sidebar: the same list, from the same query. [id] is the stored id. */
data class SessionSummary(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val messageCount: Int,
    val lastActive: Double?,
    val model: String?,
    val pinned: Boolean = false,
    val startedAt: Double? = null,
    val tokens: Long = 0,
    val costUsd: Double = 0.0,
    val unread: Boolean = false,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: preview?.lineSequence()?.firstOrNull()?.take(80)
            ?.takeIf { it.isNotBlank() } ?: "Untitled session"
}

data class ModelOption(val provider: String, val providerName: String, val id: String, val label: String, val current: Boolean)

data class ModelCatalog(
    val currentModel: String?,
    val currentProvider: String?,
    val options: List<ModelOption>,
    val reasoningDefault: String = "medium",
    val reasoningLevels: List<String> = REASONING_LEVELS,
) {
    val selected: ModelOption?
        get() = options.firstOrNull { it.id == currentModel && it.provider == currentProvider }
            ?: options.firstOrNull { it.id == currentModel }
}

/** A dangerous command Hermes wants allowed. [id] is the JSON-RPC id the answer goes back to. */
data class ApprovalRequest(
    val id: String,
    val sessionId: String?,
    val command: String?,
    val description: String?,
    val choices: List<String>,
)

/** One question of the clarify tool. */
data class ClarifyQuestion(val qid: String?, val question: String, val choices: List<String>, val multiSelect: Boolean)

/** The clarify tool asking the user something: one question, or a batch. */
data class ClarifyRequest(val id: String, val sessionId: String?, val questions: List<ClarifyQuestion>) {
    val batch: Boolean get() = questions.size > 1 || questions.firstOrNull()?.qid != null
}

data class ComponentStatus(val key: String, val label: String, val ok: Boolean, val summary: String, val detail: String?)

/** Error returned by the bridge with its stable error code (never an IOException: not retried). */
class BridgeException(val httpCode: Int, val code: String, message: String) : Exception(message)

// ---------------------------------------------------------------- JSON helpers

fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

fun JSONObject.dbl(name: String): Double? = if (isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun parseSession(o: JSONObject) = SessionSummary(
    id = o.getString("id"),
    title = o.str("title"),
    preview = o.str("preview"),
    source = o.str("source"),
    messageCount = o.optInt("message_count", 0),
    lastActive = o.dbl("last_active") ?: o.dbl("last_activity_at") ?: o.dbl("started_at"),
    model = o.str("model"),
    pinned = o.optBoolean("pinned", false),
    startedAt = o.dbl("started_at"),
    // Same total the desktop sidebar shows and sorts by.
    tokens = o.optLong("input_tokens", 0) + o.optLong("output_tokens", 0),
    costUsd = o.dbl("actual_cost_usd") ?: o.dbl("estimated_cost_usd") ?: 0.0,
    unread = o.optBoolean("unread", false),
)

fun parseApproval(id: String, p: JSONObject) = ApprovalRequest(
    id = id,
    sessionId = p.str("session_id"),
    command = p.str("command"),
    description = p.str("description"),
    choices = p.optJSONArray("choices").strings().ifEmpty { listOf("once", "session", "deny") },
)

fun parseClarify(id: String, p: JSONObject): ClarifyRequest {
    val batch = p.optJSONArray("questions").objects().mapNotNull { q ->
        val text = q.str("question") ?: return@mapNotNull null
        ClarifyQuestion(q.str("qid"), text, q.optJSONArray("choices").strings(), q.optBoolean("multi_select"))
    }
    val single = p.str("question")?.let {
        listOf(ClarifyQuestion(null, it, p.optJSONArray("choices").strings(), p.optBoolean("multi_select")))
    }.orEmpty()
    return ClarifyRequest(id, p.str("session_id"), batch.ifEmpty { single })
}

/**
 * Build the picker from `model.options`: usable providers only, featured models first. A provider
 * without credentials, and models it marks unavailable, would only offer dead taps.
 */
fun parseCatalog(o: JSONObject): ModelCatalog {
    val raw = mutableListOf<ModelOption>()
    o.optJSONArray("providers").objects().forEach { p ->
        if (p.has("authenticated") && !p.isNull("authenticated") && !p.optBoolean("authenticated", true)) return@forEach
        val slug = p.str("slug") ?: return@forEach
        val name = p.str("name") ?: slug
        val unavailable = p.optJSONArray("unavailable_models").strings().toSet()
        val featured = p.optJSONArray("featured_models").strings().toSet()
        val current = p.optBoolean("is_current", false) || slug == o.str("provider")
        p.optJSONArray("models").strings()
            .filter { it !in unavailable }
            .sortedBy { if (it in featured) 0 else 1 }
            .forEach { m -> raw += ModelOption(slug, name, m, m.substringAfterLast('/'), current) }
    }
    // The same short name can exist under several providers ("claude-sonnet-5.5" is Copilot's own
    // id and also the tail of Nous Portal's "anthropic/claude-sonnet-5.5"). Identical labels in
    // different groups made the picker ambiguous; when a short name is not unique, show the full id.
    val shortCounts = raw.groupingBy { it.label.lowercase() }.eachCount()
    val options = raw.map { if ((shortCounts[it.label.lowercase()] ?: 0) > 1) it.copy(label = it.id) else it }
    return ModelCatalog(o.str("model"), o.str("provider"), options)
}

val REASONING_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

/** One slash command suggestion, as Hermes' own completer (`complete.slash`) returns it. */
data class SlashSuggestion(val text: String, val display: String, val meta: String, val skill: Boolean)

fun parseSuggestions(o: JSONObject): List<SlashSuggestion> = o.optJSONArray("items").objects().mapNotNull { i ->
    val text = i.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val name = "/" + text.removePrefix("/").substringBefore(' ')
    if (name.lowercase() in UNAVAILABLE_COMMANDS) return@mapNotNull null
    SlashSuggestion(text, i.str("display")?.takeIf { it.isNotBlank() } ?: name, i.str("meta").orEmpty(), i.str("kind") == "skill")
}

/**
 * What a slash command resolved to, as the desktop reads it (`parseCommandDispatch`): output to
 * show, another command to run, or a message to send or to put in the composer.
 */
sealed interface Dispatch {
    data class Output(val text: String) : Dispatch
    data class Alias(val target: String) : Dispatch
    data class Send(val message: String, val display: String?, val notice: String?) : Dispatch
    data class Prefill(val message: String, val notice: String?) : Dispatch
}

fun parseDispatch(o: JSONObject?): Dispatch? {
    o ?: return null
    val notice = o.str("notice")?.trim()?.takeIf { it.isNotEmpty() }
    return when (o.str("type")) {
        null, "exec", "plugin" -> Dispatch.Output(listOfNotNull(o.str("output"), o.str("warning")).joinToString("\n").ifBlank { "(no output)" })
        "alias" -> o.str("target")?.takeIf { it.isNotBlank() }?.let { Dispatch.Alias(it) }
        "send", "skill" -> Dispatch.Send(o.str("message").orEmpty(), o.str("display")?.trim()?.takeIf { it.isNotEmpty() }, notice)
        "prefill" -> Dispatch.Prefill(o.str("message").orEmpty(), notice)
        else -> null
    }
}

/**
 * Commands the phone does not offer: terminal-only, messaging-only, or desktop windows (pet,
 * memory graph, skins, profiles). Mirrors the desktop's slash registry.
 */
val UNAVAILABLE_COMMANDS = setOf(
    "/approve", "/deny", "/busy", "/clear", "/config", "/copy", "/cron", "/curator", "/exit", "/footer", "/gateway",
    "/history", "/image", "/indicator", "/insights", "/kanban", "/login", "/paste", "/platforms", "/plugins", "/quit",
    "/redraw", "/reload", "/reload-mcp", "/reload-skills", "/reload_mcp", "/reload_skills", "/restart", "/sb",
    "/set-home", "/sethome", "/skills", "/statusbar", "/toolsets", "/update", "/verbose", "/voice",
    "/browser", "/wake", "/handoff", "/pet", "/hatch", "/generate-pet", "/journey", "/learning", "/memory-graph",
    "/skin", "/profile", "/resume", "/sessions", "/switch",
)
