package io.github.nideta231.hermesremote

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.nideta231.hermesremote.data.AppUpdate
import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.BridgeException
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.SessionView
import io.github.nideta231.hermesremote.data.SessionViewStore
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.ComponentStatus
import io.github.nideta231.hermesremote.data.CredentialStore
import io.github.nideta231.hermesremote.data.Dispatch
import io.github.nideta231.hermesremote.data.DraftStore
import io.github.nideta231.hermesremote.data.Endpoint
import io.github.nideta231.hermesremote.data.EndpointResolver
import io.github.nideta231.hermesremote.data.Gateway
import io.github.nideta231.hermesremote.data.GatewayState
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.Incoming
import io.github.nideta231.hermesremote.data.LanDiscovery
import io.github.nideta231.hermesremote.data.LiveLink
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import io.github.nideta231.hermesremote.data.ProfileStore
import io.github.nideta231.hermesremote.data.Notifier
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.REASONING_LEVELS
import io.github.nideta231.hermesremote.data.RpcException
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.SlashSuggestion
import io.github.nideta231.hermesremote.data.Tailnet
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import io.github.nideta231.hermesremote.data.UpdateEvents
import io.github.nideta231.hermesremote.data.Updater
import io.github.nideta231.hermesremote.data.WatchService
import io.github.nideta231.hermesremote.data.objects
import io.github.nideta231.hermesremote.data.parseApproval
import io.github.nideta231.hermesremote.data.parseCatalog
import io.github.nideta231.hermesremote.data.parseClarify
import io.github.nideta231.hermesremote.data.parseDispatch
import io.github.nideta231.hermesremote.data.parseSuggestions
import io.github.nideta231.hermesremote.data.reuseKeys
import io.github.nideta231.hermesremote.data.str
import io.github.nideta231.hermesremote.data.strings
import io.github.nideta231.hermesremote.data.withInflight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

enum class Link { IDLE, LIVE, RECONNECTING }

data class ChatState(
    /** Stored session id: what the sidebar, the desktop and notifications know the chat by. */
    val sessionId: String? = null,
    /** Hermes' live id for this chat on the shared socket; every RPC and event uses it. */
    val runtimeId: String? = null,
    val title: String = "New chat",
    val items: List<ChatItem> = emptyList(),
    /** idle, starting, working or waiting (a question is open). Same words as the desktop sidebar. */
    val status: String = "idle",
    val link: Link = Link.IDLE,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val pinned: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val reasoning: String? = null,
    /** Unsent composer text for this session; survives tab switches and process death. */
    val draft: String = "",
) {
    val busy: Boolean get() = status != "idle"
    val waiting: Boolean get() = status == "waiting" || LiveReducer.openApproval(items) != null || LiveReducer.openClarify(items) != null
}

data class SessionsState(
    val items: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    /** Stored id → live status for sessions doing something right now (the sidebar shimmer). */
    val live: Map<String, String> = emptyMap(),
    /** Projects from `projects.tree`, for grouping and filtering the drawer by project. */
    val projects: List<ProjectRef> = emptyList(),
)

/** A paired PC in the switcher. [name] is the PC's computer name, or the host it was paired at. */
data class PairedPc(val id: String, val name: String, val url: String, val active: Boolean)

/** Which address the app is using, and what else it could use. */
data class ConnectionState(
    val activeUrl: String? = null,
    val transport: Transport? = null,
    val mode: TransportMode = TransportMode.AUTO,
    val lanAvailable: Boolean = false,
    val tailnetAvailable: Boolean = false,
    val searching: Boolean = false,
    /** Paired with an old (v1) code: no certificate pin, so the LAN can't be used safely. */
    val needsRepairForLan: Boolean = false,
    /** Name of the PC's current network and whether the user trusts it (from the bridge). */
    val pcNetwork: String? = null,
    val pcNetworkTrusted: Boolean? = null,
    /** The live socket to Hermes is up. */
    val live: Boolean = false,
    val liveError: String? = null,
)

data class SystemState(
    val components: List<ComponentStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val checkedAt: Long? = null,
    val approvalMode: String? = null,
    val approvalSaving: Boolean = false,
)

data class UpdateState(
    val installed: String = "",
    val available: AppUpdate? = null,
    val checking: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    val checkedAt: Long? = null,
    val error: String? = null,
)

