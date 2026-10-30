package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class BpNode(val id: String, val kind: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float,
                  val color: String = "default", val note: String = "")

data class BpEdge(val id: String, val from: String, val to: String, val label: String = "")

/** [limit]: the column's WIP cap (null = none); the header turns red when it holds more. */
data class BpColumn(val id: String, val name: String, val color: String = "default", val limit: Int? = null) {
    fun over(count: Int) = limit != null && count > limit
}

/** Text limits, the same as the bridge's (it refuses longer text instead of cutting it). */
object BpLimits {
    const val TITLE = 600
    const val LABEL = 400
    const val COLUMN = 120
}

/**
 * Board settings, shared with Hermes. [newCards] "top"/"bottom": where new and moved-in cards land.
 * [agingDays] 0 = off. A null field is "not chosen": a board then follows the global default, and the
 * global default follows the built-in one.
 */
data class BpSettings(val newCards: String? = null, val agingDays: Int? = null, val stickyAdd: Boolean? = null) {
    /** This over [fallback], field by field, then the built-in values. */
    fun over(fallback: BpSettings) = BpSettings(newCards ?: fallback.newCards ?: NEW_CARDS[0], agingDays ?: fallback.agingDays ?: AGING_DAYS[0],
        stickyAdd ?: fallback.stickyAdd ?: false)
    fun toJson(): JSONObject = JSONObject().put("new_cards", newCards ?: "default").put("aging_days", agingDays ?: "default")
        .put("sticky_add", stickyAdd ?: "default")

    companion object {
        val NEW_CARDS = listOf("bottom", "top")
        val AGING_DAYS = listOf(0, 3, 7, 14, 30)
        fun parse(o: JSONObject?) = BpSettings(o?.optString("new_cards")?.takeIf { it in NEW_CARDS },
            o?.takeIf { it.has("aging_days") }?.optInt("aging_days", -1)?.takeIf { it in AGING_DAYS },
            (o?.opt("sticky_add") as? Boolean))
    }
}

/** How faded a card untouched since [updatedSec] looks: 1 = fresh, down to 0.45 at 4x [agingDays]. */
fun agingAlpha(updatedSec: Double, nowSec: Double, agingDays: Int): Float {
    if (agingDays <= 0 || updatedSec <= 0.0) return 1f
    val days = (nowSec - updatedSec) / 86_400.0
    return when {
        days < agingDays -> 1f
        days < agingDays * 2 -> 0.8f
        days < agingDays * 4 -> 0.62f
        else -> 0.45f
    }
}

data class BpCheck(val text: String, val done: Boolean)

data class BpCard(
    val id: String, val column: String, val title: String, val body: String = "", val tags: List<String> = emptyList(),
    val checklist: List<BpCheck> = emptyList(), val priority: String? = null, val due: String? = null,
    val assignee: String? = null, val node: String? = null, val color: String = "default", val order: Double = 0.0,
    val updated: Double = 0.0,
    /** Who is actively working on it right now (e.g. "Hermes"), or null. */
    val agent: String? = null,
    val comments: List<BpComment> = emptyList(),
    /** The Hermes chat last working on or commenting on it (null: unknown, e.g. an older bridge). */
    val session: String? = null,
    /** Files attached to the description (paths on the PC). */
    val files: List<String> = emptyList(),
) {
    val progress get() = checklist.count { it.done } to checklist.size
    val openAsks get() = comments.filter { it.needsYou }
}

/** A note on a card. `question`/`review` wait for the user until resolved (the card is highlighted). */
data class BpComment(val id: String, val at: Double, val by: String, val kind: String, val text: String,
                     val resolved: Boolean = false, val files: List<String> = emptyList(),
                     /** The Hermes chat that wrote it (null: the phone, or an older bridge). */
                     val session: String? = null) {
    val needsYou get() = (kind == "question" || kind == "review") && !resolved
}

/** The chat to tell that [m] on [card] was resolved on the phone: the one that asked, else the card's. */
fun resolvedPingChat(card: BpCard, m: BpComment): String? = m.session ?: card.session

