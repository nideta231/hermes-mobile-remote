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
import androidx.compose.material3.SnackbarHost
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
import io.github.nideta231.hermesremote.data.Notifier
import io.github.nideta231.hermesremote.ui.ChatActions
import io.github.nideta231.hermesremote.ui.CronActions
import io.github.nideta231.hermesremote.ui.CronScreen
import io.github.nideta231.hermesremote.ui.VaultActions
import io.github.nideta231.hermesremote.ui.VaultScreen
import io.github.nideta231.hermesremote.ui.ChatScreen
import io.github.nideta231.hermesremote.ui.HermesTheme
import io.github.nideta231.hermesremote.ui.ThemePrefs
import io.github.nideta231.hermesremote.ui.isDark
import io.github.nideta231.hermesremote.ui.PairScreen
import io.github.nideta231.hermesremote.ui.SessionActions
import io.github.nideta231.hermesremote.ui.SessionsPane
import io.github.nideta231.hermesremote.ui.UpdateSheet
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
enum class Page { CHAT, SCHEDULED, VAULT, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pairUri = mutableStateOf<String?>(null)
    private val showChat = mutableStateOf(false)

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
        handleIntent(intent)
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

    private fun handleIntent(intent: Intent?) {
        // Tapped a notification: go straight to the chat it is about.
        intent?.getStringExtra(Notifier.EXTRA_OPEN_SESSION)?.let { sid ->
            intent.removeExtra(Notifier.EXTRA_OPEN_SESSION)
            val profile = intent.getStringExtra(Notifier.EXTRA_PROFILE) ?: DEFAULT_HERMES_PROFILE
            intent.removeExtra(Notifier.EXTRA_PROFILE)
            vm.openSession(sid, profile)
            showChat.value = true
        }
        val data = intent?.data ?: return
        if (data.scheme == "hermesremote") {
            // A new code while paired adds that PC (or re-pairs it) without dropping the current one.
            if (vm.pairing.value != null) vm.startAddingPc()
            pairUri.value = data.toString()
        }
    }