/** An expensive model needs a yes before Hermes switches to it (the desktop asks the same). */
data class ModelConfirm(val option: ModelOption, val message: String)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val profiles = ProfileStore(app)
    private var store = CredentialStore(app, ProfileStore.pairingFile(profiles.activeId))
    private var drafts = DraftStore(app, ProfileStore.draftsFile(profiles.activeId))
    private var bridgeAddresses: Map<String, List<String>>? = null
    private var client: BridgeClient? = null
    private var gateway: Gateway? = null
    private var gatewayJobs: List<Job> = emptyList()

    private val _pairing = MutableStateFlow(store.load())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    /** Every paired PC, for the switcher. */
    private val _pcs = MutableStateFlow<List<PairedPc>>(emptyList())
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()

    /** "Add a PC" is open: the pairing screen shows over the app, and a pairing lands in a new profile. */
    private val _addingPc = MutableStateFlow(false)
    val addingPc: StateFlow<Boolean> = _addingPc.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    /** How the drawer groups, sorts and filters sessions; persisted across launches. */
    private val viewStore = SessionViewStore(app)
    private val _sessionView = MutableStateFlow(viewStore.load())
    val sessionView: StateFlow<SessionView> = _sessionView.asStateFlow()

    fun setSessionView(v: SessionView) {
        _sessionView.value = v
        viewStore.save(v)
    }

    private val _system = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()

    private val _models = MutableStateFlow<ModelCatalog?>(null)
    val models: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _conn = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _conn.asStateFlow()

    /** Model for a chat not created yet; an open chat uses (and shows) its own. */
    private val _modelChoice = MutableStateFlow<ModelOption?>(null)
    val modelChoice: StateFlow<ModelOption?> = _modelChoice.asStateFlow()

    /** Reasoning effort shown in the composer: the open chat's, or the one for the next new chat. */
    private val _reasoning = MutableStateFlow(store.reasoningEffort)
    val reasoning: StateFlow<String?> = _reasoning.asStateFlow()

    private val _suggestions = MutableStateFlow<List<SlashSuggestion>>(emptyList())
    val suggestions: StateFlow<List<SlashSuggestion>> = _suggestions.asStateFlow()

    private val _confirm = MutableStateFlow<ModelConfirm?>(null)
    val confirm: StateFlow<ModelConfirm?> = _confirm.asStateFlow()

    /** Set by "/model" with no argument: the UI opens the model picker and clears it. */
    private val _openModelPicker = MutableStateFlow(false)
    val openModelPicker: StateFlow<Boolean> = _openModelPicker.asStateFlow()

    private val updater = Updater(app)
    private val _update = MutableStateFlow(UpdateState(installed = updater.installedVersion))
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** One-shot user-facing messages (snackbar). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var openJob: Job? = null
    private var suggestJob: Job? = null
    private var activeJob: Job? = null
    private var sessionsRefreshJob: Job? = null

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { onForeground(); startHeartbeat(); startActivePolling() }
        override fun onStop(owner: LifecycleOwner) {
            heartbeatJob?.cancel()
            activeJob?.cancel()
            // A turn still running: keep the process (and the socket) up so its end or its
            // question can be notified. The service stops by itself once nothing runs.
            if (_chat.value.busy || LiveLink.busy.value.isNotEmpty()) WatchService.start(app)
        }
    }

    private var heartbeatJob: Job? = null

    /**
     * While the app is on screen, notice a dead link (PC left the network, stopped trusting it,
     * Wi-Fi dropped without Android reporting a new network) and move to whatever still works.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(20_000)
                val c = client ?: continue
                if (_conn.value.searching) continue
                if (gateway?.state?.value == GatewayState.OPEN) {
                    // On Tailscale: re-ask the bridge which LAN addresses it serves now (the PC may
                    // have rejoined a trusted network); discoverEndpoints then moves us to the LAN.
                    if (_conn.value.transport == Transport.TAILNET) discoverEndpoints()
                    continue
                }
                if (!c.reachable() && !c.reachable()) reconnect()
            }
        }
    }

    /** The sidebar's "working" shimmer: what Hermes is running right now, for every session. */
    private fun startActivePolling() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            while (true) {
                refreshActive()
                delay(4_000)
            }
        }
    }

    private suspend fun refreshActive() {
        val g = gateway ?: return
        if (g.state.value != GatewayState.OPEN) return
        val rows = runCatching { g.call("session.active_list", JSONObject(), 8_000) }.getOrNull() ?: return
        val live = rows.optJSONArray("sessions").objects().mapNotNull { r ->
            val status = r.str("status") ?: return@mapNotNull null
            val key = r.str("session_key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (status == "idle") null else key to status
        }.toMap()
        if (live != _sessions.value.live) _sessions.update { it.copy(live = live) }
    }

    private val discovery = LanDiscovery(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var networkJob: Job? = null
    private var lastNetwork: android.net.Network? = null

    /**
     * Wi-Fi joined/left, mobile data took over, VPN up/down: re-pick the address right away,
     * also while the app is open (not just when it comes back to the foreground).
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = networkChanged(network)
        override fun onLost(network: android.net.Network) = networkChanged(null)
        override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
            if (network != lastNetwork) networkChanged(network)
        }
    }

    private fun networkChanged(network: android.net.Network?) {
        if (network == lastNetwork) return
        val first = lastNetwork == null && network != null && networkJob == null
        lastNetwork = network
        if (first || network == null) return  // registration callback / offline: nothing to pick
        networkJob?.cancel()
        networkJob = viewModelScope.launch {
            delay(1500)  // let DHCP/routes settle; Android fires several callbacks per change
            if (_pairing.value != null && !_conn.value.searching) reconnect()
        }
    }

    init {
        refreshPcs()
        _pairing.value?.let { start(it) }
        checkForUpdate(quiet = true)
        viewModelScope.launch {
            UpdateEvents.failure.collect { msg ->
                if (msg != null) {
                    _update.update { it.copy(progress = null, error = msg) }
                    _toast.value = msg
                    UpdateEvents.failure.value = null
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        // LiveLink stays: the notification service may still need the socket.
    }

    /** Re-pick the best address: in AUTO the LAN if reachable, else Tailscale. */
    private fun reconnect() {
        val st = _conn.value
        when {
            st.mode == TransportMode.AUTO -> switchTo(null)
            st.mode == TransportMode.LAN -> switchTo(Transport.LAN)
            else -> switchTo(Transport.TAILNET)
        }
    }

    private fun start(p: Pairing) {
        bridgeAddresses = store.bridgeAddresses
        val savedMode = runCatching { TransportMode.valueOf(store.transportMode.uppercase()) }.getOrDefault(TransportMode.AUTO)
        _conn.update { it.copy(activeUrl = p.url, mode = savedMode, needsRepairForLan = p.pin == null,
            transport = EndpointResolver.transportOf(EndpointResolver.hostOf(p.url).orEmpty())) }
        useClient(BridgeClient(p))
        store.lastSessionId?.let { openSession(it) }
        refreshSessions()
        refreshStatus()
        discoverEndpoints()
    }

    /** Point the app (REST and the live socket) at [c]. */
    private fun useClient(c: BridgeClient) {
        client = c
        val g = LiveLink.connect(app, c)
        if (g === gateway && gatewayJobs.all { it.isActive }) return
        gatewayJobs.forEach { it.cancel() }
        gateway = g
        gatewayJobs = listOf(
            viewModelScope.launch { g.incoming.collect(::onIncoming) },
            viewModelScope.launch {
                g.state.collect { s ->
                    _conn.update { it.copy(live = s == GatewayState.OPEN, liveError = if (s == GatewayState.OPEN) null else g.lastError) }
                    _chat.update { it.copy(link = when (s) {
                        GatewayState.OPEN -> Link.LIVE
                        GatewayState.CONNECTING, GatewayState.RECONNECTING -> Link.RECONNECTING
                        else -> Link.IDLE
                    }) }
                    if (s == GatewayState.UNAUTHORIZED) _toast.value = g.lastError
                }
            },
        )
    }

    /** Ask the bridge what it answers on, so the app can offer LAN/Tailscale without re-pairing. */
    private fun discoverEndpoints() {
        val c = client ?: return
        viewModelScope.launch {
            val me = runCatching { c.me() }.getOrNull()
            val addresses: Map<String, List<String>>? = me?.optJSONObject("addresses")?.let { obj ->
                mapOf("lan" to obj.optJSONArray("lan").strings(), "tailnet" to obj.optJSONArray("tailnet").strings())
            }
            if (addresses == null) {
                // The saved address is dead (e.g. paired at home, now outside): try the rest.
                if (me == null && !_conn.value.searching) reconnect()
                return@launch
            }
            if (me.optInt("protocol", 0) < MIN_BRIDGE_PROTOCOL) {
                _toast.value = "Your PC runs an older bridge. Update it: run install.sh on the PC."
            }
            me.str("pc_name")?.takeIf { it.isNotBlank() }?.let { name ->
                val cur = profiles.active()
                if (cur.name.isBlank()) { profiles.rename(cur.id, name); refreshPcs() }
            }
            me.optJSONObject("network")?.let { n ->
                _conn.update { it.copy(pcNetwork = n.str("name"), pcNetworkTrusted = n.optBoolean("trusted")) }
            }
            bridgeAddresses = addresses
            store.bridgeAddresses = addresses
            _conn.update { st ->
                st.copy(lanAvailable = addresses["lan"].orEmpty().isNotEmpty(), tailnetAvailable = addresses["tailnet"].orEmpty().isNotEmpty())
            }
            preferLanIfBack()
        }
    }

    /** In AUTO, on Tailscale while the LAN answers again (came home): move to the LAN. */
    private suspend fun preferLanIfBack() {
        val st = _conn.value
        if (st.mode != TransportMode.AUTO || st.transport != Transport.TAILNET || st.searching) return
        val p = _pairing.value ?: return
        if (p.pin == null) return
        val lan = EndpointResolver.resolve(p.url, bridgeAddresses, TransportMode.LAN)
            .firstOrNull { it.transport == Transport.LAN } ?: return
        if (BridgeClient(p.copy(url = lan.url)).reachable()) switchTo(null)
    }

    /** Manual override: try only this transport, and remember the choice. */
    fun useTransport(transport: Transport) {
        _pairing.value ?: return
        val mode = when (transport) {
            Transport.LAN -> TransportMode.LAN
            Transport.TAILNET -> TransportMode.TAILNET
        }
        _conn.update { it.copy(mode = mode) }
        store.transportMode = mode.name.lowercase()
        switchTo(transport)
    }

    fun useAutoTransport() {
        _conn.update { it.copy(mode = TransportMode.AUTO) }
        store.transportMode = "auto"
        switchTo(null)
    }

    /** Try each candidate address in order; the first that answers wins. */
    private fun switchTo(force: Transport?) {
        val p = _pairing.value ?: return
        _conn.update { it.copy(searching = true) }
        viewModelScope.launch {
            var last: Throwable? = null
            suspend fun tryAll(candidates: List<Endpoint>): Boolean {
                for (ep in candidates) {
                    // A LAN address without the certificate pin is never used (token would leak).
                    if (ep.transport == Transport.LAN && p.pin == null) continue
                    val candidate = p.copy(url = ep.url)
                    try {
                        if (!BridgeClient(candidate).reachable()) { last = IOException("${ep.label} is not reachable"); continue }
                        _conn.update { it.copy(activeUrl = ep.url, transport = ep.transport, searching = false) }
                        if (_pairing.value?.url != ep.url) _pairing.update { it?.copy(url = ep.url) }
                        store.save(clientPairing())
                        useClient(BridgeClient(candidate))
                        refreshSessions()
                        refreshStatus()
                        discoverEndpoints()
                        return true
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        last = t
                    }
                }
                return false
            }

            val mode = _conn.value.mode
            val wantLan = force != Transport.TAILNET && mode != TransportMode.TAILNET
            val known = EndpointResolver.resolve(p.url, bridgeAddresses, mode, force)
            val lanFirst = known.filter { it.transport == Transport.LAN }
            if (wantLan && tryAll(lanFirst)) return@launch
            // Known LAN addresses failed: the PC may have a new IP. Ask the network (mDNS).
            if (wantLan && p.pin != null) {
                val found = discovery.find(p.pin).filter { h -> lanFirst.none { it.host == h } }
                if (found.isNotEmpty()) {
                    val port = EndpointResolver.portOf(p.url)
                    if (tryAll(found.map { Endpoint(Transport.LAN, it, port) })) return@launch
                }
            }
            if (tryAll(known.filter { it.transport == Transport.TAILNET })) return@launch
            _conn.update { it.copy(searching = false) }
            last?.let { say(it) }
        }
    }

    private fun clientPairing(): Pairing {
        val p = _pairing.value!!
        val url = _conn.value.activeUrl ?: p.url
        return if (url == p.url) p else p.copy(url = url)
    }

    fun consumeToast() { _toast.value = null }

    private fun say(t: Throwable) {
        if (t is CancellationException) throw t
        _toast.value = describe(t)
    }

    private fun describe(t: Throwable): String = when (t) {
        is BridgeException -> when (t.code) {
            "unauthorized" -> "This device was revoked or the token is wrong. Pair again."
            "forbidden_peer" -> "Bridge refused this Tailscale identity."
            "forbidden_network" -> "The PC doesn't serve this network. On a network you trust, run `hermes-remote-bridge trust` on the PC."
            "hermes_unavailable" -> "Hermes isn't running on the PC."
            else -> t.message ?: t.code
        }
        is RpcException -> when (t.code) {
            4009 -> "Wait for the current reply to finish first."
            4023 -> "That chat is open somewhere; close it there first."
            else -> t.message ?: "Hermes refused that (${t.code})."
        }
        is javax.net.ssl.SSLException -> "That address isn't your PC (certificate mismatch). Nothing was sent."
        is IOException -> t.message?.takeIf { it.isNotBlank() && !it.startsWith("closed") }
            ?: "Can't reach the PC on this network, and Tailscale isn't answering either."
        else -> t.message ?: t.javaClass.simpleName
    }

    // ------------------------------------------------------------ pairing

    suspend fun pair(p: Pairing): String? {
        // Try every address in the code in order (LAN first); whichever answers is used, and
        // the rest become fallbacks so the phone still connects after changing networks.
        var lastError: Throwable? = null
        for (url in listOf(p.url) + p.alternates) {
            val candidate = p.copy(url = url)
            try {
                BridgeClient(candidate).me()
                if (_pairing.value != null) {
                    // Another PC while one is paired: its own profile. The same PC paired again
                    // (same certificate, or same address) replaces its old pairing instead.
                    val same = profiles.list().firstOrNull { pr ->
                        val old = CredentialStore(app, ProfileStore.pairingFile(pr.id)).load() ?: return@firstOrNull false
                        if (p.pin != null) old.pin == p.pin else old.url == p.url
                    }
                    teardown()
                    activate((same ?: profiles.add()).id)
                }
                _addingPc.value = false
                store.save(candidate)
                val known = (listOf(p.url) + p.alternates).groupBy { u ->
                    if (Tailnet.isLanHost(EndpointResolver.hostOf(u).orEmpty())) "lan" else "tailnet"
                }.mapValues { (_, us) -> us.mapNotNull { EndpointResolver.hostOf(it) } }
                store.bridgeAddresses = known
                _pairing.value = candidate
                refreshPcs()
                start(candidate)
                return null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
            }
        }
        return lastError?.let(::describe)
    }

    /** Forget the PC this app is on now, and move to the next paired one (if any). */
    fun unpair() {
        teardown()
        store.clear()
        drafts.clearAll()
        val gone = profiles.activeId
        // The legacy slot keeps its file names, so it stays in the list as the empty fallback.
        val next = profiles.remove(gone)
        activate(next)
        _pairing.value = store.load()
        refreshPcs()
        _pairing.value?.let { start(it) }
    }

    /** Talk to another paired PC. Its chats, drafts and addresses come back as they were left. */
    fun switchPc(id: String) {
        if (id == profiles.activeId && _pairing.value != null) return
        if (profiles.list().none { it.id == id }) return
        teardown()
        activate(id)
        _pairing.value = store.load()
        refreshPcs()
        _pairing.value?.let { start(it) }
    }

    /** Forget any paired PC; forgetting the one in use is [unpair]. */
    fun forgetPc(id: String) {
        if (id == profiles.activeId) return unpair()
        CredentialStore(app, ProfileStore.pairingFile(id)).clear()
        DraftStore(app, ProfileStore.draftsFile(id)).clearAll()
        profiles.remove(id)
        refreshPcs()
    }

    fun renamePc(id: String, name: String) {
        if (name.isBlank()) return
        profiles.rename(id, name)
        refreshPcs()
    }

    fun startAddingPc() { _addingPc.value = true }
    fun cancelAddingPc() { _addingPc.value = false }

    private fun activate(id: String) {
        profiles.activeId = id
        store = CredentialStore(app, ProfileStore.pairingFile(id))
        drafts = DraftStore(app, ProfileStore.draftsFile(id))
        _reasoning.value = store.reasoningEffort
        _chat.value = ChatState(draft = drafts.get(null), reasoning = store.reasoningEffort)
    }

    /** Drop every link and screen state of the current PC (its saved files stay). */
    private fun teardown() {
        openJob?.cancel()
        gatewayJobs.forEach { it.cancel() }
        gatewayJobs = emptyList()
        LiveLink.close()
        gateway = null
        client = null
        bridgeAddresses = null
        _pairing.value = null
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        _system.value = SystemState()
        _models.value = null
        _modelChoice.value = null
        _confirm.value = null
        _conn.value = ConnectionState()
    }

    private fun refreshPcs() {
        val active = profiles.activeId
        _pcs.value = profiles.list().mapNotNull { pr ->
            val saved = if (pr.id == active) _pairing.value ?: store.load()
                else CredentialStore(app, ProfileStore.pairingFile(pr.id)).load()
            saved ?: return@mapNotNull null
            PairedPc(pr.id, pr.name.ifBlank { EndpointResolver.hostOf(saved.url) ?: "PC" }, saved.url, pr.id == active)
        }
    }

    // ------------------------------------------------------------ app lifecycle

    /** Back on screen: the socket may have died while the process was frozen. Catch up. */
    fun onForeground() {
        client ?: return
        gateway?.nudge()
        discoverEndpoints()
        refreshSessions()
        refreshStatus()
        val sid = _chat.value.sessionId
        if (sid != null && gateway?.state?.value == GatewayState.OPEN) viewModelScope.launch { resume(sid) }
    }

    // ------------------------------------------------------------ the live socket

    private val pendingEvents = ArrayList<Incoming.Event>()
    private var flushJob: Job? = null

    private fun onIncoming(inc: Incoming) {
        when (inc) {
            is Incoming.Open -> {
                // Fresh socket: re-attach the open chat (its runtime may be new) and catch up.
                _chat.value.sessionId?.let { sid -> viewModelScope.launch { resume(sid) } }
                viewModelScope.launch { refreshActive() }
            }
            is Incoming.Event -> {
                if (inc.type == "sessions.changed") return scheduleSessionsRefresh()
                if (inc.sessionId.isEmpty() || inc.sessionId != _chat.value.runtimeId) return
                pendingEvents += inc
                // Streamed text is applied once per frame-ish (32 ms), not per token: recomposing
                // the whole list per token is what made text stutter. Anything else flushes now.
                if (inc.type in STREAM_EVENTS) {
                    if (flushJob == null) flushJob = viewModelScope.launch { delay(32); flushJob = null; flushEvents() }
                } else {
                    flushJob?.cancel(); flushJob = null
                    flushEvents()
                }
            }
            is Incoming.Request -> {
                val rid = inc.params.str("session_id")
                if (rid == null || rid != _chat.value.runtimeId) return
                flushEvents()
                when (inc.method) {
                    "approval" -> _chat.update { it.copy(items = LiveReducer.withApproval(it.items, parseApproval(inc.id, inc.params)), status = "waiting") }
                    "clarify" -> _chat.update { it.copy(items = LiveReducer.withClarify(it.items, parseClarify(inc.id, inc.params)), status = "waiting") }
                    // Secrets, sudo passwords and vault prompts are answered on the PC, never typed on a phone.
                    else -> _chat.update { it.copy(items = it.items + ChatItem.Notice("ask-${inc.id}",
                        "Hermes is asking for something only the PC can answer (${inc.method}). Answer it in the desktop app.")) }
                }
            }
        }
    }

    private fun flushEvents() {
        if (pendingEvents.isEmpty()) return
        val batch = pendingEvents.toList()
        pendingEvents.clear()
        var finished = false
        _chat.update { st ->
            var items = st.items
            var status = st.status
            var title = st.title
            var model = st.model
            var provider = st.provider
            var reasoning = st.reasoning
            for (ev in batch) {
                val key = "${ev.seq ?: System.nanoTime()}"
                when (ev.type) {
                    "message.start" -> status = "working"
                    "message.complete" -> { status = "idle"; finished = true }
                    "request.cancel" -> {
                        val id = ev.payload.str("id")
                        if (id != null) {
                            val why = ev.payload.str("reason").orEmpty()
                            items = LiveReducer.resolve(items, id, if ("elsewhere" in why || "answered" in why) "answered on another screen" else "withdrawn")
                            LiveLink.answered(app, id)
                        }
                        if (status == "waiting") status = "working"
                    }
                    "session.title" -> ev.payload.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                    "session.info" -> {
                        ev.payload.str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                        ev.payload.str("provider")?.takeIf { it.isNotBlank() }?.let { provider = it }
                        ev.payload.str("reasoning_effort")?.let { reasoning = it.ifBlank { null } }
                        ev.payload.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                        if (ev.payload.has("running")) {
                            val running = ev.payload.optBoolean("running")
                            if (running && status == "idle") status = "working"
                        }
                    }
                }
                items = LiveReducer.apply(items, ev.type, ev.payload, key)
            }
            st.copy(items = items, status = status, title = title, model = model, provider = provider, reasoning = reasoning)
        }
        _reasoning.value = _chat.value.reasoning ?: _reasoning.value
        if (finished) { scheduleSessionsRefresh(); viewModelScope.launch { refreshActive() } }
    }

    private fun scheduleSessionsRefresh() {
        sessionsRefreshJob?.cancel()
        sessionsRefreshJob = viewModelScope.launch { delay(600); refreshSessions(quiet = true) }
    }

    /**
     * Attach to [storedId] on the shared socket and show it as it is right now: the transcript,
     * the turn in flight (if the desktop or the phone is running one) and any open question.
     * Hermes then streams every further event of this session to us, wherever it was started.
     */
    private suspend fun resume(storedId: String): Boolean {
        val g = gateway ?: return false
        return try {
            val r = g.call("session.resume", JSONObject().put("session_id", storedId).put("cols", 96), 60_000)
            if (_chat.value.sessionId != storedId) return false
            val rid = r.getString("session_id")
            val info = r.optJSONObject("info") ?: JSONObject()
            val running = r.optBoolean("running") || info.optBoolean("running")
            var items = HistoryMapper.map(r.optJSONArray("messages"))
            if (running) items = withInflight(items, r.optJSONObject("inflight"))
            var waiting = false
            r.optJSONArray("open_requests").objects().forEach { q ->
                val id = q.str("id") ?: return@forEach
                val params = q.optJSONObject("params") ?: JSONObject()
                when (q.str("method")) {
                    "approval" -> { items = LiveReducer.withApproval(items, parseApproval(id, params)); waiting = true }
                    "clarify" -> { items = LiveReducer.withClarify(items, parseClarify(id, params)); waiting = true }
                }
            }
            val title = info.str("title")?.takeIf { it.isNotBlank() }
            LiveLink.watch(rid, storedId, title ?: _chat.value.title)
            pendingEvents.clear()
            _chat.update { st ->
                st.copy(runtimeId = rid, items = reuseKeys(st.items, items), loading = false,
                    status = when { waiting -> "waiting"; running -> "working"; else -> "idle" },
                    title = title ?: st.title, model = info.str("model") ?: st.model, provider = info.str("provider") ?: st.provider,
                    reasoning = info.str("reasoning_effort")?.ifBlank { null } ?: st.reasoning)
            }
            _chat.value.reasoning?.let { _reasoning.value = it }
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _chat.update { it.copy(loading = false) }
            if (t is RpcException && _chat.value.sessionId == storedId) {
                // Deleted elsewhere: forget it instead of retrying forever.
                store.lastSessionId = null
                drafts.clear(storedId)
                _chat.value = ChatState(draft = drafts.get(null))
            }
            say(t)
            false
        }
    }

    /** The runtime id to send to, attaching (or creating the chat) first when needed. */
    private suspend fun ensureLive(firstText: String?): String {
        val g = gateway ?: throw IOException("Not connected to your PC")
        _chat.value.runtimeId?.let { return it }
        _chat.value.sessionId?.let { sid ->
            if (resume(sid)) _chat.value.runtimeId?.let { return it }
            throw IOException("Couldn't open this chat on the PC")
        }
        val choice = _modelChoice.value
        val params = JSONObject().put("source", "desktop").put("cols", 96).apply {
            choice?.let { put("model", it.id).put("provider", it.provider) }
            _reasoning.value?.let { put("reasoning_effort", it) }
        }
        val r = g.call("session.create", params, 60_000)
        val rid = r.getString("session_id")
        val stored = r.str("stored_session_id") ?: rid
        val info = r.optJSONObject("info") ?: JSONObject()
        store.lastSessionId = stored
        // The draft was keyed to "no session yet"; move it to the real id.
        val carried = _chat.value.draft
        if (carried.isNotEmpty() && carried.trim() != firstText?.trim()) drafts.put(stored, carried)
        drafts.clear(null)
        LiveLink.watch(rid, stored, null)
        _chat.update { it.copy(sessionId = stored, runtimeId = rid, model = info.str("model") ?: it.model,
            provider = info.str("provider") ?: it.provider) }
        _modelChoice.value = null
        scheduleSessionsRefresh()
        return rid
    }

    /** One RPC on the open chat; a runtime Hermes reaped meanwhile is re-attached once. */
    private suspend fun rpc(method: String, params: (String) -> JSONObject, timeoutMs: Long = 30_000): JSONObject {
        val g = gateway ?: throw IOException("Not connected to your PC")
        val rid = ensureLive(null)
        return try {
            g.call(method, params(rid), timeoutMs)
        } catch (e: RpcException) {
            if (e.code != 4001) throw e
            _chat.update { it.copy(runtimeId = null) }
            g.call(method, params(ensureLive(null)), timeoutMs)
        }
    }

    // ------------------------------------------------------------ sessions

    fun refreshSessions(quiet: Boolean = false) {
        val c = client ?: return
        if (!quiet) _sessions.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (list, more) = c.sessions(50, 0)
                _sessions.update { it.copy(items = list, loading = false, hasMore = more, error = null) }
                val open = list.firstOrNull { it.id == _chat.value.sessionId }
                if (open != null) _chat.update { it.copy(pinned = open.pinned, title = if (it.title == "New chat") open.displayTitle else it.title) }
                refreshProjects()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _sessions.update { it.copy(loading = false, error = describe(t)) }
            }
        }
    }

    /** Project membership for the drawer. Best effort: older bridges refuse `projects.tree`. */
    private suspend fun refreshProjects() {
        val g = gateway ?: return
        if (g.state.value != GatewayState.OPEN) return
        val tree = runCatching { g.call("projects.tree", JSONObject(), 15_000) }.getOrNull() ?: return
        val projects = tree.optJSONArray("projects").objects().mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            val ids = p.optJSONArray("sessionIds")
            val members = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it)?.takeIf(String::isNotBlank) }.toSet()
            ProjectRef(id, p.str("label") ?: id, members)
        }
        if (projects != _sessions.value.projects) _sessions.update { it.copy(projects = projects) }
    }

    fun loadMoreSessions() {
        val c = client ?: return
        val cur = _sessions.value
        if (cur.loading || !cur.hasMore) return
        _sessions.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (list, more) = c.sessions(50, cur.items.size)
                _sessions.update { s -> s.copy(items = (s.items + list).distinctBy { it.id }, loading = false, hasMore = more) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _sessions.update { it.copy(loading = false, error = describe(t)) }
            }
        }
    }

    fun newChat() {
        openJob?.cancel()
        store.lastSessionId = null
        _chat.value = ChatState(draft = drafts.get(null), link = _chat.value.link, reasoning = store.reasoningEffort)
        _reasoning.value = store.reasoningEffort
    }

    /** Save unsent composer text. Called on every keystroke; cheap enough to be synchronous. */
    fun setDraft(text: String) {
        drafts.put(_chat.value.sessionId, text)
        _chat.update { it.copy(draft = text) }
        updateSuggestions(text)
    }

    fun openSession(id: String) {
        Notifier.clearSession(app, id)
        if (_chat.value.sessionId == id && _chat.value.runtimeId != null) return
        openJob?.cancel()
        store.lastSessionId = id
        val summary = _sessions.value.items.firstOrNull { it.id == id }
        _chat.value = ChatState(sessionId = id, loading = true, draft = drafts.get(id), link = _chat.value.link,
            title = summary?.displayTitle ?: "Loading…", pinned = summary?.pinned ?: false, model = summary?.model)
        openJob = viewModelScope.launch {
            // The socket may still be opening (app start): wait for it rather than failing.
            repeat(40) { if (gateway?.state?.value == GatewayState.OPEN) return@repeat; delay(250) }
            resume(id)
        }
    }

    fun togglePin() {
        val id = _chat.value.sessionId ?: return
        setPinned(id, !_chat.value.pinned)
    }

    /** Pin or unpin any session; updates the list and the open chat optimistically. */
    fun setPinned(id: String, pinned: Boolean) {
        val c = client ?: return
        fun apply(value: Boolean) {
            if (_chat.value.sessionId == id) _chat.update { it.copy(pinned = value) }
            _sessions.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(pinned = value) else it }) }
        }
        apply(pinned)
        viewModelScope.launch {
            try {
                c.setPinned(id, pinned)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                apply(!pinned)
                say(t)
            }
        }
    }

    fun rename(id: String, title: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.renameSession(id, title)
                if (_chat.value.sessionId == id) _chat.update { it.copy(title = title) }
                refreshSessions(quiet = true)
            } catch (t: Throwable) { say(t) }
        }
    }

    fun delete(id: String) {
        val g = gateway ?: return
        viewModelScope.launch {
            try {
                if (_chat.value.sessionId == id) {
                    // Hermes refuses to delete a chat it holds open; let go of ours first.
                    _chat.value.runtimeId?.let { rid -> runCatching { g.call("session.close", JSONObject().put("session_id", rid)) } }
                    newChat()
                }
                g.call("session.delete", JSONObject().put("session_id", id))
                _sessions.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
                drafts.clear(id)
                refreshSessions(quiet = true)
            } catch (t: Throwable) { say(t) }
        }
    }

    // ------------------------------------------------------------ turns

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) return runSlash(trimmed)
        sendMessage(trimmed.removePrefix("/"), trimmed.removePrefix("/"))
    }

    /** Submits [text]; the chat shows [display] (what the user typed) for it. */
    private fun sendMessage(text: String, display: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_chat.value.sending) {
            // Never drop a send silently: the text stays in the composer and the snackbar says why.
            _toast.value = "Still sending your last message…"
            return
        }
        val key = "u-${System.nanoTime()}"
        _suggestions.value = emptyList()
        _chat.update { it.copy(sending = true, draft = if (it.draft.trim() == display.trim()) "" else it.draft,
            items = it.items + ChatItem.User(key, display, pending = true)) }
        drafts.put(_chat.value.sessionId, _chat.value.draft)
        viewModelScope.launch {
            try {
                val res = rpc("prompt.submit", { rid -> JSONObject().put("session_id", rid).put("text", trimmed) }, 30_000)
                val status = res.str("status")
                _chat.update { st ->
                    st.copy(sending = false, status = if (st.status == "idle") "working" else st.status,
                        items = st.items.map { if (it is ChatItem.User && it.key == key) it.copy(pending = false) else it }.let { items ->
                            when (status) {
                                // Folded into the running turn: not a turn of its own.
                                "steered", "redirected" -> items.filterNot { it.key == key } +
                                    ChatItem.Notice("$key-n", (if (status == "steered") "Steer: " else "Redirect: ") + display)
                                "queued" -> items + ChatItem.Notice("$key-n", "Queued: runs after the current reply.")
                                else -> items
                            }
                        })
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // The text goes back to the composer rather than vanishing with the failed bubble.
                _chat.update { st ->
                    st.copy(sending = false, items = st.items.filterNot { it.key == key },
                        draft = if (st.draft.isNotEmpty()) st.draft else display)
                }
                drafts.put(_chat.value.sessionId, _chat.value.draft)
                say(t)
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            try { rpc("session.interrupt", { JSONObject().put("session_id", it) }) } catch (t: Throwable) { say(t) }
        }
    }

    fun steer(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!_chat.value.busy) return send(trimmed)
        _chat.update { it.copy(draft = if (it.draft.trim() == trimmed) "" else it.draft) }
        drafts.put(_chat.value.sessionId, _chat.value.draft)
        viewModelScope.launch {
            try {
                rpc("session.steer", { JSONObject().put("session_id", it).put("text", trimmed) })
                _chat.update { it.copy(items = it.items + ChatItem.Notice("steer-${System.nanoTime()}", "Steer: $trimmed")) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _chat.update { st -> st.copy(draft = st.draft.ifEmpty { trimmed }) }
                say(t)
            }
        }
    }

    /** Answer the open approval: once, session, always or deny. Whichever screen answers first wins. */
    fun answerApproval(choice: String) {
        val g = gateway ?: return
        val open = LiveReducer.openApproval(_chat.value.items) ?: return
        if (!g.reply(open.request.id, JSONObject().put("choice", choice))) return say(IOException("Not connected to your PC"))
        LiveLink.answered(app, open.request.id)
        _chat.update { it.copy(items = LiveReducer.resolve(it.items, open.request.id, choice), status = "working") }
    }

    /** Answer the open clarify question(s). [answers] maps qid → answer for a batch; one entry otherwise. */
    fun answerClarify(answers: List<String>) {
        val g = gateway ?: return
        val open = LiveReducer.openClarify(_chat.value.items) ?: return
        val r = open.request
        val result = if (r.batch) JSONObject().put("answers", JSONObject().apply {
            r.questions.forEachIndexed { i, q -> put(q.qid ?: "q$i", answers.getOrNull(i).orEmpty()) }
        }) else JSONObject().put("answer", answers.firstOrNull().orEmpty())
        if (!g.reply(r.id, result)) return say(IOException("Not connected to your PC"))
        LiveLink.answered(app, r.id)
        _chat.update { it.copy(items = LiveReducer.resolve(it.items, r.id, answers.joinToString(" · ").ifBlank { "(skipped)" }), status = "working") }
    }

    // ------------------------------------------------------------ models + reasoning

    fun loadModels() {
        val g = gateway ?: return
        viewModelScope.launch {
            try {
                val params = JSONObject().apply { _chat.value.runtimeId?.let { put("session_id", it) } }
                _models.value = parseCatalog(g.call("model.options", params, 30_000))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(t)
            }
        }
    }

    /** The model shown in the composer: the open chat's own, or the pick for the next new chat. */
    fun shownModel(catalog: ModelCatalog?, st: ChatState): ModelOption? {
        if (st.sessionId == null) return _modelChoice.value
        val m = st.model ?: return null
        return catalog?.options?.firstOrNull { it.id == m && (st.provider == null || it.provider == st.provider) }
            ?: ModelOption(st.provider.orEmpty(), st.provider.orEmpty(), m, m.substringAfterLast('/'), true)
    }

    /** Switch model. For an open chat the switch is Hermes' own (`config.set model … --session`). */
    fun chooseModel(option: ModelOption?, confirmed: Boolean = false) {
        _confirm.value = null
        if (_chat.value.sessionId == null) { _modelChoice.value = option; return }
        option ?: return
        viewModelScope.launch {
            try {
                val res = rpc("config.set", { rid ->
                    JSONObject().put("session_id", rid).put("key", "model").put("value", "${option.id} --provider ${option.provider} --session")
                        .apply { if (confirmed) put("confirm_expensive_model", true) }
                })
                if (res.optBoolean("confirm_required")) {
                    _confirm.value = ModelConfirm(option, res.str("confirm_message") ?: "This model is expensive. Switch anyway?")
                    return@launch
                }
                _chat.update { it.copy(model = option.id, provider = option.provider) }
                res.str("warning")?.takeIf { it.isNotBlank() }?.let { _toast.value = it }
                if (res.optBoolean("deferred")) _toast.value = "Switches after the current reply."
            } catch (t: Throwable) { say(t) }
        }
    }

    fun dismissConfirm() { _confirm.value = null }

    fun setReasoning(effort: String?) {
        if (_chat.value.sessionId == null) {
            _reasoning.value = effort
            store.reasoningEffort = effort
            return
        }
        val level = effort ?: _models.value?.reasoningDefault ?: "medium"
        viewModelScope.launch {
            try {
                rpc("config.set", { JSONObject().put("session_id", it).put("key", "reasoning").put("value", level) })
                _reasoning.value = level
                _chat.update { it.copy(reasoning = level) }
            } catch (t: Throwable) { say(t) }
        }
    }

    fun modelPickerOpened() { _openModelPicker.value = false }

    // ------------------------------------------------------------ slash commands

    /** Suggestions come from Hermes' own completer, so skills and plugins appear like on the desktop. */
    private fun updateSuggestions(text: String) {
        suggestJob?.cancel()
        if (!text.startsWith("/") || text.contains('\n') || text.contains(' ')) {
            if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
            return
        }
        val g = gateway ?: return
        suggestJob = viewModelScope.launch {
            delay(90)
            val params = JSONObject().put("text", text).apply { _chat.value.runtimeId?.let { put("session_id", it) } }
            val res = runCatching { g.call("complete.slash", params, 8_000) }.getOrNull() ?: return@launch
            _suggestions.value = parseSuggestions(res).take(40)
        }
    }

    private fun note(command: String, text: String) {
        _chat.update { it.copy(items = it.items + ChatItem.CommandOutput("cmd-${System.nanoTime()}", command, text)) }
    }

    private fun runSlash(line: String, depth: Int = 0) {
        _chat.update { it.copy(draft = "") } // the typed command was consumed
        drafts.put(_chat.value.sessionId, "")
        _suggestions.value = emptyList()
        val name = line.drop(1).substringBefore(' ').lowercase()
        val arg = line.substringAfter(' ', "").trim()
        when (name) {
            "new", "reset" -> return newChat()
            "stop" -> return if (_chat.value.busy) stop() else note(line, "Nothing is running.")
            "model" -> if (arg.isEmpty()) { loadModels(); _openModelPicker.value = true; return }
            "reasoning" -> if (arg.isEmpty()) return note(line, "Reasoning effort: ${_chat.value.reasoning ?: _reasoning.value ?: "default"}\n" +
                "Options: ${REASONING_LEVELS.joinToString(", ")}")
            "steer" -> if (arg.isNotEmpty() && _chat.value.busy) return steer(arg)
        }
        if ("/$name" in io.github.nideta231.hermesremote.data.UNAVAILABLE_COMMANDS) return note(line, "/$name works on the PC only.")
        viewModelScope.launch {
            try {
                when (name) {
                    "btw" -> {
                        if (arg.isEmpty()) return@launch note(line, "Usage: /btw <question>")
                        rpc("prompt.btw", { JSONObject().put("session_id", it).put("text", arg) })
                        note(line, "Asking on the side… the answer shows up here.")
                    }
                    "background", "bg" -> {
                        if (arg.isEmpty()) return@launch note(line, "Usage: /background <task>")
                        rpc("prompt.background", { JSONObject().put("session_id", it).put("text", arg) })
                        note(line, "Started in the background; the result shows up here.")
                    }
                    "title" -> {
                        val res = rpc("session.title", { rid -> JSONObject().put("session_id", rid).apply { if (arg.isNotEmpty()) put("title", arg) } })
                        res.str("title")?.takeIf { it.isNotBlank() }?.let { t -> _chat.update { it.copy(title = t) } }
                        note(line, "Title: ${res.str("title") ?: "(none)"}")
                        scheduleSessionsRefresh()
                    }
                    "compress", "compact" -> {
                        note(line, "Compressing…")
                        val res = rpc("session.compress", { rid -> JSONObject().put("session_id", rid).apply { if (arg.isNotEmpty()) put("focus_topic", arg) } }, 300_000)
                        note(line, res.str("message") ?: "Compressed: ${res.optInt("before_messages")} → ${res.optInt("after_messages")} messages.")
                        _chat.value.sessionId?.let { resume(it) }
                    }
                    "branch", "fork" -> {
                        val res = rpc("session.branch", { rid -> JSONObject().put("session_id", rid).apply { if (arg.isNotEmpty()) put("name", arg) } })
                        res.str("stored_session_id")?.let { openSession(it); scheduleSessionsRefresh() }
                    }
                    "status" -> note(line, rpc("session.status", { JSONObject().put("session_id", it) }).str("output") ?: "")
                    "save" -> note(line, rpc("session.save", { JSONObject().put("session_id", it) }).str("file")?.let { "Saved to $it" } ?: "Saved.")
                    else -> {
                        // Hermes' own dispatch, exactly as the desktop does it: the slash worker, or
                        // command.dispatch for skills, /retry, /undo, /queue, /plan and friends.
                        val res = try {
                            rpc("slash.exec", { JSONObject().put("session_id", it).put("command", line) }, 120_000)
                        } catch (e: RpcException) {
                            if (e.code != 4018) throw e
                            rpc("command.dispatch", { JSONObject().put("session_id", it).put("name", name).put("arg", arg) }, 120_000)
                        }
                        when (val d = parseDispatch(res)) {
                            is Dispatch.Output -> {
                                note(line, d.text)
                                if (name in RELOADING_COMMANDS) _chat.value.sessionId?.let { resume(it) }
                            }
                            is Dispatch.Alias -> if (depth < 3) runSlash("/" + d.target.removePrefix("/") + if (arg.isNotEmpty()) " $arg" else "", depth + 1)
                            is Dispatch.Send -> {
                                d.notice?.let { note(line, it) }
                                // /retry and /undo rewound the transcript on the PC: show that first.
                                if (name in RELOADING_COMMANDS) _chat.value.sessionId?.let { resume(it) }
                                sendMessage(d.message, d.display ?: if (name == "retry") d.message else line)
                            }
                            is Dispatch.Prefill -> {
                                d.notice?.let { note(line, it) }
                                _chat.value.sessionId?.let { resume(it) }
                                setDraft(d.message)
                            }
                            null -> note(line, "(no output)")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is RpcException) note(line, t.message ?: "Failed (${t.code})") else say(t)
            }
        }
    }

    // ------------------------------------------------------------ app updates

    fun checkForUpdate(quiet: Boolean = false) {
        if (_update.value.checking || _update.value.progress != null) return
        _update.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val found = updater.check()
                _update.update { it.copy(available = found, checking = false, checkedAt = System.currentTimeMillis()) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _update.update { it.copy(checking = false, error = if (quiet) null else "Couldn't check for updates: ${t.message}") }
            }
        }
    }

    fun installUpdate() {
        val u = _update.value.available ?: return
        if (_update.value.progress != null) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _toast.value = "Allow installs from Hermes Remote, then tap Update again."
            return
        }
        _update.update { it.copy(progress = 0f, error = null) }
        viewModelScope.launch {
            try {
                updater.install(u) { p -> _update.update { it.copy(progress = p) } }
                _update.update { it.copy(progress = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _update.update { it.copy(progress = null, error = t.message) }
                say(t)
            }
        }
    }

    // ------------------------------------------------------------ system

    fun refreshStatus() {
        val c = client ?: return
        _system.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val comps = c.status()
                val mode = runCatching { c.approvalMode() }.getOrNull()
                _system.update { SystemState(comps, false, null, System.currentTimeMillis(), mode ?: it.approvalMode) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _system.update { it.copy(loading = false, error = describe(t), checkedAt = System.currentTimeMillis()) }
            }
        }
    }

    fun setApprovalMode(mode: String) {
        val c = client ?: return
        if (_system.value.approvalMode == mode || _system.value.approvalSaving) return
        _system.update { it.copy(approvalSaving = true) }
        viewModelScope.launch {
            try {
                val applied = c.setApprovalMode(mode)
                _system.update { it.copy(approvalMode = applied, approvalSaving = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _system.update { it.copy(approvalSaving = false) }
                say(t)
            }
        }
    }

    private companion object {
        /** Bridge protocol this app speaks (the WebSocket relay). */
        const val MIN_BRIDGE_PROTOCOL = 2
        val STREAM_EVENTS = setOf("message.delta", "reasoning.delta", "thinking.delta") // coalesced per frame
        /** Commands that change the transcript on the PC: re-read it after they run. */
        val RELOADING_COMMANDS = setOf("retry", "undo", "rollback", "clear")
    }
}