/** What Hermes is told when the user resolves its question/review from the phone. */
fun resolvedPingText(bp: Blueprint, card: BpCard, m: BpComment): String {
    val what = if (m.kind == "review") "marked your review as reviewed" else "marked your question as answered"
    val quote = m.text.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim().let { if (it.length > 140) it.take(140) + "…" else it }
    return "I $what on the card \"${card.title}\" in the ${bp.name} blueprint" + (if (quote.isNotEmpty()) " (\"$quote\")" else "") +
        ". Read its comments (blueprint md) and carry on: finish the card if nothing else is open."
}

data class BpLog(val at: Double, val by: String, val text: String)

data class Blueprint(
    val projectId: String, val name: String, val path: String?, val version: Int, val control: String,
    val nodes: List<BpNode>, val edges: List<BpEdge>, val columns: List<BpColumn>, val cards: List<BpCard>, val log: List<BpLog>,
    /** The user's standing rules for any agent on this project (sent with every ask). */
    val instructions: String = "",
    /** This board's own choices (null fields follow [defaults]). */
    val settings: BpSettings = BpSettings(),
    /** The global board settings on this PC. */
    val defaults: BpSettings = BpSettings(),
    /** Global agent instructions on this PC, used when [instructions] is empty (null: older bridge). */
    val globalInstructions: String? = null,
) {
    /** What applies on this board. */
    val effective: BpSettings get() = settings.over(defaults)
    /** The rules Hermes follows here: this board's own, else the global ones. */
    val effectiveInstructions: String get() = instructions.trim().ifEmpty { globalInstructions.orEmpty().trim() }
    val needsYou get() = cards.filter { it.openAsks.isNotEmpty() }
    val hermesControl get() = control == "hermes"
    fun cardsIn(column: String) = cards.filter { it.column == column }.sortedBy { it.order }
    val tags get() = cards.flatMap { it.tags }.distinct().sorted()

    companion object {
        fun parse(o: JSONObject): Blueprint {
            val p = o.optJSONObject("project") ?: JSONObject()
            val d = o.optJSONObject("diagram") ?: JSONObject()
            val b = o.optJSONObject("board") ?: JSONObject()
            fun f(j: JSONObject, k: String) = j.optDouble(k, 0.0).toFloat()
            return Blueprint(
                p.optString("id"), p.optString("name"), p.str("path"), o.optInt("version"), o.optString("control", "shared"),
                d.optJSONArray("nodes").objects().map {
                    BpNode(it.optString("id"), it.optString("kind", "feature"), it.optString("label"), f(it, "x"), f(it, "y"),
                        f(it, "w").takeIf { w -> w > 0 } ?: 180f, f(it, "h").takeIf { h -> h > 0 } ?: 64f, it.optString("color", "default"), it.optString("note"))
                },
                d.optJSONArray("edges").objects().map { BpEdge(it.optString("id"), it.optString("from"), it.optString("to"), it.optString("label")) },
                b.optJSONArray("columns").objects().map {
                    BpColumn(it.optString("id"), it.optString("name"), it.optString("color", "default"), it.optInt("limit", 0).takeIf { n -> n > 0 })
                },
                b.optJSONArray("cards").objects().map {
                    BpCard(it.optString("id"), it.optString("column"), it.optString("title"), it.optString("body"),
                        it.optJSONArray("tags").strings(), it.optJSONArray("checklist").objects().map { c -> BpCheck(c.optString("text"), c.optBoolean("done")) },
                        it.str("priority"), it.str("due"), it.str("assignee"), it.str("node"), it.optString("color", "default"),
                        it.optDouble("order", 0.0), it.optDouble("updated", 0.0), it.str("agent"),
                        it.optJSONArray("comments").objects().map { m ->
                            BpComment(m.optString("id"), m.optDouble("at", 0.0), m.optString("by"), m.optString("kind", "comment"),
                                m.optString("text"), m.optBoolean("resolved"), m.optJSONArray("files").strings(), m.str("session"))
                        }, it.str("session"), it.optJSONArray("files").strings())
                },
                o.optJSONArray("log").objects().map { BpLog(it.optDouble("at", 0.0), it.optString("by"), it.optString("text")) },
                o.optString("instructions"),
                BpSettings.parse(o.optJSONObject("settings")),
                BpSettings.parse(o.optJSONObject("defaults")),
                if (o.has("default_instructions")) o.optString("default_instructions") else null,
            )
        }
    }
}

