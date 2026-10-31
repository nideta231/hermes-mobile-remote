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
import org.json.JSONObject

/** One memory in the PC's Memory Library (Hermes' own memory provider on a private Postgres). */
data class Memory(
    val id: Long,
    val kind: String,
    val severity: Int,
    val scope: String,
    val text: String,
    val keywords: String = "",
    val status: String = "active",
    val review: Boolean = false,
    val hits: Int = 0,
    val repeats: Int = 0,
    val source: String = "",
    val sourceSession: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Test recall only: how it scored (full-text, trigram, scope / severity / freshness weights, total). */
    val score: Double? = null,
    val ft: Double? = null,
    val sim: Double? = null,
    val scopeW: Double? = null,
    val sevW: Double? = null,
    val fresh: Double? = null,
    val links: List<MemoryLink> = emptyList(),
    val history: List<MemoryEdit> = emptyList(),
) {
    companion object {
        fun parse(o: JSONObject) = Memory(
            id = o.optLong("id"), kind = o.optString("kind", "fact"), severity = o.optInt("severity", 2),
            scope = o.optString("scope", "global"), text = o.optString("text"), keywords = o.optString("keywords"),
            status = o.optString("status", "active"), review = o.optBoolean("review"), hits = o.optInt("hits"),
            repeats = o.optInt("repeats"), source = o.optString("source"), sourceSession = o.str("source_session"),
            createdAt = o.str("created_at"), updatedAt = o.str("updated_at"),
            score = o.dbl("score"), ft = o.dbl("ft"), sim = o.dbl("sim"), scopeW = o.dbl("scope_w"),
            sevW = o.dbl("sev_w"), fresh = o.dbl("fresh"),
            links = o.optJSONArray("links").objects().map { MemoryLink(it.optLong("src"), it.optLong("dst"), it.optString("rel"),
                it.optString("other_text"), it.optString("other_kind")) },
            history = o.optJSONArray("history").objects().map { MemoryEdit(it.optString("at"), it.optString("by"), it.optString("action")) },
        )
    }
}

data class MemoryLink(val src: Long, val dst: Long, val rel: String, val otherText: String, val otherKind: String)
data class MemoryEdit(val at: String, val by: String, val action: String)

data class DoctorItem(val id: String, val status: String, val text: String, val fix: String?)

data class LibraryPlan(val supported: Boolean, val reason: String?, val downloadBytes: Long, val diskBytes: Long,
                       val freeBytes: Long, val enoughDisk: Boolean, val location: String, val remove: String, val network: String)

data class LibraryStatus(
    val supported: Boolean = false,
    val reason: String? = null,
    val installed: Boolean = false,
    val running: Boolean = false,
    val enabled: Boolean = false,
    val profile: String = "default",
    /** The memory provider the profile uses now ("built-in" when none). */
    val provider: String = "built-in",
    /** Another external provider the user set up; enabling keeps it unless they choose to switch. */
    val customProvider: String? = null,
    val pluginConflict: Boolean = false,
    val builtinEntries: Int = 0,
    val plan: LibraryPlan? = null,
    val job: JSONObject? = null,
    val smartRecall: Boolean = false,
    val sessionReview: Boolean = true,
    /** Memory model: blank = same as the chat (auto). */
    val model: String = "",
    val modelProvider: String = "auto",
    val admin: Boolean = true,
    val benchmarkMs: Double? = null,
) {
    val installing get() = job?.optString("state") == "running"

    companion object {
        fun parse(o: JSONObject): LibraryStatus {
            val s = o.optJSONObject("settings") ?: JSONObject()
            val p = o.optJSONObject("plan")
            val built = o.optJSONObject("builtin_memory")
            return LibraryStatus(
                supported = o.optBoolean("supported"), reason = o.str("reason"), installed = o.optBoolean("installed"),
                running = o.optBoolean("running"), enabled = o.optBoolean("enabled"), profile = o.optString("profile", "default"),
                provider = o.optString("provider", "built-in"), customProvider = o.str("custom_provider"),
                pluginConflict = o.optBoolean("plugin_dir_conflict"),
                builtinEntries = listOf("MEMORY.md", "USER.md").sumOf { built?.optJSONObject(it)?.optInt("entries") ?: 0 },
                plan = p?.let { LibraryPlan(it.optBoolean("supported"), it.str("reason"), it.optLong("download_bytes"),
                    it.optLong("disk_bytes"), it.optLong("disk_free_bytes"), it.optBoolean("enough_disk"),
                    it.optString("location"), it.optString("remove"), it.optString("network")) },
                job = o.optJSONObject("job")?.takeIf { it.length() > 0 },
                smartRecall = s.optBoolean("smart_recall", false), sessionReview = s.optBoolean("session_review", true),
                model = s.optString("model"), modelProvider = s.optString("provider", "auto"),
                admin = o.optBoolean("admin", true), benchmarkMs = o.optJSONObject("benchmark")?.dbl("query_ms"),
            )
        }
    }
}

