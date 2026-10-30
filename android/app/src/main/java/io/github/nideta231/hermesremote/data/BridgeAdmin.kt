package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One doctor check: [level] ok / warn / fail, and the one-tap [fix] the bridge offers for it. */
data class DoctorCheck(val id: String, val level: String, val message: String, val fix: String?)

data class AdminNetwork(val id: String, val name: String, val since: Double?)
data class AdminDevice(val id: String, val name: String, val active: Boolean, val lastSeen: Double?, val createdAt: Double?, val isThis: Boolean)

/** The PC's Tailscale Funnel: can it, is it on, at which address, and which phones may use it. */
data class FunnelAdmin(val available: Boolean, val reason: String?, val on: Boolean, val url: String?,
                       val ports: List<Int>, val devices: List<AdminDevice>,
                       /** Tailscale's one-click page that enables Funnel for the tailnet (admin sign-in). */
                       val enableUrl: String? = null)

data class BridgeAdminState(
    val checks: List<DoctorCheck> = emptyList(),
    val version: String? = null,
    val currentNetwork: String? = null,
    val currentNetworkId: String? = null,
    val currentTrusted: Boolean = false,
    val networks: List<AdminNetwork> = emptyList(),
    val devices: List<AdminDevice> = emptyList(),
    val logs: String? = null,
    /** Hermes sockets the bridge holds for dropped phones; empty on a bridge without the route. */
    val parked: List<ParkedSocket> = emptyList(),
    val parkedSupported: Boolean = false,
    /** null: the bridge has no Funnel support (older, or not Linux). */
    val funnel: FunnelAdmin? = null,
    val loading: Boolean = false,
    /** A fix or restart in flight (its name). */
    val busy: String? = null,
    val restarting: Boolean = false,
    val error: String? = null,
)

/**
 * Settings > PC bridge: what `hermes-remote-bridge doctor / trust / devices / revoke` and a service
 * restart do on the PC, from the phone. A restart drops the live socket for a few seconds; this waits
 * for the bridge to answer again and then brings the socket back at once ([onBridgeBack]).
 */