/** One change, as the bridge applies it (`/v1/blueprints/{id}/ops`). The bridge merges ops into the newest copy. */
object BpOp {
    fun of(op: String, vararg kv: Pair<String, Any?>) = JSONObject().put("op", op).apply {
        kv.forEach { (k, v) -> put(k, when (v) { null -> JSONObject.NULL; is List<*> -> JSONArray(v); else -> v }) }
    }
    fun checklist(items: List<BpCheck>) = JSONArray(items.map { JSONObject().put("text", it.text).put("done", it.done) })

    /** Apply [op] locally so the screen updates before the bridge answers (the answer replaces it). */
    fun preview(bp: Blueprint, op: JSONObject): Blueprint = when (op.optString("op")) {
        "node.update" -> bp.copy(nodes = bp.nodes.map { n ->
            if (n.id != op.optString("id")) n else n.copy(
                x = if (op.has("x")) op.optDouble("x").toFloat() else n.x, y = if (op.has("y")) op.optDouble("y").toFloat() else n.y,
                label = if (op.has("label")) op.optString("label") else n.label, kind = if (op.has("kind")) op.optString("kind") else n.kind,
                note = if (op.has("note")) op.optString("note") else n.note, color = if (op.has("color")) op.optString("color") else n.color)
        })
        "node.remove" -> op.optString("id").let { id -> bp.copy(nodes = bp.nodes.filterNot { it.id == id }, edges = bp.edges.filterNot { it.from == id || it.to == id }) }
        "edge.remove" -> bp.copy(edges = bp.edges.filterNot { it.id == op.optString("id") })
        "card.move" -> {
            val id = op.optString("id"); val col = op.optString("column")
            val others = bp.cardsIn(col).filterNot { it.id == id }
            val i = op.optInt("index", others.size).coerceIn(0, others.size)
            val order = when {
                others.isEmpty() -> 1.0
                i >= others.size -> others.last().order + 1
                i == 0 -> others.first().order - 1
                else -> (others[i - 1].order + others[i].order) / 2
            }
            bp.copy(cards = bp.cards.map { if (it.id == id) it.copy(column = col, order = order) else it })
        }
        "card.remove" -> bp.copy(cards = bp.cards.filterNot { it.id == op.optString("id") })
        else -> bp
    }
}

data class BlueprintSummary(val projectId: String, val name: String, val cards: Int, val nodes: Int, val control: String,
                            val needsYou: Int = 0, val working: Int = 0, val latest: BpLatestComment? = null)
data class BlueprintTrash(val id: String, val name: String, val removedAt: Double, val cards: Int)

data class BlueprintState(
    /** Projects that have a blueprint (by Hermes project id). */
    val have: Map<String, BlueprintSummary> = emptyMap(),
    val trash: List<BlueprintTrash> = emptyList(),
    val open: Blueprint? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
    /** Global board settings on this PC (null: not read yet, or an older bridge). */
    val defaults: BpSettings? = null,
    /** Global agent instructions on this PC (null: not read yet, or an older bridge). */
    val defaultInstructions: String? = null,
)

/**
 * Blueprints (flow diagram + board per project). Every edit is sent as an op and the bridge answers
 * with the merged document, so Hermes' edits made meanwhile are never lost; while a blueprint is open
 * it is re-read every few seconds (`since=version`, a no-op when nothing changed).
 */