data class RecallResult(val pinned: List<Memory>, val relevant: List<Memory>, val injected: String) {
    companion object {
        fun parse(o: JSONObject) = RecallResult(o.optJSONArray("pinned").objects().map(Memory::parse),
            o.optJSONArray("relevant").objects().map(Memory::parse), o.optString("injected"))
    }
}

data class LibraryStats(val mostRepeated: List<Memory>, val unused30d: Int, val needsReview: Int,
                        val byKind: Map<String, Int>, val scopes: List<Pair<String, Int>>) {
    companion object {
        fun parse(o: JSONObject) = LibraryStats(
            o.optJSONArray("most_repeated").objects().map(Memory::parse), o.optInt("unused_30d"), o.optInt("needs_review"),
            o.optJSONArray("by_kind").objects().filter { it.optString("status") == "active" }
                .associate { it.optString("kind") to it.optInt("n") },
            o.optJSONArray("scopes").objects().map { it.optString("scope") to it.optInt("n") })
    }
}

data class LibraryProject(val id: String, val name: String) { val scope get() = "project:$id" }

data class LibraryFilter(val kind: String = "", val scope: String = "", val status: String = "active", val query: String = "",
                         val minSeverity: Int = 1, val sort: String = "severity", val review: Boolean? = null)

data class LibraryState(
    val status: LibraryStatus? = null,
    val memories: List<Memory> = emptyList(),
    val total: Int = 0,
    val filter: LibraryFilter = LibraryFilter(),
    val stats: LibraryStats? = null,
    val projects: List<LibraryProject> = emptyList(),
    val doctor: List<DoctorItem> = emptyList(),
    val recall: RecallResult? = null,
    val loading: Boolean = false,
    /** An action in flight: "install", "enable", "disable", "doctor", a fix name, "recall". */
    val busy: String? = null,
    val error: String? = null,
    /** The bridge has no Library (older, or `library = false`). */
    val unsupported: Boolean = false,
)

/** Pure helpers the page and the tests share. */
object LibraryText {
    val KINDS = listOf("mistake", "rule", "preference", "workflow", "fact", "decision")

    fun severityLabel(s: Int) = when (s) { 1 -> "Note"; 2 -> "Minor"; 3 -> "Major"; 4 -> "Critical"; else -> "Fatal" }

    /** "project:p_1" -> the project's name; "client:Acme" -> "Acme (client)"; "global" -> "Everywhere". */
    fun scopeLabel(scope: String, projects: List<LibraryProject>): String = when {
        scope == "global" -> "Everywhere"
        scope.startsWith("project:") -> projects.firstOrNull { it.id == scope.removePrefix("project:") }?.name ?: scope.removePrefix("project:")
        scope.startsWith("client:") -> scope.removePrefix("client:") + " (client)"
        else -> scope
    }

    /** What the bridge accepts as a scope (mirrors library_store.SCOPE_RE). */
    fun validScope(scope: String): Boolean =
        scope == "global" || Regex("^project:[A-Za-z0-9_.-]{1,64}$").matches(scope) || Regex("^client:[A-Za-z0-9 _.&'-]{1,64}$").matches(scope)

    fun mb(bytes: Long) = if (bytes >= 1_000_000) "${(bytes + 500_000) / 1_000_000} MB" else "${(bytes + 500) / 1000} KB"

    /** One line for the install progress. */
    fun jobLine(job: JSONObject?): String? {
        job ?: return null
        return when (job.optString("state")) {
            "failed" -> "Install failed: " + job.optString("error")
            "done" -> "Installed"
            else -> when (job.optString("step")) {
                "download" -> {
                    val done = job.optLong("done"); val total = job.optLong("total")
                    if (total > 0) "Downloading ${mb(done)} of ${mb(total)} (${done * 100 / total}%)" else "Downloading…"
                }
                "unpack" -> "Unpacking the database…"
                "database" -> "Creating the memory database…"
                "benchmark" -> "Measuring speed…"
                else -> "Starting…"
            }
        }
    }

    /** The Test recall score line: why this memory ranked where it did. */
    fun scoreLine(m: Memory): String? {
        val s = m.score ?: return null
        fun f(x: Double?) = x?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
        return "score ${f(s)} = (words ${f(m.ft)} × 2 + similar ${f(m.sim)}) × scope ${f(m.scopeW)} × severity ${f(m.sevW)} × fresh ${f(m.fresh)}"
    }
}

