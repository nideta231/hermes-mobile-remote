package io.github.nideta231.hermesremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.nideta231.hermesremote.data.DEFAULT_HERMES_PROFILE
import io.github.nideta231.hermesremote.data.HostKind
import io.github.nideta231.hermesremote.data.Notifier
import io.github.nideta231.hermesremote.data.hiddenFeatures
import io.github.nideta231.hermesremote.ui.LocalHiddenFeatures
import io.github.nideta231.hermesremote.ui.ChatActions
import io.github.nideta231.hermesremote.ui.BridgeAdminActions
import io.github.nideta231.hermesremote.ui.BlueprintActions
import io.github.nideta231.hermesremote.ui.BlueprintScreen
import io.github.nideta231.hermesremote.ui.BlueprintDrawerActions
import io.github.nideta231.hermesremote.ui.BridgeAdminScreen
import io.github.nideta231.hermesremote.ui.ProjectActions
import io.github.nideta231.hermesremote.ui.CronActions
import io.github.nideta231.hermesremote.ui.CronScreen
import io.github.nideta231.hermesremote.ui.PcActions
import io.github.nideta231.hermesremote.ui.PcScreen
import io.github.nideta231.hermesremote.ui.TerminalScreen
import io.github.nideta231.hermesremote.ui.GraphScreen
import io.github.nideta231.hermesremote.ui.NotesScreen
import io.github.nideta231.hermesremote.ui.NotesActions
import io.github.nideta231.hermesremote.ui.BridgeUpdateActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import io.github.nideta231.hermesremote.ui.VaultActions
import io.github.nideta231.hermesremote.ui.VaultScreen
import io.github.nideta231.hermesremote.ui.ChatScreen
import io.github.nideta231.hermesremote.ui.HermesTheme
import io.github.nideta231.hermesremote.ui.ThemePrefs
import io.github.nideta231.hermesremote.ui.isDark
import io.github.nideta231.hermesremote.data.Root
import io.github.nideta231.hermesremote.data.rootScreen
import io.github.nideta231.hermesremote.ui.PairScreen
import io.github.nideta231.hermesremote.ui.SignInAgainScreen
import io.github.nideta231.hermesremote.ui.SessionActions
import io.github.nideta231.hermesremote.ui.SessionsPane
import io.github.nideta231.hermesremote.ui.UpdateSheet
import io.github.nideta231.hermesremote.ui.ShareTargetSheet
import io.github.nideta231.hermesremote.data.SharedFiles
import io.github.nideta231.hermesremote.ui.UpdateStrip
import io.github.nideta231.hermesremote.ui.NotesSheet
import io.github.nideta231.hermesremote.data.fullChangelog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.nideta231.hermesremote.ui.SettingsActions
import io.github.nideta231.hermesremote.ui.SettingsScreen
import io.github.nideta231.hermesremote.ui.linkHealth
import io.github.nideta231.hermesremote.data.QrImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chat is home; the others are pages pushed on top of it. */
enum class Page { CHAT, SCHEDULED, PC, TERMINAL, VAULT, GRAPH, NOTES, BRIDGE, BLUEPRINT, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pairUri = mutableStateOf<String?>(null)
    private val showChat = mutableStateOf(false)
    /** A page to open, from a home-screen widget tap. */
    private val openPage = mutableStateOf<Page?>(null)
    /** (project id, name) of a blueprint a notification asked to open. */
    private val openBlueprint = mutableStateOf<Pair<String, String>?>(null)

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairUri.value = it }
    }
    private val imageError = mutableStateOf<String?>(null)

    // A picture of the pairing QR: a screenshot, or the PNG `pair --qr-png` wrote and a chat
    // app (Telegram) passed along. GetContent also offers Files/Drive, not just the gallery.
    private val imagePicker = registerForActivityResult(GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        imageError.value = null
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { QrImage.decode(this@MainActivity, uri) }.getOrNull() }
            when {
                text == null -> imageError.value = "No QR code found in that picture. Try a sharper or uncropped image."
                !text.startsWith("hermesremote:") -> imageError.value = "That QR code isn't a Hermes Remote pairing code."
                else -> pairUri.value = text
            }
        }
    }

    // Pictures for the chat: the system photo picker (no storage permission needed). The uris stay
    // readable while the app runs, which covers picking then sending.
    private val chatPicker = registerForActivityResult(PickMultipleVisualMedia(io.github.nideta231.hermesremote.data.ImageAttachments.MAX_PER_MESSAGE)) { uris ->
        vm.addAttachments(uris.map { it.toString() })
    }

    // Files for the chat: the system document picker, any type. Read once when the message is sent.
    private val filePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris.map { it.toString() })
    }

    // Asked right after pairing: without it a run still works, but the app can't say it
    // finished while the phone is in a pocket.
    private val notifPermission = registerForActivityResult(RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity gets the launch intent again; its share was already offered.
        handleIntent(intent, restored = savedInstanceState != null)
        Notifier.ensureChannels(this)
        // Upgrades never pass through pairing, so ask here too. Android stops prompting after two declines.
        if (vm.pairing.value != null) askForNotifications()
        ThemePrefs.init(this)
        setContent {
            val dark = ThemePrefs.mode.isDark(androidx.compose.foundation.isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            HermesTheme { Surface(color = MaterialTheme.colorScheme.background) { App() } }
        }
    }

    /** Android 13+ gates notifications behind a runtime permission; below that they are on. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?, restored: Boolean = false) {
        if (intent != null && !restored && takeShare(intent)) return
        // Tapped a notification: go straight to the chat it is about.
        intent?.getStringExtra(Notifier.EXTRA_OPEN_SESSION)?.let { sid ->
            intent.removeExtra(Notifier.EXTRA_OPEN_SESSION)
            val profile = intent.getStringExtra(Notifier.EXTRA_PROFILE) ?: DEFAULT_HERMES_PROFILE
            intent.removeExtra(Notifier.EXTRA_PROFILE)
            vm.openSession(sid, profile)
            showChat.value = true
        }
        intent?.getStringExtra(Notifier.EXTRA_OPEN_BLUEPRINT)?.let { v ->
            intent.removeExtra(Notifier.EXTRA_OPEN_BLUEPRINT)
            openBlueprint.value = v.substringBefore('\t') to v.substringAfter('\t', "")
        }
        intent?.getStringExtra(io.github.nideta231.hermesremote.data.PcWidgets.EXTRA_PAGE)?.let { name ->
            intent.removeExtra(io.github.nideta231.hermesremote.data.PcWidgets.EXTRA_PAGE)
            runCatching { Page.valueOf(name) }.getOrNull()?.let { openPage.value = it }
        }
        val data = intent?.data ?: return
        if (data.scheme == "hermesremote") {
            // A new code while paired adds that PC (or re-pairs it) without dropping the current one.
            if (vm.pairing.value != null || vm.gatewayHost.value != null) vm.startAddingPc()
            pairUri.value = data.toString()
        }
    }

    /** A share from another app (text, link, pictures, files). True when [intent] was one. */
    private fun takeShare(intent: Intent): Boolean {
        val shared = SharedFiles.fromIntent(this, intent) ?: return false
        when {
            vm.pairing.value == null && vm.gatewayHost.value?.signedOut != false ->
                android.widget.Toast.makeText(this, "Pair Hermes Remote with your PC first, then share again.", android.widget.Toast.LENGTH_LONG).show()
            shared.isEmpty -> android.widget.Toast.makeText(this, "Nothing Hermes Remote can use was shared.", android.widget.Toast.LENGTH_LONG).show()
            else -> { vm.offerShare(shared); showChat.value = true }
        }
        return true
    }

    private fun scan() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code shown by `hermes-remote-bridge pair`")
            .setBeepEnabled(false).setOrientationLocked(false))
    }

    /** The gateway's own login runs in a Custom Tab (not a WebView) so its login cookie survives. */
    private fun openCustomTab(url: String) = runOnUiThread {
        androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, android.net.Uri.parse(url))
    }

    private fun pickImage() = runCatching { imagePicker.launch("image/*") }

    @Composable
    private fun App() {
        val pairing by vm.pairing.collectAsState()
        val adding by vm.addingPc.collectAsState()
        val gw by vm.gatewayHost.collectAsState()
        val hasHost = pairing != null || gw != null
        val repairDevice by vm.repairDevice.collectAsState()
        val pcs by vm.pcs.collectAsState()
        val update by vm.updates.update.collectAsState()
        var pairUpdateSheet by rememberSaveable { mutableStateOf(false) }
        AnimatedContent(rootScreen(pairing != null, gw?.signedOut, adding), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "root") { root ->
            if (root == Root.SIGN_IN) {
                gw?.let { h ->
                    SignInAgainScreen(h.name, h.baseUrl, onSignIn = { raw -> vm.signInGateway(raw, ::openCustomTab, h.id) },
                        onCancelSignIn = vm::cancelGatewaySignIn, onAddHost = vm::startAddingPc)
                }
            } else if (root == Root.PAIR) Column(Modifier.fillMaxSize()) {
                // Not paired yet, so no drawer or Settings: the update is offered here (statusbar-padded).
                if (update.available != null || update.progress != null) Box(Modifier.statusBarsPadding()) {
                    UpdateStrip(update.copy(dismissed = null), onOpen = { pairUpdateSheet = true }, onDismiss = null)
                }
                Box(Modifier.weight(1f)) {
                PairScreen(pairUri.value, onScan = ::scan, onPickImage = { pickImage() }, onPair = {
                    vm.pair(it).also { err ->
                        if (err == null) {
                            pairUri.value = null
                            imageError.value = null
                            askForNotifications()
                        }
                    }
                }, onSignInGateway = { raw ->
                    vm.signInGateway(raw, ::openCustomTab).also { err ->
                        if (err == null) {
                            android.widget.Toast.makeText(this@MainActivity, "Signed in. The gateway is in your host list.", android.widget.Toast.LENGTH_LONG).show()
                            if (hasHost) vm.cancelAddingPc()
                        }
                    }
                }, onCancelSignIn = vm::cancelGatewaySignIn, imageError = imageError.value,
                    onCancel = if (hasHost) ({ pairUri.value = null; imageError.value = null; vm.cancelAddingPc() }) else null,
                    repairDevice = repairDevice, insetTop = update.available == null && update.progress == null,
                    otherPcs = pcs.filter { !it.active }, onSwitchPc = { vm.cancelAddingPc(); vm.switchPc(it) })
                }
                if (pairUpdateSheet && update.available != null)
                    UpdateSheet(update, onInstall = vm.updates::installUpdate, onDismiss = { pairUpdateSheet = false })
            } else {
                val hidden = hiddenFeatures(if (gw != null) HostKind.GATEWAY else HostKind.BRIDGE)
                androidx.compose.runtime.CompositionLocalProvider(LocalHiddenFeatures provides hidden) { PairedApp() }
            }
        }
        val offer by vm.linkOffer.collectAsState()
        offer?.let { o ->
            val t = io.github.nideta231.hermesremote.ui.offerText(o)
            androidx.compose.material3.AlertDialog(onDismissRequest = vm::declineLinkOffer,
                title = { androidx.compose.material3.Text(t.title) },
                text = { androidx.compose.material3.Text(t.body) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = vm::acceptLinkOffer) { androidx.compose.material3.Text(t.confirm) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = vm::declineLinkOffer) { androidx.compose.material3.Text(t.dismiss) } })
        }
    }

    @Composable
    private fun PairedApp() {
        val pairing by vm.pairing.collectAsState()
        val chat by vm.chat.collectAsState()
        val sessionSearch by vm.search.state.collectAsState()
        val sessions by vm.sessions.collectAsState()
        val sessionView by vm.sessionView.collectAsState()
        val groupOrder by vm.groupOrder.collectAsState()
        val system by vm.settings.system.collectAsState()
        val models by vm.models.collectAsState()
        val choice by vm.modelChoice.collectAsState()
        val confirm by vm.confirm.collectAsState()
        val conn by vm.connection.collectAsState()
        val reasoning by vm.reasoning.collectAsState()
        val suggestions by vm.suggestions.collectAsState()
        val openModelPicker by vm.openModelPicker.collectAsState()
        val update by vm.updates.update.collectAsState()
        val toast by vm.toast.collectAsState()
        val pcs by vm.pcs.collectAsState()
        val hermesProfiles by vm.hermesProfiles.collectAsState()
        val cron by vm.cron.state.collectAsState()
        val vaultState by vm.vault.state.collectAsState()
        val claudeUsage by vm.claudeUsage.collectAsState()
        val pcState by vm.monitor.state.collectAsState()
        val bridgeUpdateState by vm.bridgeUpdate.state.collectAsState()
        val notesState by vm.notes.state.collectAsState()
        val bridgeAdminState by vm.bridgeAdmin.state.collectAsState()
        val heldUpdate = io.github.nideta231.hermesremote.data.ParkedSockets.confirmText(bridgeAdminState.parked,
            bridgeAdminState.parkedSupported, io.github.nideta231.hermesremote.data.HeldAction.Update)
        val blueprintState by vm.blueprints.state.collectAsState()
        val seenTick by vm.blueprints.seenTick.collectAsState()
        var blueprintProject by rememberSaveable { mutableStateOf<String?>(null) }
        var blueprintName by rememberSaveable { mutableStateOf("") }
        val features by vm.features.collectAsState()
        val unauthorized by vm.unauthorized.collectAsState()
        val pendingShare by vm.pendingShare.collectAsState()
        var updateSheet by rememberSaveable { mutableStateOf(false) }
        var changelogSheet by rememberSaveable { mutableStateOf(false) }

        var page by rememberSaveable { mutableStateOf(Page.CHAT) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.consumeToast() } }
        val chatToast by vm.chatToast.collectAsState()
        LaunchedEffect(chatToast) {
            val t = chatToast ?: return@LaunchedEffect
            vm.consumeChatToast()
            if (snack.showSnackbar(t.text, actionLabel = "View chat", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                vm.openSession(t.sessionId); page = Page.CHAT
            }
        }
        if (showChat.value) { page = Page.CHAT; showChat.value = false; scope.launch { drawer.close() } }

        val wide = LocalConfiguration.current.screenWidthDp >= 720
        val health = linkHealth(conn, system.error != null)

        fun go(p: Page) {
            page = p
            if (p == Page.SETTINGS) { vm.settings.refresh(); if ("blueprints" in features) vm.blueprints.refreshList() }
            scope.launch { drawer.close() }
        }

        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = !drawer.isOpen && page != Page.CHAT) { page = if (page == Page.VAULT) Page.SETTINGS else Page.CHAT }
        // Opened from a home-screen widget.
        if (openPage.value != null) { page = openPage.value!!; openPage.value = null; scope.launch { drawer.close() } }
        openBlueprint.value?.let { (pid, name) ->
            openBlueprint.value = null
            blueprintProject = pid; blueprintName = name; page = Page.BLUEPRINT; scope.launch { drawer.close() }
        }
        val gatewayHost by vm.gatewayHost.collectAsState()
        // A gateway host has no bridge monitor, but Hermes serves its own basic stats.
        val hasMonitor = "monitor" in features || gatewayHost != null
        val hasTerminal = "terminal" in features

        val projectActions = remember(vm) {
            ProjectActions(create = vm::createProject, details = vm::projectDetails, save = vm::saveProject, delete = vm::deleteProject)
        }.let { base ->
            if ("folders" in features) ProjectActions(base.create, base.details, base.save, base.delete,
                browse = vm::browseFolders, makeFolder = vm::makeFolder) else base
        }
        val sessionActions = SessionActions(
            open = { vm.openSession(it); go(Page.CHAT) },
            refresh = vm::refreshSessions,
            loadMore = vm::loadMoreSessions,
            setView = vm::setSessionView,
            moveGroup = vm::moveSessionGroup,
            moveToProject = vm::moveToProject,
            resetGroupOrder = vm::resetGroupOrder,
            rename = vm::rename,
            delete = vm::delete,
            setPinned = vm::setPinned,
            newChat = { vm.newChat(); go(Page.CHAT) },
            newChatIn = { id -> vm.newChat(); vm.chooseProject(id); go(Page.CHAT) },
            projects = projectActions,
            openSettings = { go(Page.SETTINGS) },
            switchPc = { vm.switchPc(it); go(Page.CHAT) },
            addPc = { scope.launch { drawer.close() }; vm.startAddingPc() },
            renamePc = vm::renamePc,
            forgetPc = vm::forgetPc,
            linkPcs = vm::linkPcs,
            unlinkPc = vm::unlinkPc,
            switchProfile = { vm.switchProfile(it); go(Page.CHAT) },
            openScheduled = { go(Page.SCHEDULED) },
            openVault = { go(Page.VAULT) },
            openGraph = { go(Page.GRAPH) },
            openNotes = if ("notes" in features) ({ go(Page.NOTES) }) else null,
            openBridge = if ("admin" in features) ({ go(Page.BRIDGE) }) else null,
            blueprints = if ("blueprints" in features) BlueprintDrawerActions(have = blueprintState.have.keys,
                needsYou = blueprintState.have.values.filter { it.needsYou > 0 || vm.blueprints.seen?.projectUnread(it.projectId, it.latest) == true }
                    .map { it.projectId }.toSet().also { seenTick }, trash = blueprintState.trash,
                open = { p -> blueprintProject = p.id; blueprintName = p.label; go(Page.BLUEPRINT) },
                add = { p -> vm.blueprints.create(p.id, p.label, p.path) { blueprintProject = p.id; blueprintName = p.label; go(Page.BLUEPRINT) } },
                remove = vm.blueprints::trash, restore = vm.blueprints::restore, purge = vm.blueprints::purge) else null,
            bridgeUpdate = if ("bridge_update" in features) BridgeUpdateActions(bridgeUpdateState, vm.bridgeUpdate::check, vm.bridgeUpdate::start, heldUpdate, vm.bridgeAdmin::refresh) else null,
            openPc = if (hasMonitor) ({ go(Page.PC) }) else null,
            openTerminal = if (hasTerminal) ({ go(Page.TERMINAL) }) else null,
            repairPc = vm::startRepair,
            search = vm.search::search,
            openFound = { id, profile -> vm.openSession(id, profile); go(Page.CHAT) },
        )
        val chatActions = ChatActions(
            send = vm::send, stop = vm::stop, steer = vm::steer, queue = vm::queue, approve = vm::answerApproval, stopSubagent = vm::stopSubagent, stopProcess = vm::stopProcess,
            followSubagent = vm.subagentOutput::follow, steerSubagent = vm.subagentOutput::steer,
            openSubagentSession = { vm.openSession(it) },
            answerClarify = vm::answerClarify, draft = vm::setDraft,
            togglePin = vm::togglePin, newChat = { vm.newChat() },
            openDrawer = { vm.refreshSessions(); if (wide) ThemePrefs.pickSidebarHidden(false) else scope.launch { drawer.open() } },
            hideSidebar = if (wide && !ThemePrefs.sidebarHidden) ({ ThemePrefs.pickSidebarHidden(true) }) else null,
            openSettings = { go(Page.SETTINGS) },
            loadModels = vm::loadModels, chooseModel = vm::chooseModel, dismissConfirm = vm::dismissConfirm,
            setReasoning = vm::setReasoning, modelPickerOpened = vm::modelPickerOpened,
            chooseProject = vm::chooseProject, projects = projectActions,
            pickImages = { runCatching { chatPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) } },
            pickFiles = { runCatching { filePicker.launch(arrayOf("*/*")) } }, removeFile = vm::removeFile,
            removeAttachment = vm::removeAttachment, loadImage = vm::loadImage, fetchFile = vm::fetchChatFile, fileInfo = vm::chatFileInfo, answerSecret = vm::answerSecret,
            edit = vm::editMessage,
        )
        val settingsActions = SettingsActions(
            refresh = vm.settings::refresh, unpair = vm::unpair, setApprovalMode = vm.settings::setApprovalMode,
            useTransport = vm::useTransport, useAuto = vm::useAutoTransport,
            checkUpdate = { vm.updates.checkForUpdate() }, installUpdate = vm.updates::installUpdate,
            openUpdate = { updateSheet = true }, openChangelog = { changelogSheet = true },
            loadModels = vm::loadModels, setDefaultModel = vm.settings::setDefaultModel, dismissDefaultConfirm = vm.settings::dismissDefaultConfirm,
            openVault = { page = Page.VAULT },
            repair = vm::startRepair,
            openBridge = if ("admin" in features) ({ page = Page.BRIDGE }) else null,
            bridgeUpdate = if ("bridge_update" in features) BridgeUpdateActions(bridgeUpdateState, vm.bridgeUpdate::check, vm.bridgeUpdate::start, heldUpdate, vm.bridgeAdmin::refresh) else null,
            boardDefaults = if ("blueprints" in features) blueprintState.defaults else null,
            setBoardDefaults = vm.blueprints::setDefaults,
            boardInstructions = if ("blueprints" in features) blueprintState.defaultInstructions else null,
            setBoardInstructions = vm.blueprints::setDefaultInstructions,
        )
        val pcActions = PcActions(watch = { vm.monitor.watch() }, unwatch = vm.monitor::stop, refresh = vm.monitor::refresh,
            setRange = vm.monitor::setRange, setSort = vm.monitor::setSort, setQuery = vm.monitor::setQuery,
            stopProcess = vm.monitor::stopProcess, refreshClaude = vm.monitor::refreshClaude,
            openTerminal = if (hasTerminal) ({ page = Page.TERMINAL }) else null,
            runSpeedTest = if ("speedtest" in features) vm.monitor::runSpeedTest else null, setTracking = vm.monitor::setTracking)
        val cronActions = CronActions(refresh = vm.cron::refresh, loadRuns = vm.cron::loadRuns,
            pause = vm.cron::pause, resume = vm.cron::resume, runNow = vm.cron::runNow, create = vm.cron::create)
        val openUpdate = { scope.launch { drawer.close() }; updateSheet = true }

        val content: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(page, transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
                    (slideInHorizontally(spec) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(spec) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(180)))
                }, label = "page") { p ->
                    when (p) {
                        Page.CHAT -> ChatScreen(chat, health, models, vm.shownModel(models, chat), reasoning, suggestions,
                            openModelPicker, confirm, showMenuButton = !wide || ThemePrefs.sidebarHidden, actions = chatActions,
                            update = update, onOpenUpdate = openUpdate, onDismissUpdate = vm.updates::dismissUpdateBanner,
                            projects = sessions.projects.filter { it.path != null || (!it.auto && it.id != io.github.nideta231.hermesremote.data.SessionView.NO_PROJECT) },
                            profile = hermesProfiles.headerLabel,
                            claudeUsage = if (hasMonitor) claudeUsage else null,
                            onOpenMonitor = if (hasMonitor) ({ go(Page.PC) }) else null,
                            loadContext = vm::contextBreakdown, loadUsageHistory = vm::claudeHistory,
                            onRefreshUsage = vm::refreshClaudeUsage)
                        Page.SCHEDULED -> CronScreen(cron, hermesProfiles.active.takeIf { hermesProfiles.items.size > 1 }, cronActions, onBack = { page = Page.CHAT })
                        Page.PC -> PcScreen(pcState, pcActions, onBack = { page = Page.CHAT })
                        Page.TERMINAL -> TerminalScreen(open = vm::openTerminal, onBack = { page = Page.CHAT })
                        Page.BLUEPRINT -> blueprintProject?.let { pid ->
                            BlueprintScreen(pid, blueprintName, blueprintState, BlueprintActions(open = vm.blueprints::open, close = vm.blueprints::stop,
                                apply = { ops, done -> vm.blueprints.apply(*ops, onApplied = done) }, trash = vm.blueprints::trash,
                                askHermes = { b, what -> vm.askHermesBlueprint(b, what); page = Page.CHAT },
                                fetchFile = vm.blueprints::file, fileInfo = vm.blueprints::fileInfo,
                                unread = { c -> seenTick.let { vm.blueprints.seen?.unread(pid, c) ?: 0 } },
                                markSeen = { c -> vm.blueprints.markSeen(pid, c) },
                                openGlobalSettings = { vm.blueprints.refreshList(); page = Page.SETTINGS },
                                upload = vm::uploadBlueprintFile,
                                resolved = { b, c, m -> vm.blueprintResolved(b, c, m) },
                                answered = { b, c, m, a -> vm.blueprintAnswered(b, c, m, a) }),
                                onBack = { vm.blueprints.refreshList(); page = Page.CHAT })
                        } ?: run { page = Page.CHAT }
                        Page.BRIDGE -> BridgeAdminScreen(bridgeAdminState, BridgeAdminActions(refresh = vm.bridgeAdmin::refresh,
                            fix = vm.bridgeAdmin::fix, restart = vm.bridgeAdmin::restart, trust = vm.bridgeAdmin::trustCurrent,
                            untrust = vm.bridgeAdmin::untrust, revoke = vm.bridgeAdmin::revoke, loadLogs = vm.bridgeAdmin::loadLogs,
                            update = if ("bridge_update" in features) BridgeUpdateActions(bridgeUpdateState, vm.bridgeUpdate::check, vm.bridgeUpdate::start, heldUpdate, vm.bridgeAdmin::refresh) else null),
                            onBack = { page = Page.CHAT })
                        Page.NOTES -> NotesScreen(notesState, NotesActions(watch = vm.notes::watch, unwatch = vm.notes::stop,
                            setQuery = vm.notes::setQuery, setLabel = vm.notes::setLabel, setArchived = vm.notes::setArchived,
                            create = { list, done -> vm.notes.create(list, done) }, edit = { id, f -> vm.notes.edit(id) { f(it) } },
                            closed = vm.notes::closed, opened = { vm.notes.editing = it }, archive = vm.notes::setArchived, delete = { vm.notes.delete(it) },
                            askHermes = { n -> vm.newChat(); vm.setDraft("Look at my note \"${n.title.ifBlank { n.id }}\" (id ${n.id}, personal-notes skill) and ") ; page = Page.CHAT }),
                            onBack = { page = Page.CHAT })
                        Page.GRAPH -> GraphScreen(sessions.items, sessions.projects, onOpenChat = { vm.openSession(it); page = Page.CHAT },
                            onBack = { page = Page.CHAT })
                        Page.VAULT -> VaultScreen(vaultState, VaultActions(refresh = vm.vault::refresh, save = vm.vault::save,
                            remove = vm.vault::remove), onBack = { page = Page.SETTINGS })
                        Page.SETTINGS -> SettingsScreen(system, pairing, conn, update, settingsActions, onBack = { page = Page.CHAT }, models = models)
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
            }
            if (unauthorized) {
                AlertDialog(onDismissRequest = {}, title = { Text("This PC no longer accepts this phone") },
                    text = { Text("Its pairing was revoked or replaced on the PC. Re-pair to get back in; chats, drafts and the PC's name stay.") },
                    confirmButton = { TextButton(onClick = vm::startRepair) { Text("Re-pair") } },
                    dismissButton = { TextButton(onClick = { vm.dismissUnauthorized(); page = Page.SETTINGS }) { Text("Later") } })
            }
            if (updateSheet && update.available != null) UpdateSheet(update, onInstall = vm.updates::installUpdate, onDismiss = { updateSheet = false })
            pendingShare?.let { shared ->
                ShareTargetSheet(shared, openChat = chat.title.takeIf { chat.sessionId != null },
                    onPick = { toNew -> vm.acceptShare(toNew); page = Page.CHAT }, onDismiss = vm::cancelShare)
            }
            if (changelogSheet) NotesSheet("Changelog", "You have ${update.installed}", remember { fullChangelog(vm.updates.changelog) },
                onDismiss = { changelogSheet = false })
            update.whatsNew?.let { notes ->
                NotesSheet("What's new in ${update.installed}", "Updated. Here is what changed:", notes, onDismiss = vm.updates::markWhatsNewSeen) {
                    Button(onClick = vm.updates::markWhatsNewSeen, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                androidx.compose.animation.AnimatedVisibility(!ThemePrefs.sidebarHidden,
                    enter = androidx.compose.animation.expandHorizontally(), exit = androidx.compose.animation.shrinkHorizontally()) {
                    Row {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(ThemePrefs.sidebarWidth.dp).fillMaxHeight()) {
                            SessionsPane(sessions, chat.sessionId, sessionActions, pcs = pcs, profiles = hermesProfiles, view = sessionView, update = update, onOpenUpdate = openUpdate, groupOrder = groupOrder, scheduledCount = cron.jobs.count { it.state == "scheduled" }, search = sessionSearch)
                        }
                        io.github.nideta231.hermesremote.ui.SidebarHandle()
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = page == Page.CHAT || drawer.isOpen, drawerContent = {
                ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp)) {
                    SessionsPane(sessions, chat.sessionId, sessionActions, pcs = pcs, profiles = hermesProfiles, view = sessionView, update = update, onOpenUpdate = openUpdate, groupOrder = groupOrder, scheduledCount = cron.jobs.count { it.state == "scheduled" }, search = sessionSearch)
                }
            }) { content() }
        }
    }
}
