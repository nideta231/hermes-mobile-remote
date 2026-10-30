package io.github.nideta231.hermesremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.nideta231.hermesremote.data.FileInfo
import io.github.nideta231.hermesremote.data.parseFileInfo
import io.github.nideta231.hermesremote.data.mergeSessionHead
import io.github.nideta231.hermesremote.data.sessionParams
import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.BridgeStart
import io.github.nideta231.hermesremote.data.bridgeStartState
import io.github.nideta231.hermesremote.data.bridgeStoreFor
import io.github.nideta231.hermesremote.data.PcMonitor
import io.github.nideta231.hermesremote.data.ClaudeUsage
import io.github.nideta231.hermesremote.data.ContextInfo
import io.github.nideta231.hermesremote.data.parseContext
import io.github.nideta231.hermesremote.data.TerminalSession
import io.github.nideta231.hermesremote.data.BridgeException
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.ProjectDetails
import io.github.nideta231.hermesremote.data.SessionView
import io.github.nideta231.hermesremote.data.Target
import io.github.nideta231.hermesremote.data.BackgroundProcess
import io.github.nideta231.hermesremote.data.BackgroundProcesses
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentOutput
import io.github.nideta231.hermesremote.data.SubagentReducer
import io.github.nideta231.hermesremote.data.SessionViewStore
import io.github.nideta231.hermesremote.data.DrawerCache
import io.github.nideta231.hermesremote.data.GroupOrder
import io.github.nideta231.hermesremote.data.moveGroup
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.ComponentStatus
import io.github.nideta231.hermesremote.data.AppUpdates
import io.github.nideta231.hermesremote.data.CredentialStore
import io.github.nideta231.hermesremote.data.CronJobs
import io.github.nideta231.hermesremote.data.FileAttachments
import io.github.nideta231.hermesremote.data.withFileRefs
import io.github.nideta231.hermesremote.data.Share
import io.github.nideta231.hermesremote.data.SharedContent
import io.github.nideta231.hermesremote.data.SharedFiles
import io.github.nideta231.hermesremote.data.ModelConfirm
import io.github.nideta231.hermesremote.data.SystemSettings
import io.github.nideta231.hermesremote.data.Dispatch
import io.github.nideta231.hermesremote.data.DraftStore
import io.github.nideta231.hermesremote.data.Endpoint
import io.github.nideta231.hermesremote.data.EndpointResolver
import io.github.nideta231.hermesremote.data.Gateway
import io.github.nideta231.hermesremote.data.GatewayState
import io.github.nideta231.hermesremote.data.DEFAULT_HERMES_PROFILE
import io.github.nideta231.hermesremote.data.HermesProfile
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.Incoming
import io.github.nideta231.hermesremote.data.LanDiscovery
import io.github.nideta231.hermesremote.data.LiveLink
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import io.github.nideta231.hermesremote.data.ProfileStore
import io.github.nideta231.hermesremote.data.GatewayAuth
import io.github.nideta231.hermesremote.data.GatewayAuthException
import io.github.nideta231.hermesremote.data.GatewayCredentialStore
import io.github.nideta231.hermesremote.data.GatewayCredentials
import io.github.nideta231.hermesremote.data.GatewayUrl
import io.github.nideta231.hermesremote.data.HostConnection
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.HostLink
import io.github.nideta231.hermesremote.data.HostLinking
import io.github.nideta231.hermesremote.data.decideConnection
import io.github.nideta231.hermesremote.data.gatewayAuthFor
import io.github.nideta231.hermesremote.data.viaLabel
import io.github.nideta231.hermesremote.data.LoopbackListener
import io.github.nideta231.hermesremote.data.GatewayRestClient
import io.github.nideta231.hermesremote.data.OkHttpTransport
import io.github.nideta231.hermesremote.data.SessionsSource
import io.github.nideta231.hermesremote.data.Pkce
import io.github.nideta231.hermesremote.data.Notifier
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.REASONING_LEVELS
import io.github.nideta231.hermesremote.data.RpcException
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.SlashSuggestion
import io.github.nideta231.hermesremote.data.Tailnet
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import io.github.nideta231.hermesremote.data.WatchService
import io.github.nideta231.hermesremote.data.objects
import io.github.nideta231.hermesremote.data.parseApproval
import io.github.nideta231.hermesremote.data.parseCatalog
import io.github.nideta231.hermesremote.data.parseClarify
import io.github.nideta231.hermesremote.data.parseSecret
import io.github.nideta231.hermesremote.data.secretAnswer
import io.github.nideta231.hermesremote.data.ImageAttachments
import io.github.nideta231.hermesremote.data.parseDispatch
import io.github.nideta231.hermesremote.data.parseSuggestions
import io.github.nideta231.hermesremote.data.profileParam
import io.github.nideta231.hermesremote.data.fileName
import io.github.nideta231.hermesremote.data.reuseKeys
import io.github.nideta231.hermesremote.data.str
import io.github.nideta231.hermesremote.data.strings
import io.github.nideta231.hermesremote.data.withInflight
import io.github.nideta231.hermesremote.data.turnPromptKey
import io.github.nideta231.hermesremote.data.withLiveUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** New chat only: the project it will be created in (its folder becomes the chat's working folder). */
    val project: ProjectRef? = null,
    /** Pictures picked for the next message (phone `content://` uris), sent with it. */
    val attachments: List<String> = emptyList(),
    /** Files (any type) picked for the next message, as (phone uri, display name). */
    val files: List<Pair<String, String>> = emptyList(),
    /** Child agents this chat delegated to: running ones, and the ones that ended this turn. */
    val subagents: List<Subagent> = emptyList(),
    /** Background processes this chat started (dev servers, builds): running ones and recent ends. */
    val processes: List<BackgroundProcess> = emptyList(),
) {
    val busy: Boolean get() = status != "idle"
    val waiting: Boolean get() = status == "waiting" || LiveReducer.openApproval(items) != null ||
        LiveReducer.openClarify(items) != null || LiveReducer.openSecret(items) != null
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
/** [gateway]: a direct gateway host (signs in, no bridge pairing to redo). */
data class PairedPc(val id: String, val name: String, val url: String, val active: Boolean, val gateway: Boolean = false, val linked: Boolean = false)

/**
 * The app asks whether two hosts are one PC. [bridgeId] set: a listed bridge and gateway match, offer "Link".
 * [bridgeId] null: a bridge answers at [bridgeUrl] next to the gateway host [gatewayId], offer "Pair".
 */
data class LinkOffer(val gatewayId: String, val bridgeId: String?, val bridgeUrl: String?, val name: String)

/**
 * The Hermes profiles on the PC in use, and which one the app talks to. [items] is empty until
 * the bridge answers, and stays empty on a bridge too old to list them (the picker then hides).
 */
data class ProfilesState(
    val items: List<HermesProfile> = emptyList(),
    val active: String = DEFAULT_HERMES_PROFILE,
) {
    val current: HermesProfile? get() = items.firstOrNull { it.name == active }

    /** What the chat header names, or null: a PC with only the default profile has nothing to tell apart. */
    val headerLabel: String? get() = if (items.size > 1) current?.label ?: active else null
}

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
    /** "via Bridge" or "via Gateway" for a linked PC; null for any other host. */
    val via: String? = null,
)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val profiles = ProfileStore(app)
    private var store = CredentialStore(app, ProfileStore.pairingFile(profiles.activeId))
    private var drafts = DraftStore(app, ProfileStore.draftsFile(profiles.activeId))
    private var bridgeAddresses: Map<String, List<String>>? = null
    private var client: BridgeClient? = null
    /** Sessions, profiles, pin/rename, status and pictures of the active host: [client] for a bridge, the gateway's own REST routes otherwise. */
    private var source: SessionsSource? = null
    private var gateway: Gateway? = null
    private var gatewayJobs: List<Job> = emptyList()

    private val _pairing = MutableStateFlow(store.load())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    /**
     * The active host when it is a gateway (it has no [pairing]): null otherwise. [signedOut] is true when it
     * holds no tokens or the socket ended unauthorized: the root then asks to sign in again, keeping the host.
     */
    data class GatewayHostState(val id: String, val name: String, val baseUrl: String?, val signedOut: Boolean)
    private val _gatewayHost = MutableStateFlow<GatewayHostState?>(null)
    val gatewayHost: StateFlow<GatewayHostState?> = _gatewayHost.asStateFlow()

    private fun hasHost() = _pairing.value != null || _gatewayHost.value != null

    /** Every paired PC, for the switcher. */
    private val _pcs = MutableStateFlow<List<PairedPc>>(emptyList())
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()

    /** A question about linking hosts that are one PC; null when nothing is offered. */
    private val _linkOffer = MutableStateFlow<LinkOffer?>(null)
    val linkOffer: StateFlow<LinkOffer?> = _linkOffer.asStateFlow()

    /** Set by "Pair" on the bridge offer: the next bridge pairing is linked with this gateway host. */
    private var pendingLinkGateway: String? = null

    /** Link [a] and [b] by hand ("Link with..."): one is a bridge host, the other a gateway host. */
    fun linkPcs(a: String, b: String) {
        val kinds = profiles.list().associate { it.id to it.kind }
        val bridge = listOf(a, b).firstOrNull { kinds[it] == HostKind.BRIDGE } ?: return
        val gateway = listOf(a, b).firstOrNull { kinds[it] == HostKind.GATEWAY } ?: return
        profiles.link(bridge, gateway)
        refreshPcs()
    }

    fun unlinkPc(id: String) { profiles.unlink(id); refreshPcs() }

    /** "Pair" on the bridge offer opens the pairing screen; "Link" on the existing-hosts offer links them. */
    fun acceptLinkOffer() {
        val o = _linkOffer.value ?: return
        _linkOffer.value = null
        if (o.bridgeId != null) { profiles.link(o.bridgeId, o.gatewayId); refreshPcs() }
        else { pendingLinkGateway = o.gatewayId; startAddingPc() }
    }

    fun declineLinkOffer() {
        val o = _linkOffer.value ?: return
        _linkOffer.value = null
        if (o.bridgeId != null) profiles.declineLink(HostLink(o.bridgeId, o.gatewayId))
    }

    /** Offers once to link two listed hosts that are one PC (the gateway's host is an address of the bridge). */
    private fun offerExistingLinks() {
        if (_linkOffer.value != null) return
        val bridges = profiles.list().filter { it.kind == HostKind.BRIDGE }.mapNotNull { pr ->
            val saved = CredentialStore(app, ProfileStore.pairingFile(pr.id)).load() ?: return@mapNotNull null
            val known = CredentialStore(app, ProfileStore.pairingFile(pr.id)).bridgeAddresses.orEmpty().values.flatten()
            pr.id to (known + listOfNotNull(EndpointResolver.hostOf(saved.url))).toSet()
        }.toMap()
        val gateways = profiles.list().filter { it.kind == HostKind.GATEWAY }.mapNotNull { pr ->
            GatewayCredentialStore(app, pr.id).load()?.let { pr.id to it.baseUrl }
        }.toMap()
        val found = HostLinking.matches(bridges, gateways)
            .firstOrNull { profiles.linkedWith(it.bridgeId) == null && profiles.linkedWith(it.gatewayId) == null && !profiles.isDeclined(it) } ?: return
        val name = profiles.list().firstOrNull { it.id == found.bridgeId }?.name.orEmpty()
        _linkOffer.value = LinkOffer(found.gatewayId, found.bridgeId, null, name.ifBlank { "this PC" })
    }

    /** After a gateway sign-in: offer to link a listed bridge, else look for a bridge on the same host. */
    private fun offerBridgeFor(gatewayId: String, gatewayUrl: String) {
        offerExistingLinks()
        if (_linkOffer.value != null || profiles.linkedWith(gatewayId) != null) return
        viewModelScope.launch {
            val url = HostLinking.findBridgeFor(gatewayUrl, HostLinking::probe) ?: return@launch
            if (_linkOffer.value == null) _linkOffer.value = LinkOffer(gatewayId, null, url, EndpointResolver.hostOf(url).orEmpty())
        }
    }

    /** "Add a PC" is open: the pairing screen shows over the app, and a pairing lands in a new profile. */
    private val _addingPc = MutableStateFlow(false)
    val addingPc: StateFlow<Boolean> = _addingPc.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    private val _hermesProfiles = MutableStateFlow(ProfilesState(active = store.hermesProfile))
    val hermesProfiles: StateFlow<ProfilesState> = _hermesProfiles.asStateFlow()

    /** The Hermes profile every request goes to (saved per paired PC). */
    private val profile: String get() = _hermesProfiles.value.active

    /** [params] for a socket call that acts on the profile in use. */
    private fun scoped(params: JSONObject): JSONObject = params.apply { profileParam(profile)?.let { put("profile", it) } }

    /** How the drawer groups, sorts and filters sessions; persisted across launches. */
    private val viewStore = SessionViewStore(app)
    private val _sessionView = MutableStateFlow(viewStore.load())
    val sessionView: StateFlow<SessionView> = _sessionView.asStateFlow()

    fun setSessionView(v: SessionView) {
        val regroup = v.grouping != _sessionView.value.grouping
        _sessionView.value = v
        viewStore.save(v)
        if (regroup) loadGroupOrder()
    }

    /** The drawer as last synced (chats, projects, custom group order) for this PC; see [DrawerCache]. */
    private var drawerCache = DrawerCache(app, profiles.activeId)

    /** Custom group order (group keys, top first) for the current grouping and profile. */
    private val _groupOrder = MutableStateFlow<List<String>>(emptyList())
    val groupOrder: StateFlow<List<String>> = _groupOrder.asStateFlow()

    private fun loadGroupOrder() { _groupOrder.value = drawerCache.groupOrder(profile, _sessionView.value.grouping) }

    /**
     * Move a drawer group by [delta] places (Int.MIN_VALUE/MAX_VALUE = top/bottom) among [shown], the
     * group keys in the order the drawer draws them. Switches the drawer to the custom group order.
     */
    fun moveSessionGroup(shown: List<String>, key: String, delta: Int) {
        val next = moveGroup(shown, _groupOrder.value, key, delta)
        _groupOrder.value = next
        drawerCache.putGroupOrder(profile, _sessionView.value.grouping, next)
        if (_sessionView.value.groupOrder != GroupOrder.CUSTOM) setSessionView(_sessionView.value.copy(groupOrder = GroupOrder.CUSTOM))
    }

    /** Forget the custom order of the current grouping (back to following the sort). */
    fun resetGroupOrder() {
        _groupOrder.value = emptyList()
        drawerCache.putGroupOrder(profile, _sessionView.value.grouping, emptyList())
        setSessionView(_sessionView.value.copy(groupOrder = GroupOrder.AUTO))
    }

    /** Show the saved drawer at once (no blank list, no chats lumped into Home) until the sync lands. */
    private fun loadCachedDrawer() {
        val asked = profile
        val items = drawerCache.sessions(asked)
        val projects = drawerCache.projects(asked)
        loadGroupOrder()
        if (items.isEmpty() && projects.isEmpty()) return
        _sessions.update { st ->
            st.copy(items = st.items.ifEmpty { items }, projects = st.projects.ifEmpty { projects })
        }
    }

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

    /** One-shot user-facing messages (snackbar). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Something another app shared, copied and waiting for the user to pick a chat for it. */
    private val _pendingShare = MutableStateFlow<SharedContent?>(null)
    val pendingShare: StateFlow<SharedContent?> = _pendingShare.asStateFlow()
    private var shareJob: Job? = null

    /** The Scheduled page: cron jobs of the profile in use. */
    val cron = CronJobs(viewModelScope, source = { source as? io.github.nideta231.hermesremote.data.CronSource }, target = ::target,
        describe = ::describe, say = { _toast.value = it })
    /** The drawer's "In messages" search: Hermes' message search, beside the local title filter. */
    val search = io.github.nideta231.hermesremote.data.SessionSearch(viewModelScope,
        source = { source as? io.github.nideta231.hermesremote.data.SessionSearchSource }, target = ::target)
    /** The Vault page: saved logins, cards and addresses on the PC (metadata; secrets stay there). */
    val vault = io.github.nideta231.hermesremote.data.Vault(viewModelScope,
        call = { method, params -> (gateway ?: throw IOException("Not connected to your PC")).call(method, scoped(params), 30_000) },
        target = ::target, describe = ::describe, say = { _toast.value = it })
    /** Settings > PC: the bridge's health, and the profile's approval mode and default model. */
    val notes = io.github.nideta231.hermesremote.data.NotesRepo(viewModelScope, client = { client },
        describe = ::describe, say = { _toast.value = it })
    val bridgeUpdate = io.github.nideta231.hermesremote.data.BridgeUpdater(viewModelScope, client = { client },
        describe = ::describe, say = { _toast.value = it }, onBridgeBack = ::bridgeBack)

    val blueprints = io.github.nideta231.hermesremote.data.BlueprintRepo(viewModelScope, client = { client },
        describe = ::describe, say = { _toast.value = it }, cacheDir = { app.cacheDir },
        seen = io.github.nideta231.hermesremote.data.BlueprintSeen(app),
        notify = { pid, name, m -> Notifier.postBlueprintComment(app, pid, name, m) })

    /** A file picked on the phone, sent to the PC for the open blueprint; null (and a toast) when it fails. */
    suspend fun uploadBlueprintFile(uri: String): String? = try {
        val (bytes, name) = io.github.nideta231.hermesremote.data.FileAttachments.read(app, uri)
        blueprints.upload(bytes, name).also { _toast.value = "Attached $name" }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        _toast.value = describe(t); null
    }

    /** New chat in [b]'s project with a drafted ask (the user sends it): work from the blueprint, take control, or do one card. */
    fun askHermesBlueprint(b: io.github.nideta231.hermesremote.data.Blueprint, what: String) {
        // An answer on a card goes back to the chat that asked (steered if it is still running), not a new one.
        if (what.startsWith("reply:")) {
            val ref = what.removePrefix("reply:")
            val card = b.cards.firstOrNull { it.id == ref } ?: b.cards.firstOrNull { it.title == ref }
            val title = card?.title ?: ref
            val text = "I answered on the card \"$title\" in the ${b.name} blueprint: read its comments (blueprint md) and carry on."
            card?.session?.let { sid -> return replyInChat(sid, text) }
            return askHermesBlueprint(b, "replynew:$title")
        }
        newChat(); chooseProject(b.projectId)
        val rules = b.effectiveInstructions.takeIf { it.isNotEmpty() }?.let { "\n\nProject instructions (always follow): $it" }.orEmpty()
        setDraft(when {
            what == "control" -> "Take full control of the ${b.name} blueprint (project-blueprint skill): read it, plan the flow and the board, then work through it and keep it updated. I'll mostly watch and adjust."
            what.startsWith("card:") -> "Read the ${b.name} blueprint (project-blueprint skill), then do the card \"${what.removePrefix("card:")}\": move it to Working, tick its checklist as you go and move it to Done when it's verified."
            what.startsWith("replynew:") -> "Read the ${b.name} blueprint (project-blueprint skill). I answered on the card \"${what.removePrefix("replynew:")}\": read its comments and carry on."
            else -> "Read the ${b.name} blueprint (project-blueprint skill) and continue the work: pick the next card, keep the board and diagram updated as you go."
        } + rules)
    }

    /** Open chat [sid] and send [text] there: a steer while it runs, a new turn when idle; drafted if the chat won't open. */
    private fun replyInChat(sid: String, text: String) {
        openSession(sid)
        viewModelScope.launch {
            repeat(60) {
                val c = _chat.value
                if (c.sessionId != sid) return@launch  // the user went elsewhere
                if (c.runtimeId != null && !c.loading) return@launch if (c.busy) steer(text) else send(text)
                delay(250)
            }
            if (_chat.value.sessionId == sid) setDraft(text)
        }
    }

    val bridgeAdmin = io.github.nideta231.hermesremote.data.BridgeAdmin(viewModelScope, client = { client },
        describe = ::describe, say = { _toast.value = it }, onBridgeBack = ::bridgeBack,
        supportsParked = { "parked" in _features.value })

    /** After a bridge restart: reopen the live socket at once (no backoff wait) and reload everything it serves. */
    private fun bridgeBack() {
        val g = gateway
        if (g == null || client == null) return
        g.nudge()  // reconnects now if the restart dropped it; an open (new) socket is left alone
        discoverEndpoints()
        refreshProfiles()
        refreshSessions()
        settings.refresh()
        cron.refresh()
    }
    val settings = SystemSettings(viewModelScope, client = { client }, source = { source }, target = ::target,
        describe = ::describe, say = { _toast.value = it },
        onDefaultModel = { option -> _models.update { cat -> cat?.copy(options = cat.options.map { o ->
            o.copy(current = o.id == option.id && o.provider == option.provider) }) } })

    /** Reading and steering the open chat's running subagents. */
    val subagentOutput = SubagentOutput(viewModelScope, rpc = { method, params -> rpc(method, params) },
        chatId = { _chat.value.sessionId },
        live = { gateway?.state?.value == GatewayState.OPEN && _chat.value.runtimeId != null },
        describe = ::describe, say = { _toast.value = it })

    /** The PC page: live stats, history, processes and Claude usage (bridge 1.18+). */
    val monitor = PcMonitor(viewModelScope, client = { client }, describe = ::describe, say = { _toast.value = it },
        gateway = { source as? GatewayRestClient })

    /** A shell on the PC for the Terminal page; null when not connected. Closed by the caller. */
    fun openTerminal(cols: Int, rows: Int): TerminalSession? = client?.let { TerminalSession(it, cols, rows) }

    /** What the PC's bridge serves beyond chat ("monitor", "claude_usage", "terminal"), from /v1/me. */
    private val _features = MutableStateFlow<Set<String>>(emptySet())
    val features: StateFlow<Set<String>> = _features.asStateFlow()

    /** The PC refused this phone's token (revoked or re-paired from the PC): offer to re-pair. */
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()
    fun dismissUnauthorized() { _unauthorized.value = false }

    /** Self-update from GitHub releases, and "What's new" after one. */
    val updates = AppUpdates(app, viewModelScope, describe = ::describe, say = { _toast.value = it })

    private var openJob: Job? = null
    private var suggestJob: Job? = null
    private var activeJob: Job? = null
    private var sessionsRefreshJob: Job? = null

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { onForeground(); startHeartbeat(); startActivePolling(); startUsagePolling(); updates.startChecks() }
        override fun onStop(owner: LifecycleOwner) {
            heartbeatJob?.cancel()
            usageJob?.cancel()
            updates.stopChecks()
            activeJob?.cancel()
            // A turn still running: keep the process (and the socket) up so its end or its
            // question can be notified. The service stops by itself once nothing runs.
            if (_chat.value.busy || LiveLink.busy.value.isNotEmpty() || LiveLink.backgroundWork.value.isNotEmpty()) WatchService.start(app)
        }
    }

    private var heartbeatJob: Job? = null
    private var usageJob: Job? = null

    /** Claude plan usage for the top-bar ring; null until the bridge (with the monitor feature) answers. */
    private val _claudeUsage = MutableStateFlow<ClaudeUsage?>(null)
    val claudeUsage: StateFlow<ClaudeUsage?> = _claudeUsage.asStateFlow()

    /** The open chat's context window by category (Hermes' /usage breakdown); null without a chat. */
    suspend fun contextBreakdown(): ContextInfo? {
        if (_chat.value.sessionId == null) return null
        return parseContext(rpc("session.context_breakdown", { JSONObject().put("session_id", it) }, 60_000))
    }

    /** Claude usage over [range] for the usage popup's chart. */
    suspend fun claudeHistory(range: String): io.github.nideta231.hermesremote.data.PcHistory? = client?.claudeHistory(range)

    /** Ask the bridge to check Claude usage upstream now (the ring and popup update). */
    fun refreshClaudeUsage() {
        val c = client ?: return
        viewModelScope.launch { runCatching { c.claudeUsage(refresh = true) }.getOrNull()?.let { if (it.hasNumbers) _claudeUsage.value = it } }
    }

    /** Polls the bridge's cached Claude usage while the app is in front (cheap: the bridge caches upstream). */
    private fun startUsagePolling() {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            while (true) {
                val c = client
                if (c != null && "monitor" in _features.value) {
                    runCatching { c.claudeUsage() }.getOrNull()?.let { u -> if (u.hasNumbers) _claudeUsage.value = u }
                }
                delay(if (_claudeUsage.value == null) 10_000 else 60_000)
            }
        }
    }

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
            var tick = 0
            while (true) {
                refreshActive()
                refreshProcesses()
                // Every ~30 s also re-sync the drawer (newest page + projects), in case a change event was missed.
                if (++tick % 8 == 0) refreshSessionsHead()
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
        // Composer attachments don't outlive the process, so no copy from an earlier run is in use.
        SharedFiles.clearAll(app)
        enterActiveHost()
        updates.showWhatsNew()
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

    private fun start(p: Pairing, linkedSwitch: Boolean = false) {
        // The bridge side reads and writes the bridge host's own file, even when the gateway entry is the active one.
        val link = HostLinking.linkOf(profiles.links(), profiles.activeId)
        store = CredentialStore(app, bridgeStoreFor(link, profiles.activeId))
        val saved = bridgeStartState(link, profiles.activeId) {
            CredentialStore(app, it).let { s -> BridgeStart(s.lastSessionId, s.bridgeAddresses, s.transportMode, s.bridgeFeatures) }
        }
        bridgeAddresses = saved.bridgeAddresses
        val savedMode = runCatching { TransportMode.valueOf(saved.transportMode.uppercase()) }.getOrDefault(TransportMode.AUTO)
        _conn.update { it.copy(activeUrl = p.url, mode = savedMode, needsRepairForLan = p.pin == null,
            transport = EndpointResolver.transportOf(EndpointResolver.hostOf(p.url).orEmpty())) }
        useClient(BridgeClient(p), linkedSwitch)
        _features.value = saved.bridgeFeatures
        loadCachedDrawer()
        saved.lastSessionId?.let { openSession(it) }
        refreshProfiles()
        refreshSessions()
        settings.refresh()
        cron.refresh()
        discoverEndpoints()
    }

    /** Point the app (REST and the live socket) at [c]. */
    private fun useClient(c: BridgeClient, linkedSwitch: Boolean = false) {
        client = c
        source = c
        attach(LiveLink.connect(app, c, linkedSwitch))
    }

    /** Make the active host (bridge or gateway) go live: [decideConnection] picks how, from its kind and tokens. */
    private fun enterActiveHost() {
        val pr = profiles.active()
        linkJob?.cancel()
        _conn.update { it.copy(via = null) }
        val creds = if (pr.kind == HostKind.GATEWAY) GatewayCredentialStore(app, pr.id).load() else null
        HostLinking.linkOf(profiles.links(), pr.id)?.let { l ->
            if (pr.kind == HostKind.BRIDGE) watchLink(l, HostConnection.BridgePairing)
            else creds?.let { watchLink(l, HostConnection.GatewayLogin(it)) }
        }
        _pairing.value = if (pr.kind == HostKind.BRIDGE) store.load() else null
        when (val how = decideConnection(pr.kind, creds)) {
            HostConnection.BridgePairing -> {
                _gatewayHost.value = null
                refreshPcs()
                _pairing.value?.let { start(it) }
            }
            is HostConnection.GatewayLogin -> {
                val c = how.credentials
                _gatewayHost.value = GatewayHostState(pr.id, pr.name.ifBlank { GatewayUrl.hostName(c.baseUrl) }, c.baseUrl, signedOut = false)
                refreshPcs()
                _conn.update { it.copy(activeUrl = c.baseUrl) }
                // No BridgeClient: nothing calls the bridge's /v1 routes. Sessions, profiles and status come from the gateway's /api routes.
                val auth = gatewayAuthFor(c, GatewayCredentialStore(app, pr.id), OkHttpTransport())
                source = GatewayRestClient(c.baseUrl, auth, OkHttpTransport()) { _gatewayHost.update { it?.copy(signedOut = true) } }
                attach(LiveLink.connectGateway(app, c, auth))
                refreshProfiles()
                refreshSessions()
                settings.refresh()
                cron.refresh()
            }
            HostConnection.NeedsSignIn -> {
                _gatewayHost.value = GatewayHostState(pr.id, pr.name.ifBlank { "Gateway" }, null, signedOut = true)
                refreshPcs()
            }
        }
    }

    private var linkJob: Job? = null

    /**
     * A linked PC: the bridge is used while it answers; when its socket is down and a probe gets no answer the app
     * moves to the gateway sign-in, and back to the bridge on a later check once it answers (not within [SWITCH_MIN_MS]).
     */
    private fun watchLink(l: HostLink, entry: HostConnection) {
        linkJob = viewModelScope.launch {
            var on: HostConnection = entry
            // Opened from the gateway side: the first check is at once, so the bridge is taken as soon as it answers.
            var since = if (entry is HostConnection.GatewayLogin) 0L else System.currentTimeMillis()
            var first = entry is HostConnection.GatewayLogin
            _conn.update { it.copy(via = viaLabel(on)) }
            while (true) {
                if (first) first = false else delay(if (on is HostConnection.GatewayLogin) 10_000L else 5_000L)
                val pairing = CredentialStore(app, ProfileStore.pairingFile(l.bridgeId)).load() ?: continue
                val bridgeUp = (on is HostConnection.BridgePairing && gateway?.state?.value == GatewayState.OPEN) ||
                    BridgeClient(pairing).probe() != BridgeClient.Probe.UNREACHABLE
                val gwStore = GatewayCredentialStore(app, l.gatewayId)
                val next = decideConnection(HostKind.BRIDGE, gwStore.load(), true, bridgeUp, on, System.currentTimeMillis() - since).connection
                if (next == on || next == HostConnection.NeedsSignIn || next::class == on::class) continue
                on = next
                since = System.currentTimeMillis()
                when (next) {
                    is HostConnection.GatewayLogin -> {
                        val c = next.credentials
                        store = CredentialStore(app, ProfileStore.pairingFile(profiles.activeId)) // back to the active entry's own file
                        val auth = gatewayAuthFor(c, gwStore, OkHttpTransport())
                        _gatewayHost.value = GatewayHostState(l.gatewayId, profiles.active().name.ifBlank { GatewayUrl.hostName(c.baseUrl) }, c.baseUrl, signedOut = false)
                        _conn.update { it.copy(activeUrl = c.baseUrl) }
                        source = GatewayRestClient(c.baseUrl, auth, OkHttpTransport()) { _gatewayHost.update { it?.copy(signedOut = true) } }
                        attach(LiveLink.connectGateway(app, c, auth, linkedSwitch = true))
                    }
                    else -> {
                        _gatewayHost.value = null
                        _pairing.value = pairing
                        start(pairing, linkedSwitch = true)
                    }
                }
                _conn.update { it.copy(via = viaLabel(on)) }
            }
        }
    }

    private fun attach(g: Gateway) {
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
                    if (s == GatewayState.UNAUTHORIZED) {
                        if (_gatewayHost.value != null) {
                            _toast.value = g.lastError
                            _gatewayHost.update { it?.copy(signedOut = true) }
                        } else _unauthorized.value = true
                    }
                    if (s == GatewayState.OPEN) _unauthorized.value = false
                }
            },
        )
    }

    /** Ask the bridge what it answers on, so the app can offer LAN/Tailscale without re-pairing. */
    private fun discoverEndpoints() {
        val c = client ?: return
        viewModelScope.launch {
            // /me can miss right after pairing or a cold start (network still waking): a lost answer used to
            // leave the PC glance hidden until the next foreground. Retry a few times before giving up.
            var me = runCatching { c.me() }.getOrNull()
            var tries = 0
            while (me == null && tries++ < 3 && client === c) {
                delay(2_000L * tries)
                me = runCatching { c.me() }.getOrNull()
            }
            if (client !== c) return@launch
            val addresses: Map<String, List<String>>? = me?.optJSONObject("addresses")?.let { obj ->
                mapOf("lan" to obj.optJSONArray("lan").strings(), "tailnet" to obj.optJSONArray("tailnet").strings())
            }
            if (addresses == null) {
                // The saved address is dead (e.g. paired at home, now outside): try the rest.
                if (me == null && !_conn.value.searching) reconnect()
                return@launch
            }
            val features = me.optJSONArray("features").strings().toSet()
            _features.value = features
            store.bridgeFeatures = features
            if ("bridge_update" in features) bridgeUpdate.check(false)
            if ("blueprints" in features) { blueprints.refreshList(); blueprints.watch() }
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
                        when (BridgeClient(candidate).probe()) {
                            BridgeClient.Probe.OK -> {}
                            BridgeClient.Probe.UNAUTHORIZED -> {
                                // The PC is right there and refused the token: no other address will do better.
                                _unauthorized.value = true
                                _conn.update { it.copy(searching = false) }
                                return true
                            }
                            BridgeClient.Probe.UNREACHABLE -> { last = IOException("${ep.label} is not reachable"); continue }
                        }
                        _conn.update { it.copy(activeUrl = ep.url, transport = ep.transport, searching = false) }
                        if (_pairing.value?.url != ep.url) _pairing.update { it?.copy(url = ep.url) }
                        store.save(clientPairing())
                        useClient(BridgeClient(candidate))
                        refreshProfiles()
                        refreshSessions()
                        settings.refresh()
                        cron.refresh()
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
                if (hasHost()) {
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
                _repairDevice.value = null
                _unauthorized.value = false
                store.save(candidate)
                val known = (listOf(p.url) + p.alternates).groupBy { u ->
                    if (Tailnet.isLanHost(EndpointResolver.hostOf(u).orEmpty())) "lan" else "tailnet"
                }.mapValues { (_, us) -> us.mapNotNull { EndpointResolver.hostOf(it) } }
                store.bridgeAddresses = known
                _pairing.value = candidate
                pendingLinkGateway?.let { g -> profiles.link(profiles.activeId, g); pendingLinkGateway = null }
                refreshPcs()
                offerExistingLinks()
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
        val gone = profiles.activeId
        clearHostData(gone)
        // The legacy slot keeps its file names, so it stays in the list as the empty fallback.
        val next = profiles.remove(gone)
        // Go to a PC that can still be used (a saved pairing or gateway sign-in), not an empty slot:
        // an empty slot is the pairing screen, which has no way back to the other PCs.
        activate(profiles.list().firstOrNull { usable(it) }?.id ?: next)
        enterActiveHost()
    }

    /** Talk to another paired PC. Its chats, drafts and addresses come back as they were left. */
    fun switchPc(id: String) {
        if (id == profiles.activeId && hasHost()) return
        if (profiles.list().none { it.id == id }) return
        teardown() // closes the old socket first: never two live sockets
        activate(id)
        enterActiveHost()
    }

    /** Forget any paired PC; forgetting the one in use is [unpair]. */
    fun forgetPc(id: String) {
        if (id == profiles.activeId) return unpair()
        clearHostData(id)
        DrawerCache(app, id).clear()
        profiles.remove(id)
        refreshPcs()
    }

    /** A host with something to connect with: a saved bridge pairing or gateway credentials. */
    private fun usable(p: io.github.nideta231.hermesremote.data.Profile): Boolean =
        if (p.kind == HostKind.GATEWAY) GatewayCredentialStore(app, p.id).load() != null
        else CredentialStore(app, ProfileStore.pairingFile(p.id)).load() != null

    /** The one step both [unpair] and [forgetPc] use: pairing, drafts and gateway credentials of host [id]. */
    private fun clearHostData(id: String) =
        ProfileStore.clearHostFiles(id) { app.getSharedPreferences(it, Context.MODE_PRIVATE) }

    fun renamePc(id: String, name: String) {
        if (name.isBlank()) return
        profiles.rename(id, name)
        refreshPcs()
    }

    fun startAddingPc() { _repairDevice.value = null; _addingPc.value = true }
    fun cancelAddingPc() { _addingPc.value = false; _repairDevice.value = null }

    /** Re-pair the PC in use: the pairing screen asks for a fresh code for this device name, and
     *  scanning it replaces the broken pairing in place (same certificate = same PC), keeping
     *  its chats, drafts and name. Null when not re-pairing. */
    private val _repairDevice = MutableStateFlow<String?>(null)
    val repairDevice: StateFlow<String?> = _repairDevice.asStateFlow()

    fun startRepair() {
        val p = _pairing.value ?: return
        _repairDevice.value = p.device.ifBlank { "phone" }
        _addingPc.value = true
    }

    private fun activate(id: String) {
        profiles.activeId = id
        store = CredentialStore(app, ProfileStore.pairingFile(id))
        drafts = DraftStore(app, ProfileStore.draftsFile(id))
        drawerCache = DrawerCache(app, id)
        _reasoning.value = store.reasoningEffort
        _chat.value = ChatState(draft = drafts.get(null), reasoning = store.reasoningEffort)
        _hermesProfiles.value = ProfilesState(active = store.hermesProfile)
    }

    /** Drop every link and screen state of the current PC (its saved files stay). */
    private fun teardown() {
        openJob?.cancel()
        gatewayJobs.forEach { it.cancel() }
        gatewayJobs = emptyList()
        LiveLink.close()
        gateway = null
        client = null
        source = null
        bridgeAddresses = null
        _pairing.value = null
        _gatewayHost.value = null
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        settings.clear()
        cron.clear()
        search.clear()
        vault.clear()
        monitor.clear()
        bridgeUpdate.clear()
        bridgeAdmin.clear()
        blueprints.clear()
        notes.clear()
        _features.value = emptySet()
        _claudeUsage.value = null
        _unauthorized.value = false
        _models.value = null
        _modelChoice.value = null
        _confirm.value = null
        _conn.value = ConnectionState()
    }

    private var signInListener: LoopbackListener? = null

    /**
     * Sign in to a gateway: [openBrowser] gets the gateway's login URL (a Custom Tab), the loopback
     * listener catches the redirect, and only a finished sign-in adds a [HostKind.GATEWAY] host with
     * its tokens. Returns null on success, else the message to show; nothing is stored on failure.
     */
    suspend fun signInGateway(rawUrl: String, openBrowser: (String) -> Unit, hostId: String? = null): String? {
        val base = try { GatewayUrl.validate(rawUrl) } catch (e: IllegalArgumentException) { return e.message }
        val auth = GatewayAuth(base, OkHttpTransport())
        val state = Pkce.newVerifier()
        val verifier = Pkce.newVerifier()
        return try {
            val code = withContext(Dispatchers.IO) {
                LoopbackListener(state).also { signInListener = it }.let { l ->
                    openBrowser(auth.authorizeUrl(Pkce.challenge(verifier), l.redirectUri, state))
                    l.awaitCode()
                }
            }
            val t = auth.signIn(code, verifier, state, state)
            // [hostId]: signing in again to a host the app already lists (keeps its entry); else a new host.
            val firstHost = !hasHost()
            val id = hostId?.takeIf { h -> profiles.list().any { it.id == h } }
                ?: profiles.add(GatewayUrl.hostName(base), HostKind.GATEWAY).id
            GatewayCredentialStore(app, id).save(GatewayCredentials(base, t.accessToken, t.refreshToken, t.expiresAt, t.provider))
            if (firstHost || id == profiles.activeId) {
                // Nothing else is live (first launch) or this is the active host coming back: go live on it.
                teardown()
                activate(id)
                enterActiveHost()
            } else refreshPcs()
            offerBridgeFor(id, base)
            null
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is GatewayAuthException) "The gateway did not accept the sign-in (${t.message})." else describe(t)
        } finally {
            signInListener?.close()
            signInListener = null
        }
    }

    fun cancelGatewaySignIn() { signInListener?.close() }

    private fun refreshPcs() {
        val active = profiles.activeId
        _pcs.value = profiles.list().mapNotNull { pr ->
            if (pr.kind == HostKind.GATEWAY) {
                // The active gateway host stays listed while signed out, so the user can see and remove it.
                val gw = GatewayCredentialStore(app, pr.id).load()
                if (gw == null && pr.id != active) return@mapNotNull null
                return@mapNotNull PairedPc(pr.id, pr.name.ifBlank { gw?.let { GatewayUrl.hostName(it.baseUrl) } ?: "Gateway" }, gw?.baseUrl.orEmpty(), pr.id == active, gateway = true, linked = profiles.linkedWith(pr.id) != null)
            }
            val saved = if (pr.id == active) _pairing.value ?: store.load()
                else CredentialStore(app, ProfileStore.pairingFile(pr.id)).load()
            saved ?: return@mapNotNull null
            PairedPc(pr.id, pr.name.ifBlank { EndpointResolver.hostOf(saved.url) ?: "PC" }, saved.url, pr.id == active, linked = profiles.linkedWith(pr.id) != null)
        }
    }

    // ------------------------------------------------------------ app lifecycle

    /** Back on screen: the socket may have died while the process was frozen. Catch up. */
    fun onForeground() {
        if (client == null && gateway == null) return
        gateway?.nudge()
        discoverEndpoints()
        refreshProfiles()
        refreshSessions()
        settings.refresh()
        cron.refresh()
        val g = gateway ?: return
        if (g.state.value != GatewayState.OPEN) return // the reopen emits Incoming.Open, which resumes
        viewModelScope.launch {
            // A socket that looks open may have died while the phone slept: its events (the rest of the
            // reply) were lost. Ping it; a dead one is dropped and the reconnect re-reads the chat.
            val alive = try { g.call("ping", JSONObject(), 5_000); true } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            if (!alive) return@launch g.forceReconnect()
            _chat.value.sessionId?.let { resume(it) }
        }
    }

    private var resyncJob: Job? = null

    /**
     * A turn ended: re-read the chat from the PC. Streamed events can be lost (screen off, a socket
     * that dropped and came back mid-reply); the stored transcript is complete, so the chat always
     * ends up exactly as the PC has it, without pulling to refresh.
     */
    private fun resyncAfterTurn() {
        val sid = _chat.value.sessionId ?: return
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            delay(700)
            if (_chat.value.sessionId == sid && !_chat.value.sending) resume(sid, quiet = true)
        }
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
                // The first sessions sync usually lands before the socket is up, when projects.tree
                // can't be asked yet: without this every chat sat in Home until a manual refresh.
                viewModelScope.launch { refreshProjects() }
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
                    // Sudo, secrets and password-manager prompts: a masked field, like the desktop's card.
                    // The value goes straight back over the encrypted link and is never kept on the phone.
                    else -> parseSecret(inc.id, inc.method, inc.params)?.let { r ->
                        _chat.update { it.copy(items = LiveReducer.withSecret(it.items, r), status = "waiting") }
                    }
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
            var subagents = st.subagents
            var status = st.status
            var title = st.title
            var model = st.model
            var provider = st.provider
            var reasoning = st.reasoning
            for (ev in batch) {
                val key = "${ev.seq ?: System.nanoTime()}"
                when (ev.type) {
                    "message.start" -> {
                        status = "working"
                        fetchLiveUser(st.runtimeId, items.lastOrNull()?.key)
                    }
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
                if (SubagentReducer.handles(ev.type)) subagents = SubagentReducer.apply(subagents, ev.type, ev.payload, System.currentTimeMillis())
                else items = LiveReducer.apply(items, ev.type, ev.payload, key)
            }
            st.copy(items = items, status = status, title = title, model = model, provider = provider, reasoning = reasoning,
                subagents = subagents)
        }
        _reasoning.value = _chat.value.reasoning ?: _reasoning.value
        if (finished) { scheduleSessionsRefresh(); viewModelScope.launch { refreshActive() }; resyncAfterTurn() }
    }

    /** Re-read the open chat's live children from Hermes. Runs on every [resume], so also after each
     *  turn ([resyncAfterTurn]): a child still "running" then is either a background one or lost its end event. */
    private fun refreshSubagents() {
        val g = gateway ?: return
        val rid = _chat.value.runtimeId ?: return
        viewModelScope.launch {
            val roster = try {
                g.call("subagent.list", JSONObject().put("session_id", rid), 15_000)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return@launch  // an older bridge or Hermes: the live events still show them
            }
            _chat.update { if (it.runtimeId != rid) it else it.copy(subagents = SubagentReducer.fromRoster(it.subagents, roster)) }
        }
    }

    /** Re-read the open chat's background processes (polled with [startActivePolling], and on [resume]). */
    private suspend fun refreshProcesses() {
        val g = gateway ?: return
        if (g.state.value != GatewayState.OPEN) return
        val rid = _chat.value.runtimeId ?: return
        val list = try {
            BackgroundProcesses.parse(g.call("process.list", JSONObject().put("session_id", rid), 15_000))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return  // an older bridge or Hermes, or the chat isn't live: keep what is shown
        }
        _chat.update { if (it.runtimeId != rid || it.processes == list) it else it.copy(processes = list) }
    }

    /** Kill one background process of the open chat. */
    fun stopProcess(id: String) {
        viewModelScope.launch {
            try {
                val r = rpc("process.kill", { JSONObject().put("session_id", it).put("process_id", id) })
                if (r.optString("status") == "already_exited") _toast.value = "That process had already ended."
            } catch (t: Throwable) { say(t) }
            refreshProcesses()
        }
    }

    /** Stop one child agent; the chat's own turn goes on. */
    fun stopSubagent(id: String) {
        viewModelScope.launch {
            try {
                val r = rpc("subagent.interrupt", { JSONObject().put("session_id", it).put("subagent_id", id) })
                if (!r.optBoolean("found", true)) _toast.value = "That subagent had already finished."
            } catch (t: Throwable) { say(t) }
        }
    }

    /**
     * `message.start` has no text, so a turn typed on the desktop showed only its reply here. Read
     * the turn's user message from the session (`inflight`, transcript skipped) and put it in place;
     * the phone's own sends are already shown and are left alone (see [withLiveUser]).
     */
    private fun fetchLiveUser(rid: String?, anchorKey: String?) {
        val g = gateway ?: return
        rid ?: return
        viewModelScope.launch {
            val inflight = try {
                g.call("session.activate", JSONObject().put("session_id", rid).put("omit_messages", true), 15_000)
                    .optJSONObject("inflight")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null // older Hermes: the turn-end refresh still shows it
            } ?: return@launch
            _chat.update { if (it.runtimeId != rid) it else it.copy(items = withLiveUser(it.items, anchorKey, inflight)) }
        }
    }

    private fun scheduleSessionsRefresh() {
        sessionsRefreshJob?.cancel()
        sessionsRefreshJob = viewModelScope.launch { delay(600); refreshSessionsHead() }
    }

    /**
     * Live update of the drawer (`sessions.changed` fires every ~2 s while a reply streams): re-read
     * only the newest page and merge it in. Re-reading every page by offset while the active chat
     * keeps moving to the top shifted the pages under the reader, so chats vanished, came back and
     * the list jumped on every refresh.
     */
    private fun refreshSessionsHead() {
        val c = source ?: return
        if (_sessions.value.items.isEmpty()) return refreshSessions(quiet = true)
        val asked = target()
        viewModelScope.launch {
            try {
                val (page, _) = c.sessions(SESSIONS_PER_REQUEST, 0, asked.profile)
                if (!stillOn(asked)) return@launch
                val merged = mergeSessionHead(_sessions.value.items, page)
                if (merged != _sessions.value.items) _sessions.update { it.copy(items = merged, error = null) }
                drawerCache.putSessions(asked.profile, merged)
                val open = page.firstOrNull { it.id == _chat.value.sessionId }
                if (open != null) _chat.update { it.copy(pinned = open.pinned, title = if (it.title == "New chat") open.displayTitle else it.title) }
                refreshProjects()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Quiet: a missed live refresh is retried on the next change; never flash an error row.
            }
        }
    }

    /**
     * Attach to [storedId] on the shared socket and show it as it is right now: the transcript,
     * the turn in flight (if the desktop or the phone is running one) and any open question.
     * Hermes then streams every further event of this session to us, wherever it was started.
     */
    private suspend fun resume(storedId: String, quiet: Boolean = false): Boolean {
        val g = gateway ?: return false
        return try {
            val r = g.call("session.resume", sessionParams(profile).put("session_id", storedId), 60_000)
            if (_chat.value.sessionId != storedId) return false
            val rid = r.getString("session_id")
            val info = r.optJSONObject("info") ?: JSONObject()
            val running = r.optBoolean("running") || info.optBoolean("running")
            val messages = r.optJSONArray("messages")
            var items = HistoryMapper.map(messages)
            val inflight = r.optJSONObject("inflight")
            // A failed turn stays in `inflight` (with `error`) after it stops running: show it, as the desktop does.
            if (running || inflight?.str("error")?.isNotBlank() == true) {
                val started = if (r.has("turn_started_at") && !r.isNull("turn_started_at")) r.optDouble("turn_started_at") else null
                items = withInflight(items, inflight, turnPromptKey(messages, started))
            }
            var waiting = false
            r.optJSONArray("open_requests").objects().forEach { q ->
                val id = q.str("id") ?: return@forEach
                val params = q.optJSONObject("params") ?: JSONObject()
                when (q.str("method")) {
                    "approval" -> { items = LiveReducer.withApproval(items, parseApproval(id, params)); waiting = true }
                    "clarify" -> { items = LiveReducer.withClarify(items, parseClarify(id, params)); waiting = true }
                    else -> parseSecret(id, q.str("method").orEmpty(), params)?.let {
                        items = LiveReducer.withSecret(items, it); waiting = true
                    }
                }
            }
            val title = info.str("title")?.takeIf { it.isNotBlank() }
            LiveLink.watch(rid, storedId, title ?: _chat.value.title, profileParam(profile))
            pendingEvents.clear()
            _chat.update { st ->
                // A message still being sent isn't on the PC yet: keep its bubble through the re-read.
                val fresh = reuseKeys(st.items, items)
                val keys = fresh.mapTo(HashSet()) { it.key }
                st.copy(runtimeId = rid, items = fresh + st.items.filter { it is ChatItem.User && it.pending && it.key !in keys }, loading = false,
                    status = when { waiting -> "waiting"; running -> "working"; else -> "idle" },
                    title = title ?: st.title, model = info.str("model") ?: st.model, provider = info.str("provider") ?: st.provider,
                    reasoning = info.str("reasoning_effort")?.ifBlank { null } ?: st.reasoning)
            }
            _chat.value.reasoning?.let { _reasoning.value = it }
            refreshSubagents()  // delegations run in the background, so also when the turn is over
            viewModelScope.launch { refreshProcesses() }
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _chat.update { it.copy(loading = false) }
            if (quiet) return false // a background re-sync: the chat as shown stays
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
        val params = sessionParams(profile).apply {
            choice?.let { put("model", it.id).put("provider", it.provider) }
            _reasoning.value?.let { put("reasoning_effort", it) }
            _chat.value.project?.path?.let { put("cwd", it) }
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
        LiveLink.watch(rid, stored, null, profileParam(profile))
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

    // ------------------------------------------------------------ Hermes profiles

    /**
     * Which profiles the PC has. A bridge too old to list them leaves the app on the default profile.
     * The list belongs to the PC it was asked of: one that arrives after a switch to another PC is
     * dropped, so neither PC's dropdown shows (or loses) the other's profiles.
     */
    fun refreshProfiles() {
        val c = source ?: return
        val pc = profiles.activeId
        viewModelScope.launch {
            val items = try {
                c.profiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t !is BridgeException || t.httpCode != 404) return@launch  // offline: keep what we have
                emptyList()
            }
            if (profiles.activeId != pc) return@launch
            _hermesProfiles.update { it.copy(items = items) }
            // The chosen profile was deleted (or this bridge can't scope requests): fall back to the default.
            if (profile != DEFAULT_HERMES_PROFILE && items.none { it.name == profile }) switchProfile(DEFAULT_HERMES_PROFILE)
        }
    }

    /**
     * Talk to another Hermes profile on the same PC. Like switching PCs, but the link stays up:
     * the drawer, settings and the next new chat all move to [name]; open chats stay on the PC.
     */
    fun switchProfile(name: String) {
        if (name == profile) return
        store.hermesProfile = name
        _hermesProfiles.update { it.copy(active = name) }
        newChat()
        _sessions.value = SessionsState(loading = true, live = _sessions.value.live)
        loadCachedDrawer()
        _models.value = null
        _modelChoice.value = null
        settings.forgetProfileValues()
        cron.clear()
        search.clear()
        vault.clear()
        refreshSessions()
        settings.refresh()
        cron.refresh()
    }

    /** The paired PC and the Hermes profile on it that requests are for right now. */
    private fun target() = Target(profiles.activeId, profile)

    /** False once the user moved to another PC or profile while a request for [asked] was in flight. */
    private fun stillOn(asked: Target) = target() == asked

    // ------------------------------------------------------------ sessions

    fun refreshSessions(quiet: Boolean = false) {
        val c = source ?: return
        val asked = target()
        if (!quiet) _sessions.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Fetch every page up front (only titles and stats, ~100 per request): search and the
                // drawer filters then cover all chats, and the drawer itself pages what it draws.
                val list = ArrayList<SessionSummary>()
                var more = true
                while (more && list.size < MAX_SESSIONS) {
                    val (page, next) = c.sessions(SESSIONS_PER_REQUEST, list.size, asked.profile)
                    list += page
                    more = next && page.isNotEmpty()
                }
                if (!stillOn(asked)) return@launch
                val items = list.distinctBy { s -> s.id }
                _sessions.update { it.copy(items = items, loading = false, hasMore = more, error = null) }
                drawerCache.putSessions(asked.profile, items)
                val open = list.firstOrNull { it.id == _chat.value.sessionId }
                if (open != null) _chat.update { it.copy(pinned = open.pinned, title = if (it.title == "New chat") open.displayTitle else it.title) }
                refreshProjects()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Sync failed: the saved list stays on screen (it was shown at start), with a note.
                if (stillOn(asked)) _sessions.update { it.copy(loading = false,
                    error = if (it.items.isEmpty()) describe(t) else "Showing the saved list: ${describe(t)}") }
            }
        }
    }

    /** Project membership for the drawer. Best effort: older bridges refuse `projects.tree`. */
    private suspend fun refreshProjects() {
        val g = gateway ?: return
        if (g.state.value != GatewayState.OPEN) return
        val asked = target()
        val tree = runCatching { g.call("projects.tree", scoped(JSONObject()), 15_000) }.getOrNull() ?: return
        if (!stillOn(asked)) return
        val projects = tree.optJSONArray("projects").objects().mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            val ids = p.optJSONArray("sessionIds")
            val members = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it)?.takeIf(String::isNotBlank) }.toSet()
            // Same root as desktop's projectRootCwd: the primary folder, else the first repo with one.
            val path = p.str("path")?.takeIf(String::isNotBlank)
                ?: p.optJSONArray("repos").objects().firstNotNullOfOrNull { r -> r.str("path")?.takeIf(String::isNotBlank) }
            ProjectRef(id, p.str("label") ?: id, members, path, auto = p.optBoolean("isAuto"))
        }
        if (projects != _sessions.value.projects) _sessions.update { it.copy(projects = projects) }
        drawerCache.putProjects(asked.profile, projects)
    }

    fun loadMoreSessions() {
        val c = source ?: return
        val cur = _sessions.value
        if (cur.loading || !cur.hasMore) return
        _sessions.update { it.copy(loading = true) }
        val asked = target()
        viewModelScope.launch {
            try {
                val (list, more) = c.sessions(SESSIONS_PER_REQUEST, cur.items.size, asked.profile)
                if (!stillOn(asked)) return@launch
                _sessions.update { s -> s.copy(items = (s.items + list).distinctBy { it.id }, loading = false, hasMore = more) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _sessions.update { it.copy(loading = false, error = describe(t)) }
            }
        }
    }

    /** Starts a blank chat; with [project] it is created in that project, like desktop's "New session in". */
    fun newChat(project: ProjectRef? = null) {
        openJob?.cancel()
        store.lastSessionId = null
        _chat.value = ChatState(draft = drafts.get(null), link = _chat.value.link, reasoning = store.reasoningEffort,
            project = project?.takeIf { it.path != null })
        _reasoning.value = store.reasoningEffort
    }

    /** Picks (or clears) the project of the new chat on screen. An opened chat keeps its folder. */
    fun chooseProject(id: String?) {
        if (_chat.value.sessionId != null) return
        val project = id?.let { pid -> _sessions.value.projects.firstOrNull { it.id == pid && it.path != null } }
        _chat.update { it.copy(project = project) }
    }

    /**
     * Creates a Hermes project on the PC (`projects.create`, same as the desktop's "New project") with
     * [path] as its primary folder, then selects it for the new chat. [done] gets null on success, else the error.
     */
    fun createProject(name: String, path: String, description: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val g = gateway
            if (g == null || g.state.value != GatewayState.OPEN) { done("Not connected to the PC."); return@launch }
            val params = scoped(JSONObject().put("name", name.trim()))
            if (path.isNotBlank()) params.put("primary_path", path.trim())
            if (description.isNotBlank()) params.put("description", description.trim())
            val result = runCatching { g.call("projects.create", params, 15_000) }
            result.exceptionOrNull()?.let { t ->
                if (t is CancellationException) throw t
                done(describe(t)); return@launch
            }
            val project = result.getOrNull()?.optJSONObject("project")
            val id = project?.str("id")
            // Like desktop's "New project": the description also lands as IDEA.md in the project folder.
            val ideaError = if (id != null && description.isNotBlank() && !project.str("primary_path").isNullOrBlank())
                writeIdea(id, description) else null
            refreshProjects()
            if (id != null) chooseProject(id)
            done(ideaError?.let { "Project created, but IDEA.md wasn't written: $it" })
        }
    }

    /** Sub-folders of a PC path for the project folder picker (bridge with the `folders` feature). */
    suspend fun browseFolders(path: String): io.github.nideta231.hermesremote.data.FolderListing =
        (client ?: throw IOException("Not connected to the PC.")).folders(path)

    suspend fun makeFolder(path: String): io.github.nideta231.hermesremote.data.FolderListing =
        (client ?: throw IOException("Not connected to the PC.")).makeFolder(path)

    /** Name, folder and description of a saved project, for the edit dialog. */
    suspend fun projectDetails(id: String): ProjectDetails {
        val g = gateway?.takeIf { it.state.value == GatewayState.OPEN } ?: throw IOException("Not connected to the PC.")
        val p = g.call("projects.get", scoped(JSONObject().put("id", id)), 15_000).optJSONObject("project")
            ?: throw IOException("Project not found")
        val path = p.str("primary_path")?.takeIf(String::isNotBlank)
        // IDEA.md wins over the stored text: it is what the desktop and the agent read.
        val idea = if (path == null) null else runCatching { client?.projectIdea(id, profileParam(profile)) }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()?.trimEnd()
        return ProjectDetails(id, p.str("name") ?: id, path, idea?.takeIf(String::isNotBlank) ?: p.str("description").orEmpty())
    }

    /**
     * Saves the edit dialog: rename (`projects.update`), change the folder (the new path becomes the
     * primary folder and the old one is detached; blank = no folder) and the description (stored on
     * the project and written to IDEA.md in its folder). [done] gets null or an error.
     */
    fun saveProject(before: ProjectDetails, name: String, path: String, description: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val g = gateway
            if (g == null || g.state.value != GatewayState.OPEN) { done("Not connected to the PC."); return@launch }
            val id = before.id
            val newPath = path.trim()
            val error = try {
                val fields = JSONObject()
                if (name.trim() != before.name) fields.put("name", name.trim())
                if (description.trim() != before.description.trim()) fields.put("description", description.trim())
                if (fields.length() > 0) g.call("projects.update", scoped(fields.put("id", id)), 15_000)
                if (newPath != (before.path ?: "")) {
                    if (newPath.isNotEmpty())
                        g.call("projects.add_folder", scoped(JSONObject().put("id", id).put("path", newPath).put("is_primary", true)), 15_000)
                    val stored = g.call("projects.get", scoped(JSONObject().put("id", id)), 15_000).optJSONObject("project")
                    val primary = stored?.str("primary_path")
                    // Detach the old folder (only when it is no longer the primary one, e.g. same path typed differently).
                    before.path?.takeIf { it != primary }?.let { old ->
                        g.call("projects.remove_folder", scoped(JSONObject().put("id", id).put("path", old)), 15_000)
                    }
                }
                // IDEA.md follows the description into the (new) folder; an emptied description removes it.
                val descChanged = description.trim() != before.description.trim()
                if (newPath.isNotEmpty() && (descChanged || newPath != (before.path ?: ""))) writeIdea(id, description) else null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                describe(t)
            }
            refreshProjects()
            done(error)
        }
    }

    /** Deletes the saved project in Hermes (desktop's "Delete…"). Its folder and files on the PC stay. */
    fun deleteProject(id: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val g = gateway
            if (g == null || g.state.value != GatewayState.OPEN) { done("Not connected to the PC."); return@launch }
            val error = runCatching { g.call("projects.delete", scoped(JSONObject().put("id", id)), 15_000) }
                .exceptionOrNull()?.let { if (it is CancellationException) throw it; describe(it) }
            if (error == null && _chat.value.project?.id == id) _chat.update { it.copy(project = null) }
            refreshProjects()
            done(error)
        }
    }

    /** Writes IDEA.md through the bridge; null on success, else why not. */
    private suspend fun writeIdea(id: String, text: String): String? {
        val c = client ?: return "Not connected to the PC."
        return runCatching { c.setProjectIdea(id, text, profileParam(profile)) }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            if (it is BridgeException && it.httpCode == 404 && it.code != "no_folder") "update the bridge on the PC" else describe(it)
        }
    }

    /** Save unsent composer text. Called on every keystroke; cheap enough to be synchronous. */
    fun setDraft(text: String) {
        drafts.put(_chat.value.sessionId, text)
        _chat.update { it.copy(draft = text) }
        updateSuggestions(text)
    }

    fun openSession(id: String, inProfile: String? = null) {
        // A notification names the chat's profile: move there first, so the chat and drawer match.
        inProfile?.let { switchProfile(it) }
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
        val c = source ?: return
        fun apply(value: Boolean) {
            if (_chat.value.sessionId == id) _chat.update { it.copy(pinned = value) }
            _sessions.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(pinned = value) else it }) }
        }
        apply(pinned)
        viewModelScope.launch {
            try {
                c.setPinned(id, pinned, profile)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                apply(!pinned)
                say(t)
            }
        }
    }

    /** Re-home a stored chat into a project's folder, like desktop's "Move to project". */
    fun moveToProject(id: String, projectId: String) {
        val g = gateway ?: return
        val project = _sessions.value.projects.firstOrNull { it.id == projectId && it.path != null } ?: return
        viewModelScope.launch {
            try {
                g.call("session.workspace.move", scoped(JSONObject().put("session_key", id).put("cwd", project.path)))
                _sessions.update { s ->
                    s.copy(projects = s.projects.map {
                        if (it.id == projectId) it.copy(sessionIds = it.sessionIds + id) else it.copy(sessionIds = it.sessionIds - id)
                    })
                }
                refreshProjects()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(t)
            }
        }
    }

    fun rename(id: String, title: String) {
        val c = source ?: return
        viewModelScope.launch {
            try {
                c.renameSession(id, title, profile)
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
                g.call("session.delete", scoped(JSONObject().put("session_id", id)))
                _sessions.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
                drafts.clear(id)
                refreshSessions(quiet = true)
            } catch (t: Throwable) { say(t) }
        }
    }

    // ------------------------------------------------------------ turns

    fun send(text: String) {
        val trimmed = text.trim()
        if ((_chat.value.attachments.isNotEmpty() || _chat.value.files.isNotEmpty()) && !trimmed.startsWith("/")) return sendMessage(trimmed, trimmed)
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) return runSlash(trimmed)
        sendMessage(trimmed.removePrefix("/"), trimmed.removePrefix("/"))
    }

    /** Submits [text]; the chat shows [display] (what the user typed) for it. */
    /** Queue [text] as the next turn while one runs (Hermes runs it once the current reply ends). */
    fun queue(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && _chat.value.attachments.isEmpty() && _chat.value.files.isEmpty()) return
        if (!_chat.value.busy || trimmed.startsWith("/")) return send(text)
        sendMessage(trimmed, trimmed, queued = true)
    }

    private fun sendMessage(text: String, display: String, queued: Boolean = false) {
        val trimmed = text.trim()
        val pictures = _chat.value.attachments
        val files = _chat.value.files
        if (trimmed.isEmpty() && pictures.isEmpty() && files.isEmpty()) return
        if (_chat.value.sending) {
            // Never drop a send silently: the text stays in the composer and the snackbar says why.
            _toast.value = "Still sending your last message…"
            return
        }
        val key = "u-${System.nanoTime()}"
        _suggestions.value = emptyList()
        _chat.update { it.copy(sending = true, draft = if (it.draft.trim() == display.trim()) "" else it.draft,
            attachments = emptyList(), files = emptyList(),
            items = it.items + ChatItem.User(key, fileLabel(display, files), pending = true, images = pictures, at = System.currentTimeMillis()),
            subagents = SubagentReducer.newTurn(it.subagents)) }
        drafts.put(_chat.value.sessionId, _chat.value.draft)
        viewModelScope.launch {
            try {
                // Pictures first: Hermes queues each on the session and the next prompt.submit takes them.
                val sent = pictures.map { uri ->
                    val (bytes, name) = ImageAttachments.read(app, uri)
                    val r = rpc("image.attach_bytes", { rid -> JSONObject().put("session_id", rid)
                        .put("content_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        .put("filename", name) }, 90_000)
                    r.str("path") ?: uri
                }
                // Files are staged on the PC; the message carries their @file: references.
                val refs = files.map { (uri, _) ->
                    val (bytes, name) = FileAttachments.read(app, uri)
                    val r = rpc("file.attach", { rid -> JSONObject().put("session_id", rid)
                        .put("data_url", FileAttachments.dataUrl(bytes, app.contentResolver.getType(android.net.Uri.parse(uri))))
                        .put("name", name) }, 120_000)
                    (r.str("ref_text") ?: throw IOException("Hermes didn't take $name")) to (r.str("path") ?: r.str("ref_path"))
                }.let { staged ->
                    val paths = staged.mapNotNull { it.second }
                    if (paths.isNotEmpty()) _chat.update { st -> st.copy(items = st.items.map {
                        if (it is ChatItem.User && it.key == key) it.copy(files = paths) else it }) }
                    staged.map { it.first }
                }
                if (sent.isNotEmpty()) _chat.update { st -> st.copy(items = st.items.map {
                    if (it is ChatItem.User && it.key == key) it.copy(images = sent) else it }) }
                val res = rpc("prompt.submit", { rid -> JSONObject().put("session_id", rid)
                    .put("text", withFileRefs(trimmed.ifEmpty { if (pictures.isNotEmpty()) "What do you see in this image?" else "" }, refs))
                    .apply { if (queued) put("queued", true) } }, 30_000)
                val status = res.str("status")
                SharedFiles.delete(app, pictures + files.map { it.first })
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
                        draft = if (st.draft.isNotEmpty()) st.draft else display,
                        attachments = (pictures + st.attachments).distinct(), files = (files + st.files).distinctBy { f -> f.first })
                }
                drafts.put(_chat.value.sessionId, _chat.value.draft)
                say(t)
            }
        }
    }

    /**
     * Edit a sent message, like the desktop's edit: Hermes drops [item] and everything after it,
     * then answers [newText] in its place. Needs the message's row on the PC (known once the chat
     * was read back, i.e. after its reply finished).
     */
    fun editMessage(item: ChatItem.User, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        if (_chat.value.busy || _chat.value.sending) { _toast.value = "Wait for the current reply to finish, then edit."; return }
        val rowId = item.rowId ?: run { _toast.value = "This message isn't saved on the PC yet. Try again in a moment."; return }
        val before = _chat.value.items
        val at = before.indexOfFirst { it.key == item.key }.takeIf { it >= 0 } ?: return
        val first = before.take(at).none { it is ChatItem.User }
        val key = "u-${System.nanoTime()}"
        _chat.update { it.copy(sending = true, items = before.take(at) + ChatItem.User(key, text, pending = true, at = System.currentTimeMillis())) }
        viewModelScope.launch {
            try {
                rpc("prompt.submit", { rid -> JSONObject().put("session_id", rid).put("text", text)
                    .put("confirm_truncate", true).put("truncate_before_row_id", rowId)
                    .apply { if (first) put("confirm_empty_truncate", true) } }, 30_000)
                _chat.update { st -> st.copy(sending = false, status = if (st.status == "idle") "working" else st.status,
                    items = st.items.map { if (it is ChatItem.User && it.key == key) it.copy(pending = false) else it }) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _chat.update { it.copy(sending = false, items = before) }
                if (t is RpcException && t.code == 4018) {
                    _toast.value = "That message changed on the PC. The chat was reloaded; try again."
                    _chat.value.sessionId?.let { resume(it, quiet = true) }
                } else say(t)
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

    /** Answer the open masked prompt. Empty [value] skips it (Hermes treats that as declined). */
    fun answerSecret(identifier: String, value: String) {
        val g = gateway ?: return
        val open = LiveReducer.openSecret(_chat.value.items) ?: return
        val r = open.request
        if (!g.reply(r.id, secretAnswer(r, identifier, value))) return say(IOException("Not connected to your PC"))
        LiveLink.answered(app, r.id)
        _chat.update { it.copy(items = LiveReducer.resolve(it.items, r.id, if (value.isEmpty()) "skipped" else "sent"), status = "working") }
    }

    /** Pictures picked in the composer; they go with the next message. */
    fun addAttachments(uris: List<String>) {
        if (uris.isEmpty()) return
        val room = ImageAttachments.MAX_PER_MESSAGE - _chat.value.attachments.size
        if (room <= 0) { _toast.value = "Up to ${ImageAttachments.MAX_PER_MESSAGE} pictures per message."; return }
        if (uris.size > room) _toast.value = "Up to ${ImageAttachments.MAX_PER_MESSAGE} pictures per message."
        _chat.update { it.copy(attachments = (it.attachments + uris.take(room)).distinct()) }
    }

    fun removeAttachment(uri: String) {
        _chat.update { it.copy(attachments = it.attachments - uri) }
        SharedFiles.delete(app, listOf(uri))
    }

    /** Files picked in the composer (any type); they go with the next message. */
    fun addFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        val room = FileAttachments.MAX_PER_MESSAGE - _chat.value.files.size
        if (uris.size > room) _toast.value = "Up to ${FileAttachments.MAX_PER_MESSAGE} files per message."
        if (room <= 0) return
        val picked = uris.take(room).map { it to FileAttachments.name(app, it) }
        _chat.update { st -> st.copy(files = (st.files + picked).distinctBy { it.first }) }
    }

    fun removeFile(uri: String) {
        _chat.update { st -> st.copy(files = st.files.filterNot { it.first == uri }) }
        SharedFiles.delete(app, listOf(uri))
    }

    /** A share from another app: its streams are copied now, while the sender's grant still holds. */
    fun offerShare(shared: SharedContent) {
        cancelShare()
        shareJob = viewModelScope.launch { _pendingShare.value = SharedFiles.copy(app, shared) }
    }

    /** Puts the pending share in the composer of a new chat, or of the chat on screen. Nothing is sent. */
    fun acceptShare(toNewChat: Boolean) {
        val shared = _pendingShare.value ?: return
        _pendingShare.value = null
        if (toNewChat) newChat()
        if (shared.text.isNotEmpty()) setDraft(Share.mergeDraft(_chat.value.draft, shared.text))
        addAttachments(shared.images)
        addFiles(shared.files)
        val kept = _chat.value.attachments + _chat.value.files.map { it.first }
        val left = shared.uris - kept.toSet()
        SharedFiles.delete(app, left)
        val dropped = shared.dropped + left.size
        if (dropped > 0) _toast.value = "Left out $dropped shared item${if (dropped == 1) "" else "s"}: " +
            "too many, over ${FileAttachments.MAX_BYTES / (1024 * 1024)} MB, or unreadable."
    }

    fun cancelShare() {
        shareJob?.cancel()
        _pendingShare.value?.let { SharedFiles.delete(app, it.uris) }
        _pendingShare.value = null
    }

    /** The bubble text for a message with files: what was typed, then one line per file. */
    private fun fileLabel(display: String, files: List<Pair<String, String>>): String =
        (listOf(display.trim()).filter { it.isNotEmpty() } + files.map { "📎 ${it.second}" }).joinToString("\n")

    /** Bytes of a chat image: a phone uri (still sending) or a PC path, fetched over the bridge and cached. */
    suspend fun loadImage(ref: String): ByteArray? = ImageAttachments.load(app, ref) { path ->
        val c = source ?: return@load null
        try { c.media(path) } catch (e: BridgeException) {
            // A picture outside Hermes' image folders (e.g. a chart saved in a project): the chat names it.
            val sid = _chat.value.sessionId
            val bridge = client
            if (sid == null || bridge == null || e.httpCode != 403) throw e
            val f = java.io.File(app.cacheDir, "chat-images/${path.hashCode().toUInt()}")
            bridge.chatFile(sid, path, profileParam(profile), f)
            f.readBytes().also { f.delete() }
        }
    }

    /** A file the open chat names, downloaded from the PC into the app's cache (reused when already there). */
    suspend fun fetchChatFile(path: String): java.io.File {
        val c = client ?: throw IOException("Not connected to the PC")
        val sid = _chat.value.sessionId ?: throw IOException("This chat isn't saved yet")
        val name = fileName(path).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }
        val f = java.io.File(app.cacheDir, "chat-files/${(sid + path).hashCode().toUInt()}/$name")
        if (!f.isFile) c.chatFile(sid, path, profileParam(profile), f)
        return f
    }

    /**
     * Info for a chat file or picture: a phone pick still sending is read from the phone, a PC path
     * is asked from the bridge. An older bridge (no `file_info`) still gets the name and path.
     */
    suspend fun chatFileInfo(ref: String): FileInfo {
        if (ref.startsWith("content://") || ref.startsWith("file://")) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val uri = android.net.Uri.parse(ref)
            var name = fileName(ref); var size: Long? = null
            runCatching {
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                        c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
            FileInfo(name, ref, size = size, mime = app.contentResolver.getType(uri), note = "On this phone, still being sent")
        }
        if (ref.startsWith("data:")) return FileInfo("Picture", "Inline picture (no file on the PC)",
            size = (ref.substringAfter(',').length * 3L / 4), mime = ref.removePrefix("data:").substringBefore(';'))
        val c = client
        val sid = _chat.value.sessionId
        if (c == null || sid == null || "file_info" !in _features.value)
            return FileInfo(fileName(ref), ref, note = if (c != null && sid != null) "Update the PC bridge to see the size" else null)
        return parseFileInfo(c.chatFileInfo(sid, ref, profileParam(profile)), ref)
    }

    // ------------------------------------------------------------ models + reasoning

    fun loadModels() {
        val g = gateway ?: return
        viewModelScope.launch {
            try {
                // With a chat open, its own profile decides; otherwise the one in use.
                val params = _chat.value.runtimeId?.let { JSONObject().put("session_id", it) } ?: scoped(JSONObject())
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
            val params = _chat.value.runtimeId?.let { JSONObject().put("session_id", it) } ?: scoped(JSONObject())
            params.put("text", text)
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

    private companion object {
        /** Bridge protocol this app speaks (the WebSocket relay). */
        const val MIN_BRIDGE_PROTOCOL = 2
        /** The bridge caps one /v1/sessions page at 100. */
        const val SESSIONS_PER_REQUEST = 100
        /** Upper bound for the drawer's up-front fetch; past it the old scroll-to-load takes over. */
        const val MAX_SESSIONS = 2000
        val STREAM_EVENTS = setOf("message.delta", "reasoning.delta", "thinking.delta") // coalesced per frame
        /** Commands that change the transcript on the PC: re-read it after they run. */
        val RELOADING_COMMANDS = setOf("retry", "undo", "rollback", "clear")
    }
}
