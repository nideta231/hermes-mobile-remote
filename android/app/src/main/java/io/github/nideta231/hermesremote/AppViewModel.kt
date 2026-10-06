package io.github.nideta231.hermesremote

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.nideta231.hermesremote.data.AppUpdate
import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.CommandReply
import io.github.nideta231.hermesremote.data.REASONING_LEVELS
import io.github.nideta231.hermesremote.data.SlashCommand
import io.github.nideta231.hermesremote.data.UpdateEvents
import io.github.nideta231.hermesremote.data.Updater
import io.github.nideta231.hermesremote.data.BridgeException
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.ComponentStatus
import io.github.nideta231.hermesremote.data.CredentialStore
import io.github.nideta231.hermesremote.data.DesktopInfo
import io.github.nideta231.hermesremote.data.DraftStore
import io.github.nideta231.hermesremote.data.Endpoint
import io.github.nideta231.hermesremote.data.EndpointResolver
import io.github.nideta231.hermesremote.data.LanDiscovery
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.nideta231.hermesremote.data.Tailnet
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.coalesceDeltas
import io.github.nideta231.hermesremote.data.reuseKeys
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import io.github.nideta231.hermesremote.data.Notifier
import io.github.nideta231.hermesremote.data.WatchService
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.RunSnapshot
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import io.github.nideta231.hermesremote.data.strings
import io.github.nideta231.hermesremote.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

enum class Link { IDLE, LIVE, RECONNECTING }

data class ChatState(
    val sessionId: String? = null,
    val title: String = "New chat",
    val items: List<ChatItem> = emptyList(),
    val run: RunSnapshot? = null,
    val link: Link = Link.IDLE,
    val loading: Boolean = false,
    val sending: Boolean = false,
    /** True while the session is being driven from another surface and we are tailing it. */
    val following: Boolean = false,
    /** New messages arrived from another surface in the last few polls: something is running there. */
    val remoteActive: Boolean = false,
    val pinned: Boolean = false,
    val sessionModel: String? = null,
    /** Unsent composer text for this session; survives tab switches and process death. */
    val draft: String = "",
) {
    val busy: Boolean get() = run != null && !run.terminal
}