class BlueprintRepo(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
    private val cacheDir: () -> java.io.File = { java.io.File(System.getProperty("java.io.tmpdir") ?: ".") },
    /** Read state of Hermes' comments (null in tests: nothing is tracked or notified). */
    val seen: BlueprintSeen? = null,
    /** Alert about a new Hermes comment: (project id, project name, comment). */
    private val notify: (String, String, BpLatestComment) -> Unit = { _, _, _ -> },
) {
    private val _state = MutableStateFlow(BlueprintState())
    val state: StateFlow<BlueprintState> = _state.asStateFlow()
    private var poll: Job? = null
    /** Ops in flight: a poll must not replace the optimistic copy with an older one. */
    private var inFlight = 0

    fun clear() { stop(); _state.value = BlueprintState() }

    fun refreshList() {
        val c = client() ?: return
        scope.launch {
            try {
                val o = c.blueprints()
                _state.update { st ->
                    st.copy(have = o.optJSONArray("blueprints").objects().associate { b ->
                        val p = b.optJSONObject("project") ?: JSONObject()
                        p.optString("id") to BlueprintSummary(p.optString("id"), p.optString("name"), b.optInt("cards"), b.optInt("nodes"), b.optString("control"),
                            b.optInt("needs_you"), b.optInt("working"), b.optJSONObject("latest_comment")?.let { m ->
                                BpLatestComment(m.optString("card"), m.optString("title"), m.optDouble("at", 0.0), m.optString("by"),
                                    m.optString("kind", "comment"), m.optString("text"))
                            })
                    }, trash = o.optJSONArray("trash").objects().map {
                        BlueprintTrash(it.optString("id"), it.optJSONObject("project")?.optString("name") ?: it.optString("id"), it.optDouble("removed_at", 0.0), it.optInt("cards"))
                    }, unsupported = false, defaults = if (o.has("defaults")) BpSettings.parse(o.optJSONObject("defaults")) else null,
                        defaultInstructions = if (o.has("default_instructions")) o.optString("default_instructions") else null)
                }
                checkNewComments()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is BridgeException && t.httpCode == 404) _state.update { it.copy(unsupported = true) }
            }
        }
    }

    /** Change the global board settings (every board without its own choice follows them). */
    fun setDefaults(st: BpSettings) {
        val c = client() ?: return
        scope.launch {
            try {
                val r = c.blueprintDefaults(st.toJson())
                val d = BpSettings.parse(r.optJSONObject("defaults"))
                _state.update { s -> s.copy(defaults = d, open = s.open?.copy(defaults = d)) }
                say("Saved for every board")
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    /** Change the global agent instructions (every board without its own follows them). */
    fun setDefaultInstructions(text: String) {
        val c = client() ?: return
        scope.launch {
            try {
                val r = c.blueprintDefaults(JSONObject().put("instructions", text))
                val t = r.optString("default_instructions")
                _state.update { s -> s.copy(defaultInstructions = t, open = s.open?.copy(globalInstructions = t)) }
                say("Saved for every board")
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    /** Bumped when a card is marked seen, so badges recompose. */
    private val _seenTick = MutableStateFlow(0)
    val seenTick: StateFlow<Int> = _seenTick.asStateFlow()

    fun markSeen(projectId: String, card: BpCard) {
        val s = seen ?: return
        val before = s.seenAt(projectId, card.id)
        s.markSeen(projectId, card)
        if (s.seenAt(projectId, card.id) != before) _seenTick.update { it + 1 }
    }

    /** Alert once per new Hermes comment, unless that blueprint is open on screen (the board shows it). */
    private fun checkNewComments() {
        val s = seen ?: return
        _state.value.have.values.forEach { b ->
            val latest = b.latest
            val notifiedAt = s.notifiedAt(b.projectId)
            if (notifiedAt == null) { s.setNotified(b.projectId, latest?.at ?: 0.0); return@forEach }  // first look: baseline
            if (latest != null && shouldNotify(latest, notifiedAt, s.seenAt(b.projectId, latest.card))) {
                s.setNotified(b.projectId, latest.at)
                if (_state.value.open?.projectId != b.projectId || poll == null) notify(b.projectId, b.name, latest)
            }
        }
    }

    /** Re-read the list every [periodMs] while the app process lives, so new comments badge and alert. */
    fun watch(periodMs: Long = 30_000L) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch { while (true) { delay(periodMs); if (client() != null) refreshList() } }
    }
    private var watchJob: Job? = null

    fun open(projectId: String) {
        stop()
        _state.update { it.copy(open = it.open?.takeIf { b -> b.projectId == projectId }, loading = true, error = null) }
        poll = scope.launch {
            while (true) { load(projectId); delay(POLL_MS) }
        }
    }

    fun stop() { poll?.cancel(); poll = null }

    private suspend fun load(projectId: String) {
        val c = client() ?: return
        try {
            val since = _state.value.open?.takeIf { it.projectId == projectId }?.version ?: 0
            val o = c.blueprint(projectId, since)
            if (o.optBoolean("unchanged") || inFlight > 0) { _state.update { it.copy(loading = false) }; return }
            _state.update { it.copy(open = Blueprint.parse(o), loading = false, error = null) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { it.copy(loading = false, error = describe(t)) }
        }
    }

    /** Create a blueprint for a Hermes project (long-press project > Add blueprint). */
    fun create(projectId: String, name: String, path: String?, onDone: () -> Unit = {}) {
        val c = client() ?: return
        scope.launch {
            try {
                c.createBlueprint(projectId, JSONObject().put("name", name).put("path", path ?: JSONObject.NULL))
                say("Blueprint added to $name"); refreshList(); onDone()
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    fun trash(projectId: String) {
        val c = client() ?: return
        scope.launch {
            try { c.trashBlueprint(projectId); say("Blueprint moved to the trash"); if (_state.value.open?.projectId == projectId) { stop(); _state.update { it.copy(open = null) } }; refreshList() }
            catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    fun restore(trashId: String) {
        val c = client() ?: return
        scope.launch {
            try { c.restoreBlueprint(trashId); say("Blueprint restored"); refreshList() }
            catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    fun purge(trashId: String) {
        val c = client() ?: return
        scope.launch {
            try { c.purgeBlueprint(trashId); say("Deleted for good"); refreshList() }
            catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    /** Send [ops]; shows the change at once, then the merged document from the bridge. [onApplied] gets the applied ops (new ids). */
    fun apply(vararg ops: JSONObject, onApplied: (List<JSONObject>) -> Unit = {}) {
        val bp = _state.value.open ?: return
        val c = client() ?: return
        _state.update { st -> st.copy(open = ops.fold(bp) { acc, op -> BpOp.preview(acc, op) }) }
        inFlight++
        scope.launch {
            var failed = false
            try {
                val r = c.blueprintOps(bp.projectId, JSONArray(ops.toList()))
                val doc = Blueprint.parse(r.getJSONObject("doc"))
                _state.update { st -> if (st.open?.projectId == doc.projectId) st.copy(open = doc) else st }
                onApplied(r.optJSONArray("applied").objects())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(describe(t)); failed = true
            } finally { inFlight = (inFlight - 1).coerceAtLeast(0) }
            if (failed) load(bp.projectId)  // put back what the bridge really has
        }
    }

    /** Send a file from the phone to the PC for this blueprint; answers its PC path (for a card's or comment's `files`). */
    suspend fun upload(bytes: ByteArray, name: String): String {
        val c = client() ?: throw java.io.IOException("Not connected to the PC")
        val pid = _state.value.open?.projectId ?: throw java.io.IOException("No blueprint open")
        return c.blueprintUpload(pid, name, bytes).optString("path").ifEmpty { throw java.io.IOException("The PC did not store $name") }
    }

    /** A file a card comment attaches, downloaded into the app's cache (reused when already there). */
    suspend fun file(path: String): java.io.File {
        val c = client() ?: throw java.io.IOException("Not connected to the PC")
        val pid = _state.value.open?.projectId ?: throw java.io.IOException("No blueprint open")
        val name = fileName(path).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }
        val f = java.io.File(cacheDir(), "chat-files/bp-${(pid + path).hashCode().toUInt()}/$name")
        if (!f.isFile) c.blueprintFile(pid, path, f)
        return f
    }

    suspend fun fileInfo(path: String): FileInfo {
        val c = client() ?: throw java.io.IOException("Not connected to the PC")
        val pid = _state.value.open?.projectId ?: throw java.io.IOException("No blueprint open")
        return parseFileInfo(c.blueprintFileInfo(pid, path), path)
    }

    companion object { const val POLL_MS = 3_000L }
}