/**
 * The Memory Library page and its setup panel. Reads are per Hermes profile ([profile]); every
 * action reloads what it changed. While the page is open the list is re-read every few seconds, so
 * memories Hermes saves in a chat show up on their own.
 */
class LibraryRepo(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val profile: () -> String?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var poll: Job? = null
    private var jobPoll: Job? = null

    fun clear() { stop(); jobPoll?.cancel(); _state.value = LibraryState() }

    fun watch() {
        if (poll?.isActive == true) return
        poll = scope.launch { refreshStatus(); while (true) { if (_state.value.status?.enabled == true || _state.value.status?.installed == true) loadList(); delay(POLL_MS) } }
        scope.launch { loadProjects(); loadStats() }
    }

    fun stop() { poll?.cancel(); poll = null }

    private fun failed(t: Throwable) {
        if (t is CancellationException) throw t
        val old = t is BridgeException && t.httpCode == 404 && t.code in setOf("http_404", "library_off", "not_found")
        _state.update { it.copy(loading = false, busy = null, unsupported = old, error = if (old) null else describe(t)) }
    }

    private suspend fun guarded(busy: String? = null, block: suspend (BridgeClient) -> Unit) {
        val c = client() ?: return
        if (busy != null) _state.update { it.copy(busy = busy, error = null) }
        try { block(c); _state.update { it.copy(busy = null) } } catch (t: Throwable) { failed(t) }
    }

    fun refreshStatus() { scope.launch { guarded { c -> applyStatus(LibraryStatus.parse(c.library(profile()))) } } }

    private fun applyStatus(st: LibraryStatus) {
        _state.update { it.copy(status = st, unsupported = false, error = null) }
        if (st.installing) followJob()
    }

    fun setFilter(f: (LibraryFilter) -> LibraryFilter) { _state.update { it.copy(filter = f(it.filter)) }; scope.launch { loadList() } }

    suspend fun loadList() {
        val st = _state.value.status ?: return
        if (!st.installed) return
        guarded { c ->
            val f = _state.value.filter
            val o = c.libraryMemories(f, profile())
            _state.update { it.copy(memories = o.optJSONArray("items").objects().map(Memory::parse), total = o.optInt("total"), loading = false) }
        }
    }

    fun loadStats() { scope.launch { if (_state.value.status?.installed == true) guarded { c -> val s = LibraryStats.parse(c.libraryStats(profile())); _state.update { it.copy(stats = s) } } } }

    private suspend fun loadProjects() = guarded { c ->
        val ps = c.libraryScopes().optJSONArray("projects").objects().map { LibraryProject(it.optString("id"), it.optString("name")) }
        _state.update { it.copy(projects = ps) }
    }

    // ------------------------------------------------------------ setup (PC bridge admin)

    fun install() = scope.launch {
        guarded("install") { c -> c.libraryInstall(); followJob() }
    }

    private fun followJob() {
        if (jobPoll?.isActive == true) return
        jobPoll = scope.launch {
            while (true) {
                val c = client() ?: break
                val job = try { c.libraryJob().optJSONObject("job") } catch (t: Throwable) { if (t is CancellationException) throw t; null }
                _state.update { s -> s.copy(status = s.status?.copy(job = job)) }
                val st = job?.optString("state")
                if (st == "done" || st == "failed") {
                    if (st == "failed") say(LibraryText.jobLine(job) ?: "Install failed")
                    refreshStatus(); break
                }
                delay(700)
            }
        }
    }

    fun enable(switchProvider: Boolean, importMemory: Boolean) = scope.launch {
        guarded("enable") { c ->
            val r = c.libraryEnable(switchProvider, importMemory, profile())
            val imported = r.optJSONObject("imported")?.optInt("added") ?: 0
            say("Memory Library is on for new chats" + if (imported > 0) " ($imported memories copied in for review)" else "")
            applyStatus(LibraryStatus.parse(c.library(profile()))); loadList(); loadStats()
        }
    }

    fun disable() = scope.launch {
        guarded("disable") { c ->
            val r = c.libraryDisable(profile())
            say("Memory Library is off; memory is back to ${r.optString("provider", "built-in")}. Your memories are kept.")
            applyStatus(LibraryStatus.parse(c.library(profile())))
        }
    }

    fun doctor() = scope.launch {
        guarded("doctor") { c -> applyDoctor(c.libraryDoctor(profile())) }
    }

    private fun applyDoctor(o: JSONObject) {
        val items = o.optJSONArray("checks").objects().map { DoctorItem(it.optString("id"), it.optString("status"), it.optString("text"), it.str("fix")) }
        _state.update { it.copy(doctor = items) }
    }

    fun fix(action: String) = scope.launch {
        guarded(action) { c ->
            val r = c.libraryFix(action, profile())
            r.optJSONObject("doctor")?.let(::applyDoctor)
            applyStatus(LibraryStatus.parse(c.library(profile()))); loadList()
        }
    }

    fun setSettings(smartRecall: Boolean? = null, sessionReview: Boolean? = null, model: String? = null, provider: String? = null) = scope.launch {
        guarded("settings") { c ->
            val body = JSONObject()
            smartRecall?.let { body.put("smart_recall", it) }; sessionReview?.let { body.put("session_review", it) }
            model?.let { body.put("model", it) }; provider?.let { body.put("provider", it) }
            val s = c.librarySettings(body, profile())
            _state.update { st -> st.copy(status = st.status?.copy(smartRecall = s.optBoolean("smart_recall"), sessionReview = s.optBoolean("session_review", true),
                model = s.optString("model"), modelProvider = s.optString("provider", "auto"))) }
        }
    }

    // ------------------------------------------------------------ memories

    fun get(id: Long, done: (Memory) -> Unit) = scope.launch { guarded { c -> done(Memory.parse(c.libraryMemory(id, profile()))) } }

    fun add(text: String, kind: String, severity: Int, scope: String, keywords: String, done: () -> Unit = {}) = this.scope.launch {
        guarded("save") { c ->
            val r = c.libraryAdd(JSONObject().put("text", text).put("kind", kind).put("severity", severity).put("scope", scope).put("keywords", keywords), profile())
            say(if (r.optBoolean("duplicate")) "Already in the Library as #${r.optLong("id")}" else "Saved #${r.optLong("id")}")
            loadList(); done()
        }
    }

    fun update(id: Long, fields: JSONObject, done: (Memory) -> Unit = {}) = scope.launch {
        guarded("save") { c -> val m = Memory.parse(c.libraryUpdate(id, fields, profile())); loadList(); done(m) }
    }

    fun bump(id: Long, done: (Memory) -> Unit = {}) = scope.launch {
        guarded { c -> val m = Memory.parse(c.libraryBump(id, profile())); say("Counted again: repeated ${m.repeats}×, severity ${m.severity}"); loadList(); done(m) }
    }

    fun delete(id: Long) = scope.launch { guarded { c -> c.libraryDelete(id, profile()); say("Memory #$id deleted"); loadList() } }

    fun merge(keep: Long, drop: Long, done: (Memory) -> Unit = {}) = scope.launch {
        guarded("merge") { c -> val m = Memory.parse(c.libraryMerge(keep, drop, profile())); say("Merged #$drop into #$keep"); loadList(); done(m) }
    }

    fun recall(query: String, scopes: List<String>) = scope.launch {
        guarded("recall") { c -> val r = RecallResult.parse(c.libraryRecall(query, scopes, profile())); _state.update { it.copy(recall = r) } }
    }

    fun clearRecall() = _state.update { it.copy(recall = null) }

    suspend fun graph(scopeFilter: String, kinds: List<String>, minSeverity: Int, chats: Boolean): JSONObject? {
        val c = client() ?: return null
        return try { c.libraryGraph(scopeFilter, kinds, minSeverity, chats, profile()) } catch (t: Throwable) { failed(t); null }
    }

    companion object { const val POLL_MS = 5_000L }
}

