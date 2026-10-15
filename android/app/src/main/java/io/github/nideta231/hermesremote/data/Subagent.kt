package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/**
 * A child agent the chat delegated work to (Hermes' `delegate_task`). Built from the `subagent.*`
 * events of the session and from `subagent.list` when the phone attaches mid-run.
 */
data class Subagent(
    val id: String,
    val goal: String,
    /** queued, running, completed, failed, error, timeout or interrupted (Hermes' words). */
    val status: String = "running",
    val model: String? = null,
    val toolCount: Int = 0,
    val lastTool: String? = null,
    /** 0 for a child of the chat's agent, 1 for a child of a child, ... */
    val depth: Int = 0,
    val startedAt: Long? = null,
    val durationSec: Double? = null,
    /** The child's final answer, cut short, once it ended. */
    val summary: String? = null,
) {
    val running: Boolean get() = status == "running" || status == "queued"
    val failed: Boolean get() = status in FAILED

    private companion object {
        val FAILED = setOf("failed", "error", "timeout", "interrupted")
    }
}

/** Folds the session's `subagent.*` events into the list of children. Pure. */
object SubagentReducer {
    /** True for the events this reducer reads. */
    fun handles(type: String) = type in EVENTS

    fun apply(list: List<Subagent>, type: String, p: JSONObject, nowMs: Long): List<Subagent> {
        if (type !in EVENTS) return list
        val id = p.str("subagent_id")?.takeIf { it.isNotBlank() } ?: return list  // older Hermes: nothing to key on
        val old = list.firstOrNull { it.id == id }
        val base = old ?: Subagent(id, goal = p.str("goal").orEmpty(), status = if (type == "subagent.spawn_requested") "queued" else "running",
            startedAt = nowMs)
        val next = base.copy(
            goal = p.str("goal")?.takeIf { it.isNotBlank() } ?: base.goal,
            model = p.str("model")?.takeIf { it.isNotBlank() } ?: base.model,
            depth = if (p.has("depth") && !p.isNull("depth")) p.optInt("depth") else base.depth,
            toolCount = if (p.has("tool_count") && !p.isNull("tool_count")) p.optInt("tool_count") else base.toolCount,
        ).let { s ->
            when (type) {
                "subagent.start" -> s.copy(status = "running")
                "subagent.tool" -> s.copy(status = "running", lastTool = p.str("tool_name") ?: s.lastTool,
                    toolCount = if (p.has("tool_count")) s.toolCount else s.toolCount + 1)
                "subagent.progress", "subagent.thinking" -> if (s.status == "queued") s.copy(status = "running") else s
                "subagent.complete" -> s.copy(status = p.str("status")?.takeIf { it.isNotBlank() } ?: "completed",
                    durationSec = p.dbl("duration_seconds") ?: s.durationSec,
                    summary = p.str("summary")?.trim()?.takeIf { it.isNotEmpty() }?.take(SUMMARY_MAX) ?: s.summary)
                else -> s
            }
        }
        return if (old == null) list + next else list.map { if (it.id == id) next else it }
    }

    /** `subagent.list` on attach: the live roster. Children we knew that ended meanwhile keep their last state. */
    fun fromRoster(known: List<Subagent>, roster: JSONObject): List<Subagent> {
        val live = roster.optJSONArray("subagents").objects().mapNotNull { r ->
            val id = r.str("subagent_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val prev = known.firstOrNull { it.id == id }
            Subagent(id, r.str("goal") ?: prev?.goal.orEmpty(), r.str("status") ?: "running",
                r.str("model") ?: prev?.model, r.optInt("tool_count", prev?.toolCount ?: 0),
                r.str("last_tool") ?: prev?.lastTool, r.optInt("depth", prev?.depth ?: 0),
                r.dbl("started_at")?.let { (it * 1000).toLong() } ?: prev?.startedAt)
        }
        val liveIds = live.map { it.id }.toSet()
        return known.filter { it.id !in liveIds && !it.running } + live
    }

    /** The user sent a new message: the children that ended belong to the last exchange. Running
     *  ones stay: Hermes runs delegations in the background, past the end of the turn that started
     *  them, and starts a turn of its own to deliver their result. */
    fun newTurn(list: List<Subagent>): List<Subagent> = list.filter { it.running }

    private val EVENTS = setOf("subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking",
        "subagent.tool", "subagent.complete")
    private const val SUMMARY_MAX = 600
}
