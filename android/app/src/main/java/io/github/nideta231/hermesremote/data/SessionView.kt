package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * How the session drawer is grouped, sorted and filtered. Mirrors the desktop sidebar's
 * filter menu (grouping: date / project / status; ordering: updated / created / status /
 * tokens / cost; status and project filters).
 */
enum class SessionGrouping(val label: String) { DATE("Date"), PROJECT("Project"), STATUS("Status") }

enum class SessionOrdering(val label: String) {
    UPDATED("Last updated"), CREATED("Created"), STATUS("Status"), TOKENS("Tokens used"), COST("Cost")
}

/** A session's state right now. [order] is the "needs you first" order used by grouping and sorting. */
enum class SessionStatus(val label: String, val order: Int) {
    WAITING("Needs input", 0), WORKING("Working", 1), UNREAD("Unread", 2), IDLE("Idle", 3)
}

/** A project from the backend's `projects.tree`; [sessionIds] is the membership desktop groups by. */
data class ProjectRef(val id: String, val label: String, val sessionIds: Set<String>)

data class SessionView(
    val grouping: SessionGrouping = SessionGrouping.DATE,
    val ordering: SessionOrdering = SessionOrdering.UPDATED,
    val statuses: Set<SessionStatus> = emptySet(),
    /** Project ids to show; [NO_PROJECT] is the backend's "Home" bucket for chats outside every project. */
    val projects: Set<String> = emptySet(),
) {
    val filtersActive: Boolean get() = statuses.isNotEmpty() || projects.isNotEmpty()
    val customized: Boolean get() = this != SessionView()

    companion object {
        const val NO_PROJECT = "__no_project__"
        const val NO_PROJECT_LABEL = "Home"
    }
}

/** [key] is unique per group (two projects may share a label). */
data class SessionGroup(val label: String, val items: List<SessionSummary>, val key: String = label)

fun statusOf(s: SessionSummary, live: Map<String, String>): SessionStatus = when (live[s.id]) {
    null, "idle" -> if (s.unread) SessionStatus.UNREAD else SessionStatus.IDLE
    "waiting" -> SessionStatus.WAITING
    else -> SessionStatus.WORKING
}

private val dateOrder = listOf("Today", "Yesterday", "This week", "This month", "Older")

fun dateGroupOf(ts: Double?, now: Calendar = Calendar.getInstance()): String {
    ts ?: return "Older"
    val then = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
    val days = ((now.timeInMillis - then.timeInMillis) / 86_400_000L).toInt()
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today"
        days < 2 -> "Yesterday"
        days < 7 -> "This week"
        days < 31 -> "This month"
        else -> "Older"
    }
}

/**
 * Filters, sorts and groups the drawer list. Pinned chats always lead in their own group,
 * like on desktop. Project groups follow the order of their most relevant session.
 */
fun arrangeSessions(
    items: List<SessionSummary>,
    view: SessionView,
    live: Map<String, String> = emptyMap(),
    projects: List<ProjectRef> = emptyList(),
    query: String = "",
    now: Calendar = Calendar.getInstance(),
): List<SessionGroup> {
    val projectOf = HashMap<String, ProjectRef>()
    projects.forEach { p -> p.sessionIds.forEach { projectOf.putIfAbsent(it, p) } }

    val visible = items.filter { s ->
        (query.isBlank() || s.displayTitle.contains(query, true) || (s.preview?.contains(query, true) ?: false)) &&
            (view.statuses.isEmpty() || statusOf(s, live) in view.statuses) &&
            (view.projects.isEmpty() || (projectOf[s.id]?.id ?: SessionView.NO_PROJECT) in view.projects)
    }
    val recency = compareByDescending<SessionSummary> { it.lastActive ?: 0.0 }
    val order: Comparator<SessionSummary> = when (view.ordering) {
        SessionOrdering.UPDATED -> recency
        SessionOrdering.CREATED -> compareByDescending<SessionSummary> { it.startedAt ?: it.lastActive ?: 0.0 }
        SessionOrdering.STATUS -> compareBy<SessionSummary> { statusOf(it, live).order }.then(recency)
        SessionOrdering.TOKENS -> compareByDescending<SessionSummary> { it.tokens }.then(recency)
        SessionOrdering.COST -> compareByDescending<SessionSummary> { it.costUsd }.then(recency)
    }
    val sorted = visible.sortedWith(order)
    val (pinned, rest) = sorted.partition { it.pinned }

    val groups: List<SessionGroup> = when (view.grouping) {
        SessionGrouping.DATE -> {
            val key: (SessionSummary) -> Double? = if (view.ordering == SessionOrdering.CREATED) {
                { it.startedAt ?: it.lastActive }
            } else {
                { it.lastActive }
            }
            rest.groupBy { dateGroupOf(key(it), now) }.toSortedMap(compareBy { dateOrder.indexOf(it) })
                .map { (k, v) -> SessionGroup(k, v) }
        }
        SessionGrouping.STATUS -> rest.groupBy { statusOf(it, live) }.toSortedMap(compareBy { it.order })
            .map { (k, v) -> SessionGroup(k.label, v) }
        // Groups keep the first-appearance order of the already-sorted list; Home goes last.
        SessionGrouping.PROJECT -> rest.groupBy { projectOf[it.id]?.takeIf { p -> p.id != SessionView.NO_PROJECT } }
            .entries.sortedBy { it.key == null }
            .map { (k, v) -> SessionGroup(k?.label ?: SessionView.NO_PROJECT_LABEL, v, "p-" + (k?.id ?: SessionView.NO_PROJECT)) }
    }
    return if (pinned.isEmpty()) groups else listOf(SessionGroup("Pinned", pinned)) + groups
}

/** Persists the drawer's [SessionView] (shared across paired PCs, like desktop's per-app prefs). */
class SessionViewStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("session_view", Context.MODE_PRIVATE))

    fun load(): SessionView = SessionView(
        grouping = enumOr(prefs.getString("grouping", null), SessionGrouping.DATE),
        ordering = enumOr(prefs.getString("ordering", null), SessionOrdering.UPDATED),
        statuses = prefs.getStringSet("statuses", emptySet()).orEmpty()
            .mapNotNull { n -> SessionStatus.entries.firstOrNull { it.name == n } }.toSet(),
        projects = prefs.getStringSet("projects", emptySet()).orEmpty().toSet(),
    )

    fun save(v: SessionView) {
        prefs.edit()
            .putString("grouping", v.grouping.name)
            .putString("ordering", v.ordering.name)
            .putStringSet("statuses", v.statuses.map { it.name }.toSet())
            .putStringSet("projects", v.projects.toSet())
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