/** The knowledge map from `/v1/library/graph`, as the force layout's graph. */
object KnowledgeGraph {
    fun build(o: JSONObject): ChatGraph {
        val raw = o.optJSONArray("nodes").objects()
        val index = HashMap<String, Int>()
        val nodes = raw.mapIndexed { i, n ->
            val id = n.optString("id"); index[id] = i
            val kind = when (n.optString("type")) {
                "memory" -> GraphKind.MEMORY; "project" -> GraphKind.PROJECT; "client" -> GraphKind.CLIENT
                "chat" -> GraphKind.CHAT; else -> GraphKind.GLOBAL
            }
            val ref = when (kind) {
                GraphKind.MEMORY -> n.optLong("memory").toString()
                GraphKind.CHAT -> n.optString("session")
                else -> n.optString("scope")
            }
            GraphNode(id, kind, n.optString("label"), ref, tag = n.optString("kind"), weight = n.optInt("severity", n.optInt("count")))
        }
        val edges = o.optJSONArray("edges").objects().mapNotNull { e ->
            val a = index[e.optString("from")] ?: return@mapNotNull null
            val b = index[e.optString("to")] ?: return@mapNotNull null
            GraphEdge(a, b)
        }.distinct()
        return ChatGraph(nodes, edges)
    }
}