data class SessionsState(
    val items: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

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

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)
    private val drafts = DraftStore(app)
    private var bridgeAddresses: Map<String, List<String>>? = null
    private var client: BridgeClient? = null

    private val _pairing = MutableStateFlow(store.load())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    private val _system = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()

    private val _desktop = MutableStateFlow<Result<DesktopInfo>?>(null)
    val desktop: StateFlow<Result<DesktopInfo>?> = _desktop.asStateFlow()

    private val _models = MutableStateFlow<ModelCatalog?>(null)
    val models: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _conn = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _conn.asStateFlow()

    /** Model picked for the next send; null means "whatever the session already uses". */
    private val _modelChoice = MutableStateFlow<ModelOption?>(null)
    val modelChoice: StateFlow<ModelOption?> = _modelChoice.asStateFlow()

    /** Reasoning effort for the next send; null means Hermes' configured default. */
    private val _reasoning = MutableStateFlow(store.reasoningEffort)
    val reasoning: StateFlow<String?> = _reasoning.asStateFlow()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    /** Set by "/model" with no argument: the UI opens the model picker and clears it. */
    private val _openModelPicker = MutableStateFlow(false)
    val openModelPicker: StateFlow<Boolean> = _openModelPicker.asStateFlow()

    private val updater = Updater(app)
    private val _update = MutableStateFlow(UpdateState(installed = updater.installedVersion))
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private var followJob: Job? = null
    private var followCursor = 0

    /** One-shot user-facing messages (snackbar). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var attachJob: Job? = null
    private var lastSeq = 0L

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { onForeground(); startHeartbeat() }
        override fun onStop(owner: LifecycleOwner) { heartbeatJob?.cancel() }
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
                if (_conn.value.searching || attachJob?.isActive == true) continue  // a live stream is its own proof
                if (!c.reachable() && !c.reachable()) reconnect()
                // On Tailscale: re-ask the bridge which LAN addresses it serves now (the PC may
                // have rejoined a trusted network); discoverEndpoints then moves us to the LAN.
                else if (_conn.value.transport == Transport.TAILNET) discoverEndpoints()
            }
        }
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
        client = BridgeClient(p)
        bridgeAddresses = store.bridgeAddresses
        val savedMode = runCatching { TransportMode.valueOf(store.transportMode.uppercase()) }.getOrDefault(TransportMode.AUTO)
        _conn.update { it.copy(activeUrl = p.url, mode = savedMode, needsRepairForLan = p.pin == null,
            transport = EndpointResolver.transportOf(EndpointResolver.hostOf(p.url).orEmpty())) }
        viewModelScope.launch {
            retryPendingSend()
            store.lastSessionId?.let { openSession(it) }
        }
        refreshSessions()
        refreshStatus()
        discoverEndpoints()
    }

    /** Ask the bridge what it answers on, so the app can offer LAN/Tailscale without re-pairing. */
    private fun discoverEndpoints() {
        val c = client ?: return
        viewModelScope.launch {
            val me = runCatching { c.me() }.getOrNull()
            val addresses: Map<String, List<String>>? = me?.optJSONObject("addresses")?.let { obj ->
                mapOf(
                    "lan" to obj.optJSONArray("lan").strings(),
                    "tailnet" to obj.optJSONArray("tailnet").strings(),
                )
            }
            if (addresses == null) {
                // The saved address is dead (e.g. paired at home, now outside): try the rest.
                if (me == null && !_conn.value.searching) reconnect()
                return@launch
            }
            me.optJSONObject("network")?.let { n ->
                _conn.update { it.copy(pcNetwork = n.str("name"), pcNetworkTrusted = n.optBoolean("trusted")) }
            }
            bridgeAddresses = addresses
            store.bridgeAddresses = addresses
            _conn.update { st ->
                st.copy(lanAvailable = addresses["lan"].orEmpty().isNotEmpty(),
                    tailnetAvailable = addresses["tailnet"].orEmpty().isNotEmpty())
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
        val p = _pairing.value ?: return
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
        attachJob?.cancel()
        followJob?.cancel()
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
                        client = BridgeClient(candidate)
                        _conn.update { it.copy(activeUrl = ep.url, transport = ep.transport, searching = false) }
                        if (_pairing.value?.url != ep.url) _pairing.update { it?.copy(url = ep.url) }
                        store.save(clientPairing())
                        retryPendingSend()
                        store.lastSessionId?.let { openSession(it) }
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
            "session_busy" -> "This session is still running."
            else -> t.message ?: t.code
        }
        is javax.net.ssl.SSLException -> "That address isn't your PC (certificate mismatch). Nothing was sent."
        is IOException -> "Can't reach the PC on this network, and Tailscale isn't answering either."
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
                store.save(candidate)
                val known = (listOf(p.url) + p.alternates).groupBy { u ->
                    if (Tailnet.isLanHost(EndpointResolver.hostOf(u).orEmpty())) "lan" else "tailnet"
                }.mapValues { (_, us) -> us.mapNotNull { EndpointResolver.hostOf(it) } }
                store.bridgeAddresses = known
                _pairing.value = candidate
                start(candidate)
                return null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
            }
        }
        return lastError?.let(::describe)
    }

    fun unpair() {
        attachJob?.cancel()
        store.clear()
        client = null
        _pairing.value = null
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        _system.value = SystemState()
        _desktop.value = null
    }

    // ------------------------------------------------------------ app lifecycle

    /** Called when the app returns to the foreground: re-sync whatever may have changed meanwhile. */
    fun onForeground() {
        val c = client ?: return
        viewModelScope.launch { retryPendingSend() }
        // Networks change while the app is closed: re-check which address to use.
        discoverEndpoints()
        val sid = _chat.value.sessionId
        if (sid != null && attachJob?.isActive != true) {
            viewModelScope.launch {
                try {
                    val (_, active) = c.session(sid)
                    if (active != null || _chat.value.busy) reloadSession(sid, active)
                } catch (t: Throwable) { if (t is CancellationException) throw t }
            }
        }
        refreshStatus()
    }

    // ------------------------------------------------------------ sessions

    fun refreshSessions() {
        val c = client ?: return
        _sessions.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (list, more) = c.sessions(50, 0)
                _sessions.value = SessionsState(list, false, more)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _sessions.update { it.copy(loading = false, error = describe(t)) }
            }
        }
    }

    fun loadMoreSessions() {
        val c = client ?: return
        val cur = _sessions.value
        if (cur.loading || !cur.hasMore) return
        _sessions.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (list, more) = c.sessions(50, cur.items.size)
                _sessions.update { s -> SessionsState((s.items + list).distinctBy { it.id }, false, more) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _sessions.update { it.copy(loading = false, error = describe(t)) }
            }
        }
    }

    fun newChat() {
        attachJob?.cancel()
        followJob?.cancel()
        store.lastSessionId = null
        _chat.value = ChatState(draft = drafts.get(null))
    }

    /** Save unsent composer text. Called on every keystroke; cheap enough to be synchronous. */
    fun setDraft(text: String) {
        drafts.put(_chat.value.sessionId, text)
        _chat.update { it.copy(draft = text) }
    }

    fun openSession(id: String) {
        Notifier.clearSession(app, id)
        if (_chat.value.sessionId == id && (attachJob?.isActive == true || followJob?.isActive == true || _chat.value.items.isNotEmpty())) return
        attachJob?.cancel()
        followJob?.cancel()
        store.lastSessionId = id
        _chat.value = ChatState(sessionId = id, loading = true, draft = drafts.get(id))
        viewModelScope.launch { reloadSession(id, null, fetchActive = true) }
    }

    private suspend fun reloadSession(id: String, knownActive: RunSnapshot?, fetchActive: Boolean = true) {
        val c = client ?: return
        try {
            val (summary, active) = if (fetchActive) c.session(id) else (null to knownActive)
            val history = c.messages(id)
            val run = active ?: knownActive
            if (run != null && !run.terminal) {
                // Rebuild the in-flight turn from a full event replay so nothing is lost or doubled.
                val base = HistoryMapper.map(HistoryMapper.withoutLastTurn(history))
                val userText = HistoryMapper.lastUserText(history)
                val items = if (userText != null) base + ChatItem.User("u-${run.runId}", userText) else base
                _chat.update { it.copy(sessionId = id, title = summary?.displayTitle ?: it.title, items = items,
                    run = run, loading = false, pinned = summary?.pinned ?: false,
                    sessionModel = summary?.model, following = false, remoteActive = false) }
                attach(run, fromSeq = 0)
            } else {
                _chat.update { it.copy(sessionId = id, title = summary?.displayTitle ?: it.title,
                    items = HistoryMapper.map(history), run = null, link = Link.IDLE, loading = false,
                    pinned = summary?.pinned ?: false, sessionModel = summary?.model) }
                // The session may be mid-turn on the desktop or a messaging platform. Tail it so the
                // phone follows that conversation instead of showing a frozen snapshot.
                followCursor = history.length()
                startFollowing(id)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _chat.update { it.copy(loading = false) }
            if (t is BridgeException && t.httpCode == 404) {
                store.lastSessionId = null
                drafts.clear(id)
                _chat.value = ChatState()
            }
            say(t)
        }
    }

    /**
     * Poll a session another surface is driving. The bridge's sync route returns only
     * messages appended since our cursor, so nothing is duplicated or skipped; when the
     * bridge itself takes over the session (our own run), polling stops.
     */
    private fun startFollowing(id: String) {
        followJob?.cancel()
        val c = client ?: return
        followJob = viewModelScope.launch {
            _chat.update { it.copy(following = true) }
            var idle = 0
            while (true) {
                delay(if (idle < 3) 2000 else 5000)
                if (_chat.value.sessionId != id || _chat.value.busy) return@launch
                val s = try {
                    c.sync(id, followCursor)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is BridgeException) return@launch // gone or unauthorized: stop polling
                    idle++
                    continue
                }
                if (_chat.value.sessionId != id) return@launch
                followCursor = s.cursor
                if (s.changed && s.messages.isNotEmpty()) {
                    idle = 0
                    val fresh = HistoryMapper.map(org.json.JSONArray().also { a -> s.messages.forEach(a::put) })
                    _chat.update { st -> st.copy(items = st.items + fresh, sessionModel = s.model ?: st.sessionModel, remoteActive = true) }
                } else {
                    idle++
                    if (idle >= 3 && _chat.value.remoteActive) _chat.update { it.copy(remoteActive = false) }
                }
            }
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

    fun loadModels() {
        val c = client ?: return
        if (_models.value != null) return
        viewModelScope.launch {
            try {
                _models.value = c.models()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(t)
            }
        }
    }

    fun chooseModel(option: ModelOption?) {
        _modelChoice.value = option
    }

    fun rename(id: String, title: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.renameSession(id, title)
                if (_chat.value.sessionId == id) _chat.update { it.copy(title = title) }
                refreshSessions()
            } catch (t: Throwable) { say(t) }
        }
    }

    fun delete(id: String) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.deleteSession(id)
                _sessions.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
                drafts.clear(id)
                if (_chat.value.sessionId == id) newChat()
                refreshSessions()
            } catch (t: Throwable) { say(t) }
        }
    }

    // ------------------------------------------------------------ runs

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) return runSlash(trimmed)
        sendMessage(trimmed.removePrefix("/"), trimmed.removePrefix("/"))
    }

    /** Starts a run with [text]; the chat shows [display] (what the user typed) for it. */
    private fun sendMessage(text: String, display: String) {
        val c = client ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _chat.value.busy || _chat.value.sending) return
        val requestId = UUID.randomUUID().toString().replace("-", "")
        _chat.update { it.copy(sending = true, items = it.items + ChatItem.User("u-$requestId", display, pending = true)) }
        viewModelScope.launch {
            try {
                val sid = _chat.value.sessionId ?: c.createSession(display.lineSequence().first().take(60)).also { s ->
                    store.lastSessionId = s.id
                    // The draft was keyed to "no session yet"; move it to the real id so it comes
                    // back if the user switches tabs mid-turn.
                    val carried = _chat.value.draft
                    if (carried.isNotEmpty() && carried != display) drafts.put(s.id, carried)
                    drafts.clear(null)
                    _chat.update { it.copy(sessionId = s.id, title = s.displayTitle) }
                }.id
                store.pendingSend = Triple(requestId, sid, trimmed)
                followJob?.cancel() // we own this session now; stop tailing the other surface
                val run = startWithRetry(c, sid, trimmed, requestId)
                store.pendingSend = null
                _chat.update { st ->
                    st.copy(sending = false, run = run, following = false, remoteActive = false,
                        items = st.items.map { if (it is ChatItem.User && it.key == "u-$requestId") it.copy(pending = false) else it })
                }
                attach(run, fromSeq = 0)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Retryable failures keep pendingSend so the next foreground resends with the same id.
                if (t !is IOException) store.pendingSend = null
                _chat.update { st -> st.copy(sending = false, items = st.items.filterNot { it.key == "u-$requestId" }) }
                say(t)
            }
        }
    }

    private suspend fun startWithRetry(c: BridgeClient, sid: String, text: String, requestId: String): RunSnapshot {
        val choice = _modelChoice.value
        repeat(5) {
            try {
                return c.startRun(sid, text, requestId, choice?.id, choice?.provider, _reasoning.value)
            } catch (e: IOException) {
                delay(700)
            }
        }
        return c.startRun(sid, text, requestId, choice?.id, choice?.provider, _reasoning.value)
    }

    private suspend fun retryPendingSend() {
        val c = client ?: return
        val (requestId, sid, text) = store.pendingSend ?: return
        try {
            val run = c.startRun(sid, text, requestId) // dedup: returns the existing run if it was accepted
            store.pendingSend = null
            if (_chat.value.sessionId == sid || _chat.value.sessionId == null) {
                store.lastSessionId = sid
                _chat.update { it.copy(sessionId = sid) }
                reloadSession(sid, run)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t !is IOException) store.pendingSend = null
        }
    }

    private fun attach(run: RunSnapshot, fromSeq: Long) {
        val c = client ?: return
        attachJob?.cancel()
        // The service, not this stream, decides when to notify: this stream also keeps running
        // in the background and would otherwise always see the end first. Idempotent per run.
        if (!run.terminal) WatchService.start(app, run.runId, run.sessionId ?: _chat.value.sessionId, _chat.value.title)
        lastSeq = fromSeq
        attachJob = viewModelScope.launch {
            var backoff = 1000L
            var finished = false
            while (!finished) {
                try {
                    _chat.update { it.copy(link = Link.LIVE) }
                    c.events(run.runId, lastSeq).coalesceDeltas().collect { ev ->
                        if (ev.id <= lastSeq) return@collect // never apply an event twice
                        lastSeq = ev.id
                        backoff = 1000L
                        if (ev.name == "bridge.resync") return@collect
                        _chat.update { st ->
                            val status = when {
                                ev.name.startsWith("run.") && ev.name.removePrefix("run.") in RunSnapshot.TERMINAL_STATUSES ->
                                    ev.name.removePrefix("run.")
                                ev.name == "approval.request" -> "waiting_for_approval"
                                ev.name == "approval.responded" -> "running"
                                else -> st.run?.status ?: "running"
                            }
                            st.copy(items = LiveReducer.apply(st.items, ev), run = st.run?.copy(status = status, lastSeq = ev.id))
                        }
                    }
                    // Stream ended cleanly: the bridge closes it right after the terminal event.
                    val snap = c.run(run.runId)
                    if (snap.terminal) finished = true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is BridgeException && t.httpCode == 404) { finished = true; break }
                    _chat.update { it.copy(link = Link.RECONNECTING) }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000)
                }
            }
            // The persisted history is the source of truth once the run settles.
            val sid = run.sessionId ?: _chat.value.sessionId
            _chat.update { it.copy(link = Link.IDLE, run = it.run?.let { r -> if (r.terminal) r else r.copy(status = "completed") }) }
            if (sid != null && _chat.value.sessionId == sid) {
                try {
                    val history = c.messages(sid)
                    _chat.update { st ->
                        val notice = st.items.lastOrNull() as? ChatItem.Notice
                        val mapped = reuseKeys(st.items, HistoryMapper.map(history))
                        st.copy(items = if (notice != null) mapped + notice else mapped, run = null)
                    }
                } catch (t: Throwable) { if (t is CancellationException) throw t }
            }
            refreshSessions()
        }
    }

    fun stop() {
        val c = client ?: return
        val run = _chat.value.run ?: return
        viewModelScope.launch {
            try { c.stop(run.runId); _chat.update { it.copy(run = it.run?.copy(status = "stopping")) } } catch (t: Throwable) { say(t) }
        }
    }

    fun steer(text: String) {
        val c = client ?: return
        val run = _chat.value.run ?: return
        viewModelScope.launch {
            try {
                c.steer(run.runId, text.trim())
                _chat.update { it.copy(items = it.items + ChatItem.Notice("steer-${System.nanoTime()}", "Steer: ${text.trim()}")) }
            } catch (t: Throwable) { say(t) }
        }
    }

    fun answerApproval(choice: String) {
        val c = client ?: return
        val run = _chat.value.run ?: return
        val pending = _chat.value.items.lastOrNull { it is ChatItem.Approval && it.decided == null } as? ChatItem.Approval
        viewModelScope.launch {
            try { c.approve(run.runId, choice, pending?.request?.requestId) } catch (t: Throwable) { say(t) }
        }
    }

    // ------------------------------------------------------------ slash commands + reasoning

    fun loadCommands() {
        val c = client ?: return
        if (_commands.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { c.commands() }.onSuccess { _commands.value = it }
        }
    }

    fun setReasoning(effort: String?) {
        _reasoning.value = effort
        store.reasoningEffort = effort
    }

    fun modelPickerOpened() { _openModelPicker.value = false }

    private fun note(command: String, text: String) {
        _chat.update { it.copy(items = it.items + ChatItem.CommandOutput("cmd-${System.nanoTime()}", command, text)) }
    }

    private fun runSlash(line: String) {
        val name = line.drop(1).substringBefore(' ').lowercase()
        val arg = line.substringAfter(' ', "").trim()
        when (name) {
            "new" -> return newChat()
            "stop" -> return if (_chat.value.busy) stop() else note(line, "Nothing is running.")
            "reasoning" -> return reasoningCommand(line, arg)
            "model" -> return modelCommand(line, arg)
        }
        val c = client ?: return
        if (_chat.value.busy || _chat.value.sending) return say(IllegalStateException("Wait for the current reply to finish."))
        _chat.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val sid = _chat.value.sessionId ?: c.createSession(null).also { s ->
                    store.lastSessionId = s.id
                    drafts.clear(null)
                    _chat.update { it.copy(sessionId = s.id, title = s.displayTitle) }
                }.id
                when (val reply = c.runCommand(sid, line)) {
                    is CommandReply.Output -> {
                        _chat.update { it.copy(sending = false) }
                        note(line, reply.text)
                        if (name == "title" || name == "compress") reloadAfterCommand(sid)
                    }
                    is CommandReply.Send -> {
                        _chat.update { it.copy(sending = false) }
                        sendMessage(reply.message, reply.display)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _chat.update { it.copy(sending = false) }
                say(t)
            }
        }
    }

    private fun reloadAfterCommand(sid: String) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.session(sid) }.onSuccess { (s, _) ->
                if (_chat.value.sessionId == sid) _chat.update { it.copy(title = s.displayTitle) }
            }
            refreshSessions()
        }
    }

    private fun reasoningCommand(line: String, arg: String) {
        val levels = _models.value?.reasoningLevels ?: REASONING_LEVELS
        val wanted = when (arg.lowercase()) {
            "" -> return note(line, "Reasoning effort: ${_reasoning.value ?: "default (${_models.value?.reasoningDefault ?: "from config"})"}\n" +
                "Options: ${levels.joinToString(", ")}, default")
            "off" -> "none"
            "default", "reset" -> null
            else -> arg.lowercase()
        }
        if (wanted != null && wanted !in levels) return note(line, "Unknown level “$arg”. Options: ${levels.joinToString(", ")}, default")
        setReasoning(wanted)
        note(line, "Reasoning effort for your next messages: ${wanted ?: "default"}")
    }

    private fun modelCommand(line: String, arg: String) {
        if (arg.isEmpty()) {
            loadModels()
            _openModelPicker.value = true
            return
        }
        val c = client ?: return
        viewModelScope.launch {
            val catalog = _models.value ?: runCatching { c.models() }.getOrNull()?.also { _models.value = it }
                ?: return@launch note(line, "Couldn't load the model list from your PC.")
            val q = arg.lowercase()
            val hit = catalog.options.firstOrNull {
                it.id.lowercase() == q || it.label.lowercase() == q ||
                    it.id.substringAfterLast('/').lowercase() == q
            }
                ?: catalog.options.filter { it.id.lowercase().contains(q) || it.label.lowercase().contains(q) }.singleOrNull()
            if (hit == null) {
                val near = catalog.options.filter { it.label.lowercase().contains(q.take(4)) }.take(5).joinToString { it.label }
                note(line, "No single model matches “$arg”." + if (near.isNotEmpty()) " Close: $near" else "")
            } else {
                chooseModel(hit)
                note(line, "Model for your next messages: ${hit.providerName}: ${hit.label}")
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

    // ------------------------------------------------------------ system + desktop

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

    fun loadDesktop() {
        val c = client ?: return
        viewModelScope.launch {
            _desktop.value = try { Result.success(c.desktop()) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(IOException(describe(t)))
            }
        }
    }
}