    private fun scan() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code shown by `hermes-remote-bridge pair`")
            .setBeepEnabled(false).setOrientationLocked(false))
    }

    private fun pickImage() = runCatching { imagePicker.launch("image/*") }

    @Composable
    private fun App() {
        val pairing by vm.pairing.collectAsState()
        val adding by vm.addingPc.collectAsState()
        AnimatedContent(pairing != null && !adding, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "root") { paired ->
            if (!paired) {
                PairScreen(pairUri.value, onScan = ::scan, onPickImage = { pickImage() }, onPair = {
                    vm.pair(it).also { err ->
                        if (err == null) {
                            pairUri.value = null
                            imageError.value = null
                            askForNotifications()
                        }
                    }
                }, imageError = imageError.value,
                    onCancel = if (pairing != null) ({ pairUri.value = null; imageError.value = null; vm.cancelAddingPc() }) else null)
            } else {
                PairedApp()
            }
        }
    }

    @Composable
    private fun PairedApp() {
        val pairing by vm.pairing.collectAsState()
        val chat by vm.chat.collectAsState()
        val sessions by vm.sessions.collectAsState()
        val sessionView by vm.sessionView.collectAsState()
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
        var updateSheet by rememberSaveable { mutableStateOf(false) }
        var changelogSheet by rememberSaveable { mutableStateOf(false) }

        var page by rememberSaveable { mutableStateOf(Page.CHAT) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.consumeToast() } }
        if (showChat.value) { page = Page.CHAT; showChat.value = false; scope.launch { drawer.close() } }

        val wide = LocalConfiguration.current.screenWidthDp >= 720
        val health = linkHealth(conn, system.error != null)

        fun go(p: Page) {
            page = p
            if (p == Page.SETTINGS) vm.settings.refresh()
            scope.launch { drawer.close() }
        }

        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = !drawer.isOpen && page != Page.CHAT) { page = Page.CHAT }

        val sessionActions = SessionActions(
            open = { vm.openSession(it); go(Page.CHAT) },
            refresh = vm::refreshSessions,
            loadMore = vm::loadMoreSessions,
            setView = vm::setSessionView,
            rename = vm::rename,
            delete = vm::delete,
            setPinned = vm::setPinned,
            newChat = { vm.newChat(); go(Page.CHAT) },
            openSettings = { go(Page.SETTINGS) },
            switchPc = { vm.switchPc(it); go(Page.CHAT) },
            addPc = { scope.launch { drawer.close() }; vm.startAddingPc() },
            renamePc = vm::renamePc,
            forgetPc = vm::forgetPc,
            switchProfile = { vm.switchProfile(it); go(Page.CHAT) },
            openScheduled = { go(Page.SCHEDULED) },
            openVault = { go(Page.VAULT) },
        )
        val chatActions = ChatActions(
            send = vm::send, stop = vm::stop, steer = vm::steer, approve = vm::answerApproval, stopSubagent = vm::stopSubagent,
            answerClarify = vm::answerClarify, draft = vm::setDraft,
            togglePin = vm::togglePin, newChat = { vm.newChat() },
            openDrawer = { vm.refreshSessions(); scope.launch { drawer.open() } },
            openSettings = { go(Page.SETTINGS) },
            loadModels = vm::loadModels, chooseModel = vm::chooseModel, dismissConfirm = vm::dismissConfirm,
            setReasoning = vm::setReasoning, modelPickerOpened = vm::modelPickerOpened,
            chooseProject = vm::chooseProject, createProject = vm::createProject,
            pickImages = { runCatching { chatPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) } },
            pickFiles = { runCatching { filePicker.launch(arrayOf("*/*")) } }, removeFile = vm::removeFile,
            removeAttachment = vm::removeAttachment, loadImage = vm::loadImage, answerSecret = vm::answerSecret,
            edit = vm::editMessage,
        )
        val settingsActions = SettingsActions(
            refresh = vm.settings::refresh, unpair = vm::unpair, setApprovalMode = vm.settings::setApprovalMode,
            useTransport = vm::useTransport, useAuto = vm::useAutoTransport,
            checkUpdate = { vm.updates.checkForUpdate() }, installUpdate = vm.updates::installUpdate,
            openUpdate = { updateSheet = true }, openChangelog = { changelogSheet = true },
            loadModels = vm::loadModels, setDefaultModel = vm.settings::setDefaultModel, dismissDefaultConfirm = vm.settings::dismissDefaultConfirm,
        )
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
                            openModelPicker, confirm, showMenuButton = !wide, actions = chatActions,
                            update = update, onOpenUpdate = openUpdate, onDismissUpdate = vm.updates::dismissUpdateBanner,
                            projects = sessions.projects.filter { it.path != null },
                            profile = hermesProfiles.headerLabel)
                        Page.SCHEDULED -> CronScreen(cron, hermesProfiles.active.takeIf { hermesProfiles.items.size > 1 }, cronActions, onBack = { page = Page.CHAT })
                        Page.VAULT -> VaultScreen(vaultState, VaultActions(refresh = vm.vault::refresh, save = vm.vault::save,
                            remove = vm.vault::remove), onBack = { page = Page.CHAT })
                        Page.SETTINGS -> SettingsScreen(system, pairing, conn, update, settingsActions, onBack = { page = Page.CHAT }, models = models)
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
            }
            if (updateSheet && update.available != null) UpdateSheet(update, onInstall = vm.updates::installUpdate, onDismiss = { updateSheet = false })
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
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp).fillMaxHeight()) {
                    SessionsPane(sessions, chat.sessionId, sessionActions, pcs = pcs, profiles = hermesProfiles, view = sessionView, update = update, onOpenUpdate = openUpdate)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = page == Page.CHAT || drawer.isOpen, drawerContent = {
                ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp)) {
                    SessionsPane(sessions, chat.sessionId, sessionActions, pcs = pcs, profiles = hermesProfiles, view = sessionView, update = update, onOpenUpdate = openUpdate)
                }
            }) { content() }
        }
    }
}
