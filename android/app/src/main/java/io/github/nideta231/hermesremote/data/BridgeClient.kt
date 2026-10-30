package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The bridge's small REST surface (identity, status, profiles, the sidebar list, approval mode) plus the
 * factory for the live [Gateway] socket. Everything conversational goes over the socket.
 */
class BridgeClient(
    private val pairing: Pairing,
    baseClient: OkHttpClient = SharedHttp.client,
    // Tests only: lets a loopback TLS server stand in for the PC. Production always uses the
    // real rule (tailnet, or LAN over pinned HTTPS).
    private val hostGuard: (host: String, https: Boolean) -> Boolean =
        { host, https -> Transport.allowsToken(host, https, pairing.pin != null) },
) : SessionsSource, CronSource, SessionSearchSource {
    private val base = pairing.url.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Hermes replies to the bridge's own pings; this one keeps NATs and the phone's radio from
        // silently dropping an idle socket, and notices a dead link within ~40 s.
        .pingInterval(20, TimeUnit.SECONDS)
        .apply {
            // LAN: HTTPS pinned to the bridge's own certificate (hostname is irrelevant; the pin
            // is the identity). Tailscale: plain HTTP inside WireGuard.
            pairing.pin?.let { pin ->
                val tm = CertPin.trustManager(pin)
                sslSocketFactory(CertPin.socketFactory(tm), tm)
                hostnameVerifier { _, _ -> true }
            }
        }
        .addInterceptor { chain ->
            val req = chain.request()
            // Defense in depth: the token only ever goes to a tailnet host, or to a LAN host over
            // pinned TLS. Never in clear text across a Wi-Fi.
            if (!hostGuard(req.url.host, req.url.isHttps))
                throw IOException("Refusing to send credentials to ${req.url.host} without a verified connection")
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${pairing.token}").build())
        }
        .build()

    /** The live connection. ws(s) follows the pairing's scheme; the token rides in the header. */
    fun gateway(): Gateway {
        val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/v1/ws"
        return Gateway(http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(), wsUrl)
    }

    private fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val b = (base + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private fun parseError(resp: Response, body: String): BridgeException {
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val code = err?.str("code") ?: "http_${resp.code}"
        val message = err?.str("message") ?: "HTTP ${resp.code}"
        return BridgeException(resp.code, code, message)
    }

    private suspend fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(),
                             body: JSONObject? = null, readTimeoutS: Long = 0): JSONObject = runInterruptible(Dispatchers.IO) {
        val reqBody = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "POST" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        val req = Request.Builder().url(url(path, query)).method(method, reqBody).build()
        val client = if (readTimeoutS > 0) http.newBuilder().readTimeout(readTimeoutS, TimeUnit.SECONDS).build() else http
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseError(resp, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    // ------------------------------------------------------------ endpoints

    suspend fun me() = call("GET", "/v1/me")

    suspend fun notes(query: String = "", archived: Boolean = false, label: String? = null) =
        call("GET", "/v1/notes", mapOf("q" to query.ifBlank { null }, "archived" to archived, "label" to label))
    suspend fun blueprints() = call("GET", "/v1/blueprints")
    suspend fun blueprint(projectId: String, since: Int = 0) = call("GET", "/v1/blueprints/${enc(projectId)}", mapOf("since" to since.takeIf { it > 0 }))
    suspend fun createBlueprint(projectId: String, body: JSONObject) = call("POST", "/v1/blueprints/${enc(projectId)}", body = body)
    suspend fun blueprintDefaults(body: JSONObject) = call("PUT", "/v1/blueprints-settings", body = body)
    suspend fun blueprintOps(projectId: String, ops: org.json.JSONArray) = call("POST", "/v1/blueprints/${enc(projectId)}/ops", body = JSONObject().put("ops", ops))
    suspend fun blueprintFileInfo(projectId: String, path: String) = call("GET", "/v1/blueprints/${enc(projectId)}/file/info", mapOf("path" to path))

    /** A file a blueprint card comment attaches, streamed into [dest]. */
    suspend fun blueprintFile(projectId: String, path: String, dest: java.io.File) = runInterruptible(Dispatchers.IO) { download(url("/v1/blueprints/${enc(projectId)}/file", mapOf("path" to path)), dest) }
    /** A file the phone attaches to a blueprint card; the bridge stores it and answers `{path, name, size}`. */
    suspend fun blueprintUpload(projectId: String, name: String, bytes: ByteArray): JSONObject = runInterruptible(Dispatchers.IO) {
        val req = Request.Builder().url(url("/v1/blueprints/${enc(projectId)}/upload", mapOf("name" to name)))
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newBuilder().writeTimeout(120, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseError(resp, text)
            JSONObject(text)
        }
    }
    suspend fun trashBlueprint(projectId: String) = call("DELETE", "/v1/blueprints/${enc(projectId)}")
    suspend fun restoreBlueprint(trashId: String) = call("POST", "/v1/blueprints-trash/${enc(trashId)}/restore")
    suspend fun purgeBlueprint(trashId: String) = call("DELETE", "/v1/blueprints-trash/${enc(trashId)}")
    suspend fun createNote(body: JSONObject) = call("POST", "/v1/notes", body = body)
    suspend fun updateNote(id: String, body: JSONObject) = call("PATCH", "/v1/notes/${enc(id)}", body = body)
    suspend fun deleteNote(id: String) = call("DELETE", "/v1/notes/${enc(id)}")

    /** PC bridge admin (`/v1/admin/...`): doctor with fixes, restart, networks, devices, log. */
    suspend fun adminDoctor() = call("GET", "/v1/admin/doctor", readTimeoutS = 30)
    suspend fun adminFix(fix: String) = call("POST", "/v1/admin/fix/${enc(fix)}", readTimeoutS = 60)
    suspend fun adminRestart() = call("POST", "/v1/admin/restart", readTimeoutS = 30)
    suspend fun adminTrust() = call("GET", "/v1/admin/trust")
    suspend fun adminTrustNow() = call("POST", "/v1/admin/trust")
    suspend fun adminUntrust(id: String) = call("POST", "/v1/admin/untrust", body = JSONObject().put("id", id))
    suspend fun adminDevices() = call("GET", "/v1/admin/devices")
    suspend fun adminRevoke(name: String) = call("POST", "/v1/admin/revoke", body = JSONObject().put("name", name))
    suspend fun adminParked() = call("GET", "/v1/admin/parked")
    suspend fun adminLogs(lines: Int = 200) = call("GET", "/v1/admin/logs", mapOf("lines" to lines), readTimeoutS = 20)

    /** The bridge's release and the newest one (`/v1/bridge/update`). */
    suspend fun bridgeUpdateCheck(refresh: Boolean = false) = call("GET", "/v1/bridge/update", mapOf("refresh" to refresh), readTimeoutS = 40)

    /** Starts the bridge's self-update; it restarts at the end with every pairing kept. */
    suspend fun startBridgeUpdate() = call("POST", "/v1/bridge/update", readTimeoutS = 40)

    /** An image a chat shows, by the PC path Hermes stored for it (`/v1/media`; bridges before 1.11 answer 404). */
    override suspend fun media(path: String): ByteArray = runInterruptible(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url("/v1/media", mapOf("path" to path))).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw parseError(resp, resp.body?.string().orEmpty())
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * A file the chat [sessionId] names (a reply's `MEDIA:` line or an `@file:` the user sent),
     * streamed into [dest]. Bridges before 1.16 answer 404.
     */
    suspend fun chatFile(sessionId: String, path: String, profile: String?, dest: java.io.File) = runInterruptible(Dispatchers.IO) {
        download(url("/v1/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/file", mapOf("path" to path, "profile" to profile)), dest)
    }

    private fun download(u: String, dest: java.io.File) {
        val req = Request.Builder().url(u).build()
        http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw parseError(resp, resp.body?.string().orEmpty())
            val body = resp.body ?: throw IOException("Empty answer")
            dest.parentFile?.mkdirs()
            val tmp = java.io.File(dest.path + ".part")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("Couldn't store ${dest.name}") }
        }
    }

    /** Name, full path, size, type and modified time of a file or picture the chat shows (no download). */
    suspend fun chatFileInfo(sessionId: String, path: String, profile: String?) =
        call("GET", "/v1/sessions/${enc(sessionId)}/file/info", mapOf("path" to path) + scoped(profile))

    /** Cheap reachability check with a short timeout: an absent LAN must not stall switching. */
    suspend fun reachable(): Boolean = probe() == Probe.OK

    enum class Probe { OK, UNAUTHORIZED, UNREACHABLE }

    /** Like [reachable], but tells "the PC answered and refused this token" (revoked, or re-paired
     *  from the PC) apart from "nothing answered", so the app can offer to re-pair. */
    suspend fun probe(): Probe = runInterruptible(Dispatchers.IO) {
        runCatching {
            http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$base/v1/me").build())
                .execute().use { when { it.isSuccessful -> Probe.OK; it.code == 401 -> Probe.UNAUTHORIZED; else -> Probe.UNREACHABLE } }
        }.getOrDefault(Probe.UNREACHABLE)
    }

    override suspend fun status(): List<ComponentStatus> = StatusMapper.map(call("GET", "/v1/status").getJSONObject("components"))

    /** Hermes profiles the phone may use. Older bridges have no such route (404): treat as default only. */
    override suspend fun profiles(): List<HermesProfile> = parseProfiles(call("GET", "/v1/profiles"))

    // Every call below acts on one Hermes profile: [profile] null (or "default") is the default profile.

    /** The desktop sidebar's list: same query, same order (newest activity first). */
    override suspend fun sessions(limit: Int, offset: Int, profile: String?): Pair<List<SessionSummary>, Boolean> {
        val o = call("GET", "/v1/sessions", mapOf("limit" to limit, "offset" to offset) + scoped(profile))
        val list = o.optJSONArray("sessions").objects().map(::parseSession)
        val total = if (o.isNull("total")) null else o.optInt("total")
        return list to (if (total != null) offset + list.size < total else list.size >= limit)
    }

    /** Chats whose messages contain [query] (Hermes' own search). Bridges before this route answer 404. */
    override suspend fun searchSessions(query: String, limit: Int, profile: String?): List<SearchHit> =
        parseSearchHits(call("GET", "/v1/sessions/search", mapOf("q" to query, "limit" to limit) + scoped(profile)))

    /** A project's IDEA.md (its description file in the project folder). Bridges before 1.17 answer 404. */
    suspend fun projectIdea(id: String, profile: String? = null): String =
        call("GET", "/v1/projects/${enc(id)}/idea", scoped(profile)).optString("text")

    suspend fun setProjectIdea(id: String, text: String, profile: String? = null) =
        call("PUT", "/v1/projects/${enc(id)}/idea", scoped(profile), JSONObject().put("text", text))

    override suspend fun renameSession(id: String, title: String, profile: String?) =
        call("PATCH", "/v1/sessions/${enc(id)}", scoped(profile), JSONObject().put("title", title))

    override suspend fun setPinned(id: String, pinned: Boolean, profile: String?) =
        call("PATCH", "/v1/sessions/${enc(id)}", scoped(profile), JSONObject().put("pinned", pinned))

    suspend fun approvalMode(profile: String? = null): String =
        call("GET", "/v1/settings/approvals", scoped(profile)).getString("mode")

    suspend fun setApprovalMode(mode: String, profile: String? = null): String =
        call("PUT", "/v1/settings/approvals", scoped(profile), JSONObject().put("mode", mode)).getString("mode")

    /** Hermes' profile default model (new chats); null on bridges older than this route. */
    suspend fun defaultModel(profile: String? = null): Pair<String, String> =
        call("GET", "/v1/settings/model", scoped(profile)).let { it.optString("model") to it.optString("provider") }

    /** Returns the bridge's reply: either the applied model or `confirm_required` + `confirm_message`. */
    suspend fun setDefaultModel(model: String, provider: String, confirmed: Boolean, profile: String? = null): JSONObject =
        call("PUT", "/v1/settings/model", scoped(profile), JSONObject().put("model", model).put("provider", provider)
            .put("confirm_expensive_model", confirmed))

    /** The profile's scheduled jobs. Bridges older than this route answer 404. */
    override suspend fun cronJobs(profile: String?): List<CronJob> = parseCronJobs(call("GET", "/v1/cron/jobs", scoped(profile)))

    override suspend fun cronRuns(jobId: String, profile: String?, limit: Int): List<CronRun> =
        parseCronRuns(call("GET", "/v1/cron/jobs/${enc(jobId)}/runs", mapOf("limit" to limit) + scoped(profile)))

    /** A new job: [prompt] run on [schedule] ("every 30m", "every day 8am", "0 9 * * 1-5"), saved locally. */
    override suspend fun createCronJob(name: String, schedule: String, prompt: String, profile: String?): CronJob? =
        call("POST", "/v1/cron/jobs", scoped(profile), JSONObject().put("name", name).put("schedule", schedule)
            .put("prompt", prompt)).optJSONObject("job")?.let(::parseCronJob)

    /** [action] is pause, resume or trigger (run now). Returns the job as it is afterwards, when Hermes says. */
    override suspend fun cronAction(jobId: String, action: String, profile: String?): CronJob? =
        call("POST", "/v1/cron/jobs/${enc(jobId)}/$action", scoped(profile)).optJSONObject("job")?.let(::parseCronJob)

    // PC monitor (bridge 1.18+; older bridges answer 404).

    suspend fun setMonitorTracking(group: String, on: Boolean) = call("PUT", "/v1/monitor/tracking", body = JSONObject().put(group, on))

    suspend fun monitorNow(live: Boolean = false): PcNow = parseNow(call("GET", "/v1/monitor/now", mapOf("live" to live)))

    /** The bridge's own metrics (this device's token opens them). 404: an older bridge, or `metrics = false`. */
    suspend fun bridgeStats(): BridgeStats = parseBridgeStats(call("GET", "/metrics", mapOf("format" to "json")))

    suspend fun monitorHistory(range: String): PcHistory = parseHistory(call("GET", "/v1/monitor/history", mapOf("range" to range)))

    /** (total matching, the first [limit] sorted by [sort]). */
    suspend fun processes(sort: String, limit: Int, query: String = ""): Pair<Int, List<PcProcess>> =
        call("GET", "/v1/monitor/processes", mapOf("sort" to sort, "limit" to limit, "q" to query.ifBlank { null }))
            .let { it.optInt("total") to it.optJSONArray("processes").objects().map(::parseProcess) }

    /** True when the process is gone afterwards. */
    suspend fun stopProcess(pid: Int, force: Boolean): Boolean =
        call("POST", "/v1/monitor/processes/$pid/stop", mapOf("force" to force)).optBoolean("stopped")

    suspend fun claudeUsage(refresh: Boolean = false): ClaudeUsage = parseClaude(call("GET", "/v1/claude/usage", mapOf("refresh" to refresh)))

    /** Runs an internet speed test on the PC (bridge with the `speedtest` feature); takes ~20 s. */
    suspend fun runSpeedTest(): SpeedResult = parseSpeed(call("POST", "/v1/monitor/speedtest", readTimeoutS = 90))

    /** (a test is running now, past tests newest first). */
    suspend fun speedTests(): Pair<Boolean, List<SpeedResult>> =
        call("GET", "/v1/monitor/speedtest").let { it.optBoolean("running") to it.optJSONArray("tests").objects().map(::parseSpeed) }

    /** Sub-folders of [path] on the PC (home when blank), for the project folder picker. */
    suspend fun folders(path: String, hidden: Boolean = false): FolderListing =
        parseFolders(call("GET", "/v1/folders", mapOf("path" to path.ifBlank { null }, "hidden" to hidden.takeIf { it })))

    suspend fun makeFolder(path: String): FolderListing = parseFolders(call("POST", "/v1/folders", body = JSONObject().put("path", path)))

    suspend fun claudeHistory(range: String): PcHistory = parseHistory(call("GET", "/v1/claude/history", mapOf("range" to range)))

    /** A shell on the PC (bridge 1.18+). The token rides in the upgrade request's header, as for chat. */
    fun terminal(cols: Int, rows: Int, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/v1/terminal?cols=$cols&rows=$rows"
        return http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            .newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun scoped(profile: String?): Map<String, Any?> = mapOf("profile" to profileParam(profile))
}

object StatusMapper {
    fun map(c: JSONObject): List<ComponentStatus> {
        val out = mutableListOf<ComponentStatus>()
        c.optJSONObject("bridge")?.let {
            out += ComponentStatus("bridge", "Bridge", true, "v${it.str("version")}", null)
        }
        c.optJSONObject("hermes")?.let {
            val ok = it.str("status") == "ok"
            out += ComponentStatus("hermes", "Hermes agent", ok,
                if (ok) "v${it.str("version") ?: "?"}" else (it.str("error") ?: "not running"),
                when {
                    ok && it.optBoolean("shared_with_desktop") -> "Same Hermes as the desktop app"
                    ok -> "Started by the bridge"
                    it.optBoolean("starts_on_connect") -> "Starts when the app connects"
                    else -> null
                })
        }
        c.optJSONObject("tailscale")?.let {
            out += ComponentStatus("tailscale", "Tailscale (PC)", it.str("status") == "ok",
                it.str("dns_name")?.takeIf { d -> d.isNotEmpty() } ?: (it.str("host") ?: it.str("status") ?: "?"), null)
        }
        return out
    }
}