class BridgeAdmin(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
    private val onBridgeBack: () -> Unit,
    /** Whether the bridge lists `parked` in `/v1/me`; an old one has no such route. */
    private val supportsParked: () -> Boolean = { false },
    private val supportsFunnel: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(BridgeAdminState())
    val state: StateFlow<BridgeAdminState> = _state.asStateFlow()

    fun clear() { _state.value = BridgeAdminState() }

    private fun fail(t: Throwable) {
        if (t is CancellationException) throw t
        _state.update { it.copy(loading = false, busy = null, error = describe(t)) }
    }

    fun refresh() {
        val c = client() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val doc = c.adminDoctor()
                val trust = c.adminTrust()
                val devs = c.adminDevices()
                apply(doc); applyTrust(trust); applyDevices(devs)
                loadParked(c)
                loadFunnel(c)
                _state.update { it.copy(loading = false) }
            } catch (t: Throwable) { fail(t) }
        }
    }

    private suspend fun loadFunnel(c: BridgeClient) {
        val f = if (!supportsFunnel()) null else runCatching { parseFunnel(c.adminFunnel()) }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
        _state.update { it.copy(funnel = f) }
    }

    /** Switch the PC's Funnel on (at [port]) or off. */
    fun setFunnel(on: Boolean, port: Int = 443) {
        val c = client() ?: return
        _state.update { it.copy(busy = "funnel") }
        scope.launch {
            try {
                c.adminFunnelSet(on, port)
                _state.update { it.copy(busy = null) }
                say(if (on) "Funnel is on: the PC answers on the internet for allowed phones" else "Funnel is off")
                loadFunnel(c)
            } catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    /** Take another phone off the internet route. */
    fun removeFunnel(name: String) {
        val c = client() ?: return
        scope.launch {
            try { c.adminFunnelRemove(name); say("$name can no longer connect over the internet"); loadFunnel(c) }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    /** Reload the held list; a failure here only hides the section, it never breaks the page. */
    private suspend fun loadParked(c: BridgeClient) {
        val ok = supportsParked()
        val list = if (!ok) emptyList() else runCatching { ParkedSockets.parse(c.adminParked()) }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        _state.update { it.copy(parked = list, parkedSupported = ok) }
    }

    private fun apply(doc: JSONObject) = _state.update { st ->
        st.copy(checks = doc.optJSONArray("checks").objects().map {
            DoctorCheck(it.optString("id"), it.optString("level"), it.optString("message"), it.str("fix")?.takeIf { f -> f.isNotBlank() })
        }, version = doc.str("running") ?: doc.str("version"))
    }

    private fun applyTrust(o: JSONObject) = _state.update { st ->
        val cur = o.optJSONObject("current")
        st.copy(currentNetwork = cur?.str("name"), currentNetworkId = cur?.str("id"), currentTrusted = cur?.optBoolean("trusted") == true,
            networks = o.optJSONArray("trusted").objects().map { AdminNetwork(it.optString("id"), it.optString("name"), it.dbl("since")) })
    }

    private fun applyDevices(o: JSONObject) = _state.update { st ->
        st.copy(devices = o.optJSONArray("devices").objects().map {
            AdminDevice(it.optString("id"), it.optString("name"), it.optBoolean("active"), it.dbl("last_seen_at"), it.dbl("created_at"), it.optBoolean("this"))
        }.filter { it.active })
    }

    fun fix(name: String) {
        if (name == "restart") return restart()
        val c = client() ?: return
        _state.update { it.copy(busy = name) }
        scope.launch {
            try { apply(c.adminFix(name)); _state.update { it.copy(busy = null) }; say("Fixed"); refresh() }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun trustCurrent() {
        val c = client() ?: return
        _state.update { it.copy(busy = "trust") }
        scope.launch {
            try { applyTrust(c.adminTrustNow()); _state.update { it.copy(busy = null) }; say("Network trusted: the phone can use Wi-Fi here"); refresh() }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun untrust(id: String) {
        val c = client() ?: return
        scope.launch {
            try { applyTrust(c.adminUntrust(id)); refresh() } catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun revoke(name: String) {
        val c = client() ?: return
        scope.launch {
            try { c.adminRevoke(name); say("$name can no longer connect"); applyDevices(c.adminDevices()) }
            catch (t: Throwable) { fail(t); say(describe(t)) }
        }
    }

    fun loadLogs() {
        val c = client() ?: return
        scope.launch {
            try { val t = c.adminLogs(300).optString("text"); _state.update { it.copy(logs = t.ifBlank { "No log lines." }) } }
            catch (t: Throwable) { fail(t) }
        }
    }

    /** Restart the PC bridge, then wait for it and bring the connection straight back. */
    fun restart() {
        val c = client() ?: return
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true, busy = "restart", error = null) }
        scope.launch {
            try { c.adminRestart() } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(restarting = false, busy = null, error = describe(t)) }
                say(describe(t)); return@launch
            }
            say("Restarting the PC bridge…")
            var sawDown = false
            repeat(40) {
                delay(2_500)
                val c2 = client() ?: return@repeat
                val ok = runCatching { c2.adminDoctor() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (ok == null) { sawDown = true; return@repeat }
                // Answering again. If we never saw it go down, it may not have restarted yet: give it one more round.
                if (!sawDown && it < 3) return@repeat
                apply(ok)
                _state.update { it.copy(restarting = false, busy = null) }
                onBridgeBack()
                say("PC bridge restarted")
                refresh()
                return@launch
            }
            _state.update { it.copy(restarting = false, busy = null, error = "The bridge didn't answer yet; it keeps trying on the PC.") }
            onBridgeBack()
        }
    }
}

internal fun parseFunnel(o: JSONObject) = FunnelAdmin(
    available = o.optBoolean("available"), reason = o.str("reason")?.takeIf { it.isNotBlank() },
    on = o.optBoolean("on"), url = o.str("url")?.takeIf { it.isNotBlank() },
    ports = o.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty().ifEmpty { listOf(443, 8443, 10000) },
    devices = o.optJSONArray("devices").objects().map { AdminDevice(it.optString("id"), it.optString("name"), true, null, it.dbl("since"), it.optBoolean("this")) },
    enableUrl = o.str("enable_url")?.takeIf { it.startsWith("https://login.tailscale.com/") })
