package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Incremental SSE parser for the bridge's `id:` / `event:` / `data:` frames. */
class SseParser {
    private var id = 0L
    private var event: String? = null
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty()) { event = null; return null }
            val ev = SseEvent(id, event ?: "message", runCatching { JSONObject(data.toString()) }.getOrElse { JSONObject() })
            event = null
            data.setLength(0)
            return ev
        }
        if (line.startsWith(":")) return null
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> value.toLongOrNull()?.let { id = it }
            "event" -> event = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}

class BridgeClient(
    private val pairing: Pairing,
    baseClient: OkHttpClient = OkHttpClient(),
    // Tests only: lets a loopback TLS server stand in for the PC. Production always uses the
    // real rule (tailnet, or LAN over pinned HTTPS).
    private val hostGuard: (host: String, https: Boolean) -> Boolean =
        { host, https -> Transport.allowsToken(host, https, pairing.pin != null) },
) {
    private val base = pairing.url.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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

    // Bridge sends keepalives every 15s; 45s without bytes means the link is dead.
    private val sse: OkHttpClient = http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    private val fast: OkHttpClient = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

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
                             body: JSONObject? = null): JSONObject = runInterruptible(Dispatchers.IO) {
        val reqBody = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "POST" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        val req = Request.Builder().url(url(path, query)).method(method, reqBody).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseError(resp, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    // ------------------------------------------------------------ endpoints

    suspend fun me() = call("GET", "/v1/me")

    /** Cheap reachability check with a short timeout: an absent LAN must not stall switching. */
    suspend fun reachable(): Boolean = runInterruptible(Dispatchers.IO) {
        runCatching {
            http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$base/v1/me").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun status(): List<ComponentStatus> {
        val c = call("GET", "/v1/status").getJSONObject("components")
        return StatusMapper.map(c)
    }

    suspend fun desktop(): DesktopInfo = call("GET", "/v1/desktop").let {
        DesktopInfo(it.getString("host"), it.str("dns_name"), it.optInt("port", 3389), it.str("username"), it.getString("rdp_uri"))
    }

    suspend fun sessions(limit: Int = 50, offset: Int = 0): Pair<List<SessionSummary>, Boolean> {
        val o = call("GET", "/v1/sessions", mapOf("limit" to limit, "offset" to offset))
        return o.getJSONArray("data").objects().map(::parseSession) to o.optBoolean("has_more")
    }

    suspend fun createSession(title: String?): SessionSummary =
        parseSession(call("POST", "/v1/sessions", body = JSONObject().apply { if (title != null) put("title", title) })
            .getJSONObject("session"))

    /** Returns the session plus the bridge-tracked active run (if any). */
    suspend fun session(id: String): Pair<SessionSummary, RunSnapshot?> {
        val o = call("GET", "/v1/sessions/${enc(id)}")
        val s = parseSession(o.optJSONObject("session") ?: o)
        return s to o.optJSONObject("active_run")?.let(::parseRun)
    }

    suspend fun renameSession(id: String, title: String) =
        call("PATCH", "/v1/sessions/${enc(id)}", body = JSONObject().put("title", title))

    suspend fun setPinned(id: String, pinned: Boolean) =
        call("PATCH", "/v1/sessions/${enc(id)}", body = JSONObject().put("pinned", pinned))

    suspend fun deleteSession(id: String) = call("DELETE", "/v1/sessions/${enc(id)}", mapOf("confirm" to id))

    suspend fun messages(id: String): JSONArray =
        call("GET", "/v1/sessions/${enc(id)}/messages", mapOf("limit" to 500)).getJSONArray("data")

    /** Tail a session driven by another surface (desktop, messaging platforms). See the bridge's sync route. */
    suspend fun sync(id: String, since: Int, limit: Int = 60): SessionSync =
        call("GET", "/v1/sessions/${enc(id)}/sync", mapOf("since" to since, "limit" to limit)).let {
            SessionSync(it.getJSONArray("messages").objects(), it.getInt("cursor"), it.getBoolean("changed"),
                it.optBoolean("active"), it.str("model"))
        }

    suspend fun models(): ModelCatalog = parseCatalog(call("GET", "/v1/models"))

    suspend fun approvalMode(): String = call("GET", "/v1/settings/approvals").getString("mode")

    suspend fun setApprovalMode(mode: String): String =
        call("PUT", "/v1/settings/approvals", body = JSONObject().put("mode", mode)).getString("mode")

    /**
     * Starting a run is idempotent (client_request_id), so it can fail fast and retry: a fresh
     * connection (a pooled one that died with the Wi-Fi hangs until the 30s read timeout) and a
     * short deadline. Without this a send after idle could sit for half a minute.
     */
    suspend fun startRun(sessionId: String, input: String, clientRequestId: String,
                         model: String? = null, provider: String? = null, reasoningEffort: String? = null): RunSnapshot {
        val body = JSONObject()
            .put("session_id", sessionId).put("input", input).put("client_request_id", clientRequestId)
            .apply {
                model?.let { put("model", it) }
                provider?.let { put("provider", it) }
                reasoningEffort?.let { put("reasoning_effort", it) }
            }
        return parseRun(runInterruptible(Dispatchers.IO) {
            val req = Request.Builder().url(url("/v1/runs")).header("Connection", "close")
                .post(body.toString().toRequestBody(jsonType)).build()
            fast.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw parseError(resp, text)
                JSONObject(text)
            }
        })
    }

    suspend fun commands(): List<SlashCommand> = parseCommands(call("GET", "/v1/commands"))

    suspend fun runCommand(sessionId: String, command: String): CommandReply =
        parseCommandReply(call("POST", "/v1/sessions/${enc(sessionId)}/command", body = JSONObject().put("command", command)))

    suspend fun run(runId: String): RunSnapshot = parseRun(call("GET", "/v1/runs/${enc(runId)}"))

    suspend fun stop(runId: String) = call("POST", "/v1/runs/${enc(runId)}/stop")

    suspend fun steer(runId: String, text: String) = call("POST", "/v1/runs/${enc(runId)}/steer", body = JSONObject().put("text", text))

    suspend fun approve(runId: String, choice: String, requestId: String?) =
        call("POST", "/v1/runs/${enc(runId)}/approval", body = JSONObject().put("choice", choice).apply {
            if (requestId != null) put("request_id", requestId)
        })

    /** Live run events after [after]; completes when the bridge closes the stream (terminal event sent). */
    fun events(runId: String, after: Long): Flow<SseEvent> = flow {
        val req = Request.Builder().url(url("/v1/runs/${enc(runId)}/events", mapOf("after" to after)))
            .header("Accept", "text/event-stream").build()
        val call = sse.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw parseError(resp, resp.body?.string().orEmpty())
                val source = resp.body!!.source()
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { emit(it) }
                }
            }
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object StatusMapper {
    fun map(c: JSONObject): List<ComponentStatus> {
        val out = mutableListOf<ComponentStatus>()
        c.optJSONObject("bridge")?.let {
            out += ComponentStatus("bridge", "Bridge", true, "v${it.str("version")}",
                "${it.optInt("active_runs")} active run(s)")
        }
        c.optJSONObject("hermes")?.let {
            val st = it.str("status")
            val failing = it.optJSONObject("checks")?.let { ch -> ch.keys().asSequence().filter { k -> ch.optString(k) != "ok" }.toList() }
            out += ComponentStatus("hermes", "Hermes agent", st == "ok",
                if (st == "ok") "v${it.str("version")} · gateway ${it.str("gateway_state") ?: "?"}" else (it.str("error") ?: st ?: "unknown"),
                failing?.takeIf { f -> f.isNotEmpty() }?.joinToString(prefix = "Degraded: "))
        }
        val model = c.optJSONObject("model")
        out += ComponentStatus("model", "Cloud model", model != null,
            model?.let { "${it.str("model")}" } ?: "unavailable", model?.str("provider")?.let { "provider: $it" })
        c.optJSONObject("desktop")?.let {
            out += ComponentStatus("desktop", "Remote desktop (KRdp)", it.str("status") == "ok",
                if (it.str("status") == "ok") "listening on :${it.optInt("port")}" else "service ${it.str("unit_state")}", null)
        }
        c.optJSONObject("tailscale")?.let {
            val peers = it.optJSONArray("peers")?.objects().orEmpty()
            out += ComponentStatus("tailscale", "Tailscale (PC)", it.str("status") == "ok",
                it.str("dns_name")?.takeIf { d -> d.isNotEmpty() } ?: (it.str("host") ?: "?"),
                peers.joinToString { p -> "${p.optString("name")} ${if (p.optBoolean("online")) "●" else "○"}" }.ifEmpty { null })
        }
        return out
    }
}

/**
 * Merges bursts of `message.delta` events into one per [windowMs]. Applying every token to the chat
 * re-parses the markdown and re-pins the scroll per token, which flickers and starves the main
 * thread (taps, including Send, queue behind it). Other events flush the pending text first, so
 * order is preserved; the merged event carries the newest id so replay cursors stay exact.
 */
fun Flow<SseEvent>.coalesceDeltas(windowMs: Long = 60): Flow<SseEvent> = channelFlow {
    val lock = Mutex()
    var pending: SseEvent? = null
    suspend fun flush() { pending?.let { send(it); pending = null } }
    val ticker = launch { while (true) { delay(windowMs); lock.withLock { flush() } } }
    try {
        collect { ev ->
            lock.withLock {
                if (ev.name == "message.delta") {
                    val prev = pending
                    pending = if (prev == null) ev
                    else SseEvent(ev.id, ev.name, JSONObject().put("delta", prev.data.optString("delta") + ev.data.optString("delta")))
                } else { flush(); send(ev) }
            }
        }
        lock.withLock { flush() }
    } finally { ticker.cancel() }
}
