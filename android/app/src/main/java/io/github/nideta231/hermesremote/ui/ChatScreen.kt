package io.github.nideta231.hermesremote.ui

import io.github.nideta231.hermesremote.data.SubagentTail
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.UpdateState
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import io.github.nideta231.hermesremote.data.ClaudeUsage
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.nideta231.hermesremote.data.LiveLink
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.ChatState
import io.github.nideta231.hermesremote.ConnectionState
import io.github.nideta231.hermesremote.Link
import io.github.nideta231.hermesremote.data.ModelConfirm
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.ReplyMedia
import io.github.nideta231.hermesremote.data.fileName
import io.github.nideta231.hermesremote.data.splitReplyMedia
import io.github.nideta231.hermesremote.data.messageTime
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import io.github.nideta231.hermesremote.data.REASONING_LEVELS
import io.github.nideta231.hermesremote.data.SlashSuggestion
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.data.Transport
import kotlinx.coroutines.launch

/** Everything the chat screen can ask the app to do. */
class ChatActions(
    val send: (String) -> Unit,
    val stop: () -> Unit,
    val steer: (String) -> Unit,
    /** Send as the next turn while one runs, instead of steering it. */
    val queue: (String) -> Unit = {},
    val approve: (String) -> Unit,
    val answerClarify: (List<String>) -> Unit,
    val draft: (String) -> Unit,
    val togglePin: () -> Unit,
    val newChat: () -> Unit,
    val openDrawer: () -> Unit,
    val openSettings: () -> Unit,
    val loadModels: () -> Unit,
    val chooseModel: (ModelOption?, Boolean) -> Unit,
    val dismissConfirm: () -> Unit,
    val setReasoning: (String?) -> Unit,
    val modelPickerOpened: () -> Unit,
    /** New chat only: pick the project it starts in (null = no project). */
    val chooseProject: (String?) -> Unit = {},
    /** Create, edit and delete projects on the PC. */
    val projects: ProjectActions = ProjectActions(),
    /** Open the phone's photo picker; picks land in [ChatState.attachments]. */
    val pickImages: () -> Unit = {},
    val removeAttachment: (String) -> Unit = {},
    /** Open the system file picker (any type); picks land in [ChatState.files]. */
    val pickFiles: () -> Unit = {},
    val removeFile: (String) -> Unit = {},
    /** Bytes of a chat image (phone uri or PC path); null when it can't be had. */
    val loadImage: suspend (String) -> ByteArray? = { null },
    /** A file the chat names (PC path), downloaded into the app's cache. */
    val fetchFile: suspend (String) -> java.io.File = { throw java.io.IOException("Not available") },
    /** Name, path, size and type of a chat file or picture, for its Info sheet. */
    val fileInfo: suspend (String) -> io.github.nideta231.hermesremote.data.FileInfo = { io.github.nideta231.hermesremote.data.FileInfo(fileName(it), it) },
    /** Answer the open masked prompt: (identifier for a login, value); empty value = skip. */
    val answerSecret: (String, String) -> Unit = { _, _ -> },
    /** Edit a sent message: Hermes drops it and what followed, then answers the new text. */
    val edit: (ChatItem.User, String) -> Unit = { _, _ -> },
    /** Stop one child agent of the open chat. */
    val stopSubagent: (String) -> Unit = {},
    /** Poll a running child's transcript tail into the callback until cancelled. */
    val followSubagent: suspend (String, (SubagentTail) -> Unit) -> Unit = { _, _ -> },
    /** Send a running child an instruction (id, text, done: queued or not). */
    val steerSubagent: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    /** Open a child's own chat (its stored session id). */
    val openSubagentSession: (String) -> Unit = {},
    /** Kill one background process of the open chat. */
    val stopProcess: (String) -> Unit = {},
    /** Wide screens: hide the chat list beside the chat (null when it is hidden or there is none). */
    val hideSidebar: (() -> Unit)? = null,
)

/** The long-press menu of a sent message; provided by [ChatScreen]. */
private val LocalEditMessage = staticCompositionLocalOf<((ChatItem.User, String) -> Unit)?> { null }

/** Loads a chat image's bytes; provided once by [ChatScreen] so every bubble can draw pictures. */
internal val LocalImageLoader = staticCompositionLocalOf<suspend (String) -> ByteArray?> { { null } }

/** Downloads a chat file from the PC; provided once by [ChatScreen] so every bubble can show file cards. */
internal val LocalFileFetcher = staticCompositionLocalOf<suspend (String) -> java.io.File> { { throw java.io.IOException("Not available") } }

/** Looks up a chat file's info; provided once by [ChatScreen]. */
internal val LocalFileInfo = staticCompositionLocalOf<suspend (String) -> io.github.nideta231.hermesremote.data.FileInfo> {
    { io.github.nideta231.hermesremote.data.FileInfo(fileName(it), it) }
}

/** How the phone reaches the PC right now, reduced to what the top bar shows. */
enum class LinkHealth { LAN, TAILNET, SEARCHING, OFFLINE }

fun linkHealth(conn: ConnectionState, unreachable: Boolean): LinkHealth = when {
    conn.searching -> LinkHealth.SEARCHING
    unreachable || conn.activeUrl == null -> LinkHealth.OFFLINE
    conn.transport == Transport.LAN -> LinkHealth.LAN
    else -> LinkHealth.TAILNET
}

@Composable
fun ChatScreen(
    state: ChatState,
    health: LinkHealth,
    catalog: ModelCatalog?,
    model: ModelOption?,
    reasoning: String?,
    suggestions: List<SlashSuggestion>,
    openModelPicker: Boolean,
    confirm: ModelConfirm?,
    showMenuButton: Boolean,
    actions: ChatActions,
    update: UpdateState? = null,
    onOpenUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    /** Saved projects; those without a folder are listed so one can be added to them. */
    projects: List<ProjectRef> = emptyList(),
    /** The Hermes profile this chat runs in; null when the PC has only the default one. */
    profile: String? = null,
    /** Claude plan usage for the badge beside the link icon; null hides it. Tapping opens the usage popup. */
    claudeUsage: ClaudeUsage? = null,
    /** Opens the System Monitor from the usage popup; null hides that link. */
    onOpenMonitor: (() -> Unit)? = null,
    loadContext: suspend () -> io.github.nideta231.hermesremote.data.ContextInfo? = { null },
    loadUsageHistory: suspend (String) -> io.github.nideta231.hermesremote.data.PcHistory? = { null },
    onRefreshUsage: () -> Unit = {},
) {
    var usageSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var projectSheet by remember { mutableStateOf(false) }
    var reasoningSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (catalog == null) actions.loadModels() }
    LaunchedEffect(openModelPicker) {
        if (openModelPicker) { modelSheet = true; actions.modelPickerOpened() }
    }
    val approval = LiveReducer.openApproval(state.items)
    val clarify = LiveReducer.openClarify(state.items)
    val secret = LiveReducer.openSecret(state.items)
    val subagentActions = SubagentActions(actions.stopSubagent, actions.followSubagent, actions.steerSubagent, actions.openSubagentSession)

    CompositionLocalProvider(LocalImageLoader provides actions.loadImage, LocalFileFetcher provides actions.fetchFile,
        LocalFileInfo provides actions.fileInfo,
        LocalEditMessage provides actions.edit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ChatTopBar(state, health, showMenuButton, actions, profile, claudeUsage) { usageSheet = true }
        if (usageSheet && claudeUsage != null) UsageSheet(claudeUsage, hasChat = state.sessionId != null, busy = state.busy,
            loadContext = loadContext, loadHistory = loadUsageHistory, onRefresh = onRefreshUsage,
            onCompact = { topic -> actions.send(if (topic.isEmpty()) "/compact" else "/compact $topic") },
            onOpenMonitor = onOpenMonitor, onDismiss = { usageSheet = false })
        ConnectionBanner(state, health, actions.openSettings)
        update?.let { UpdateStrip(it, onOpenUpdate, onDismissUpdate) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = when {
                    state.loading && state.items.isEmpty() -> 0
                    state.items.isEmpty() -> 1
                    else -> 2
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "chat-body",
            ) { body ->
                when (body) {
                    0 -> LoadingChat()
                    1 -> EmptyChat(onPick = { actions.draft(it) })
                    else -> MessageList(state, subagentActions)
                }
            }
        }
        // Background work stays pinned above the composer while the chat scrolls.
        val work by LiveLink.backgroundWork.collectAsState()
        val strip = backgroundStrip(state, loop = work.contains("${state.runtimeId}:loop"), heartbeat = work.contains("${state.runtimeId}:heartbeat"))
        AnimatedVisibility(strip != null,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))) {
            strip?.let { BackgroundStripBar(it, subagentActions, actions.stopProcess) }
        }
        // Questions slide up from the composer, like a sheet the agent raised.
        AnimatedVisibility(approval != null,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))) {
            approval?.let { ApprovalDock(it, actions.approve) }
        }
        AnimatedVisibility(clarify != null && approval == null,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))) {
            clarify?.let { ClarifyDock(it, actions.answerClarify) }
        }
        AnimatedVisibility(secret != null && approval == null && clarify == null,
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))) {
            secret?.let { SecretDock(it, actions.answerSecret) }
        }
        SlashSuggestions(state, suggestions, actions.draft)
        Composer(state, catalog, model, reasoning, actions,
            onModel = { actions.loadModels(); modelSheet = true },
            onReasoning = { actions.loadModels(); reasoningSheet = true },
            onProject = if (state.sessionId == null && projects.isNotEmpty()) ({ projectSheet = true }) else null)
    }
    }

    if (modelSheet) ModelSheet(catalog, model, state.sessionId == null, onDismiss = { modelSheet = false }, onPick = {
        actions.chooseModel(it, false); modelSheet = false
    })
    if (projectSheet) ProjectSheet(projects, state.project?.id, onDismiss = { projectSheet = false },
        actions = actions.projects) {
        actions.chooseProject(it); projectSheet = false
    }
    if (reasoningSheet) ReasoningSheet(catalog, reasoning, onDismiss = { reasoningSheet = false }) { actions.setReasoning(it); reasoningSheet = false }
    confirm?.let { c ->
        AlertDialog(onDismissRequest = actions.dismissConfirm, title = { Text("Switch to ${c.option.label}?") },
            text = { Text(c.message) },
            confirmButton = { TextButton(onClick = { actions.chooseModel(c.option, true) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = actions.dismissConfirm) { Text("Cancel") } })
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun ChatTopBar(state: ChatState, health: LinkHealth, showMenu: Boolean, actions: ChatActions, profile: String?,
                       usage: ClaudeUsage?, onOpenUsage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) IconButton(onClick = actions.openDrawer) { Icon(Glyphs.Menu, "Sessions") }
            else actions.hideSidebar?.let { hide -> IconButton(onClick = hide) { Icon(Glyphs.Menu, "Hide the chat list") } }
                ?: Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                AnimatedContent(state.title, transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                }, label = "title") { title ->
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val (label, color, pulse) = when {
                    state.link == Link.RECONNECTING -> Triple("Reconnecting…", Warn, true)
                    state.waiting -> Triple("Needs your answer", Warn, true)
                    state.status == "starting" -> Triple("Starting…", Gold, true)
                    state.busy -> Triple("Working…", Gold, true)
                    state.sessionId == null -> Triple("New conversation", MaterialTheme.colorScheme.onSurfaceVariant, false)
                    else -> Triple("Idle", MaterialTheme.colorScheme.onSurfaceVariant, false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color, pulse, 6)
                    Spacer(Modifier.width(6.dp))
                    AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
                    }
                    profile?.let { ProfileTag(it) }
                }
            }
            // New chat lives in the drawer and Pin in the chat's ⋮ menu; the top bar only shows the link.
            usage?.let { UsageRing(it, onOpenUsage) }
            LinkBadge(health, actions.openSettings)
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** "· developer" after the status: which Hermes profile this chat runs in. */
@Composable
private fun ProfileTag(name: String) {
    Text(" · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Icon(Glyphs.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
    Spacer(Modifier.width(3.dp))
    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Claude plan usage at a glance: Claude's mark inside the rings (outer = 5-hour session, inner = week), session % beside. */
@Composable
private fun UsageRing(u: ClaudeUsage, onClick: (() -> Unit)?) {
    val session = (u.session ?: 0.0).coerceIn(0.0, 100.0)
    val weekly = u.weekly?.coerceIn(0.0, 100.0)
    fun tone(p: Double) = when { p >= 90 -> Bad; p >= 70 -> Warn; else -> ClaudeColor }
    val sessionColor = tone(session)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val desc = buildString {
        append("Claude usage: session ${session.toInt()}%")
        weekly?.let { append(", week ${it.toInt()}%") }
    }
    Row(Modifier.clip(RoundedCornerShape(20.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = desc }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(28.dp)) {
                val outer = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = outer)
                drawArc(sessionColor, -90f, (360f * session / 100.0).toFloat(), false, style = outer)
                if (weekly != null) {
                    val inset = 5.dp.toPx()
                    val inner = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                    val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(track, -90f, 360f, false, topLeft = tl, size = sz, style = inner)
                    drawArc(tone(weekly).copy(alpha = 0.75f), -90f, (360f * weekly / 100.0).toFloat(), false, topLeft = tl, size = sz, style = inner)
                }
            }
            ClaudeMark(Modifier.size(11.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("${session.toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = sessionColor, maxLines = 1)
    }
}

@Composable
private fun LinkBadge(health: LinkHealth, onClick: () -> Unit) {
    val (icon, tint, desc) = when (health) {
        LinkHealth.LAN -> Triple(Glyphs.Wifi, Ok, "Local network")
        LinkHealth.TAILNET -> Triple(Glyphs.Globe, MaterialTheme.colorScheme.secondary, "Tailscale")
        LinkHealth.SEARCHING -> Triple(Glyphs.Refresh, Warn, "Connecting")
        LinkHealth.OFFLINE -> Triple(Glyphs.Offline, Bad, "Can't reach your PC")
    }
    IconButton(onClick = onClick) {
        AnimatedContent(health, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "link") {
            Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Slides in under the top bar when the PC can't be reached, so a dead link is never silent. */
@Composable
private fun ConnectionBanner(state: ChatState, health: LinkHealth, onOpen: () -> Unit) {
    val show = health == LinkHealth.OFFLINE || health == LinkHealth.SEARCHING || state.link == Link.RECONNECTING
    AnimatedVisibility(show, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val offline = health == LinkHealth.OFFLINE
        Surface(color = if (offline) Bad.copy(alpha = 0.16f) else Warn.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (offline) Bad else Warn, pulse = !offline, size = 7)
                Spacer(Modifier.width(10.dp))
                Text(if (offline) "Can't reach your PC. Tap for details." else "Connecting to your PC…",
                    style = MaterialTheme.typography.labelLarge, color = if (offline) Bad else Warn)
            }
        }
    }
}

// ------------------------------------------------------------------ empty + loading

/** Skeleton lines shimmering while a chat opens: shows where content will be instead of a spinner. */
@Composable
private fun LoadingChat() {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(0.55f to true, 0.9f to false, 0.75f to false, 0.4f to true, 0.85f to false).forEach { (w, user) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(w).heightIn(min = if (user) 38.dp else 54.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).shimmer(true, Color.White))
            }
        }
    }
}

private val starters = listOf(
    "What's using the most disk space on my PC?",
    "Summarize what changed in my git repos today",
    "Check if any system updates are pending",
    "/status",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(Gold.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text("What should Hermes do?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text("Same Hermes as your desktop app: chats started here show up there, live. Type / for commands and skills.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.size(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { s ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(s) }) {
                    Text(s, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ messages

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(state: ChatState, subagentActions: SubagentActions) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canFwd) ->
            if (scrolling) follow = !canFwd
        }
    }
    // Keys already on screen when the chat opened (history) appear at once; only items that
    // arrive afterwards fade and rise in. Kept for the list's lifetime, so scrolling an item out
    // and back never replays its entrance (the old "chat fading in and out" bug).
    val seen = remember(state.sessionId) { HashSet<String>(state.items.map { it.key }) }
    val lastText = (state.items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(state.items.size, lastText, viewport, state.busy, state.subagents.size, state.processes.size) {
        if (follow && state.items.isNotEmpty()) listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2)
    }
    val last = state.items.lastOrNull()
    val thinking = state.busy && !state.waiting && state.subagents.none { it.running } &&
        !(last is ChatItem.Assistant && last.streaming) && !(last is ChatItem.Thinking && last.streaming) &&
        !(last is ChatItem.Tool && last.status == ToolStatus.RUNNING)

    val delegateTools = remember(state.items) { state.items.filterIsInstance<ChatItem.Tool>().filter { it.name == Delegate.TOOL } }
    val (delegateRows, _) = remember(delegateTools, state.subagents) { Delegate.merge(delegateTools, state.subagents) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.items, key = { it.key }) { item ->
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).entrance(item.key, seen)) {
                    when (item) {
                        is ChatItem.User -> UserBubble(item)
                        is ChatItem.Assistant -> AssistantBlock(item)
                        is ChatItem.Thinking -> ThinkingBlock(item)
                        is ChatItem.Tool -> delegateRows[item.key]?.takeIf { it.isNotEmpty() }
                            ?.let { DelegateCard(it, subagentActions) } ?: ToolRow(item)
                        is ChatItem.Approval -> if (item.decided != null) ApprovalRecord(item)
                        is ChatItem.Clarify -> if (item.answer != null) ClarifyRecord(item)
                        is ChatItem.Secret -> if (item.outcome != null) SecretRecord(item)
                        is ChatItem.Notice -> NoticeLine(item)
                        is ChatItem.CommandOutput -> CommandOutputCard(item)
                    }
                }
            }
            if (thinking) item(key = "typing") {
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).entrance("typing", HashSet())) { TypingIndicator() }
            }
        }
        AnimatedVisibility(!follow, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            SmallFloatingActionButton(onClick = {
                follow = true
                scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2) }
            }, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
                Icon(Glyphs.Down, "Jump to latest", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** New items fade in and rise 12 dp with a soft spring; items in [seen] are shown as-is. */
@Composable
private fun Modifier.entrance(key: String, seen: HashSet<String>): Modifier {
    val fresh = remember(key) { seen.add(key) }
    val p = remember(key) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(key) { if (p.value < 1f) p.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 12.dp.toPx()
    }
}

/** The agent is busy but nothing is streaming yet: bouncing dots and a shimmering label. */
@Composable
private fun TypingIndicator() {
    Row(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).shimmer(true)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        TypingDots(Gold)
        Spacer(Modifier.width(10.dp))
        Text("Thinking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun copyable(text: String): Modifier {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    return Modifier.combinedClickable(onClick = {}, onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        clipboard.setText(AnnotatedString(text))
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(item: ChatItem.User) {
    val alpha by animateFloatAsState(if (item.pending) 0.10f else 0.17f, label = "bubble")
    val edit = LocalEditMessage.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    link?.let { LinkSheet(it) { link = null } }
    // With file cards drawn, the "📎 name" lines that stand for them would say it twice.
    val shownText = remember(item.text, item.files) {
        if (item.files.isEmpty()) item.text
        else item.text.lines().filterNot { it.trim().startsWith("📎 ") }.joinToString("\n").trim()
    }
    // One Column: the list item is a Box, so a picture and the text emitted side by side were drawn
    // on top of each other (the picture covered the start of the message).
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    if (item.images.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().padding(bottom = if (item.text.isBlank()) 0.dp else 6.dp), contentAlignment = Alignment.CenterEnd) {
            ImageStrip(item.images, Modifier.padding(start = 40.dp))
        }
    }
    if (item.files.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().padding(bottom = if (shownText.isBlank()) 0.dp else 6.dp), contentAlignment = Alignment.CenterEnd) {
            FileCards(item.files, Modifier.padding(start = 40.dp), alignEnd = true)
        }
    }
    if (shownText.isNotBlank()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = Gold.copy(alpha = alpha), shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 560.dp).padding(start = 40.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .combinedClickable(onClick = {}, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })) {
            Text(remember(shownText) { linkified(clipForDisplay(shownText)) { link = it } }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = if (item.pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Glyphs.Copy, null, Modifier.size(18.dp)) },
                onClick = { clipboard.setText(AnnotatedString(item.text)); menu = false })
            if (edit != null && !item.pending) androidx.compose.material3.DropdownMenuItem(text = { Text("Edit") },
                leadingIcon = { Icon(Glyphs.Edit, null, Modifier.size(18.dp)) }, onClick = { menu = false; editing = true })
        }
    }
    MessageTime(item.at, Modifier.padding(top = 3.dp, end = 4.dp))
    }
    if (editing && edit != null) EditMessageDialog(item, onSave = { edit(item, it); editing = false }, onClose = { editing = false })
}

@Composable
private fun EditMessageDialog(item: ChatItem.User, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(item.text) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 10, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth())
                Text("Hermes forgets this message and everything after it, then answers the edited one. " +
                    "Pictures and files sent with it aren't sent again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank() && text.trim() != item.text.trim(), onClick = { onSave(text) }) { Text("Send", color = Gold) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant) {
    val shown = rememberSmoothText(item.text, item.streaming)
    val caretOn by animateFloatAsState(if (item.streaming) 1f else 0f, tween(250), label = "caret")
    val media = remember(shown, item.streaming) {
        if (item.streaming) ReplyMedia(shown, emptyList(), emptyList()) else splitReplyMedia(shown)
    }
    val body = media.text
    val images = media.images
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        // Streaming replies are short-lived; a finished one is clipped so a huge answer can't stall scrolling.
        val safe = remember(body, item.streaming) { if (item.streaming) body else clipForDisplay(body) }
        MarkdownText(if (caretOn > 0.01f) "$safe ▍" else safe)
        if (images.isNotEmpty()) ImageStrip(images, Modifier.padding(top = 8.dp))
        if (media.files.isNotEmpty()) FileCards(media.files, Modifier.padding(top = 8.dp))
        // Copy the whole reply as markdown, like the desktop's copy action under each answer.
        if (!item.streaming && item.text.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            CopyButton(item.text, Modifier.offset(x = (-6).dp))
            MessageTime(item.at)
        }
    }
}

/** When a message was sent, under it: the time today, else the date too ("Oct 26, 14:05"). */
@Composable
private fun MessageTime(at: Long?, modifier: Modifier = Modifier) {
    at ?: return
    val label = remember(at) { messageTime(at, System.currentTimeMillis()) }
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = modifier)
}

/** The model's reasoning: one shimmering line while it thinks, expandable afterwards. */
@Composable
private fun ThinkingBlock(item: ChatItem.Thinking) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }
        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(Modifier.shimmer(item.streaming), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Brain, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (item.streaming) "Thinking…" else "Thought", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(Glyphs.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).rotate(rot))
        }
        if (open) {
            Text(remember(item.text) { clipForDisplay(item.text) }, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        }
    }
}

@Composable
private fun NoticeLine(item: ChatItem.Notice) {
    Text(item.text, style = MaterialTheme.typography.labelMedium,
        color = if (item.error) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun ToolRow(item: ChatItem.Tool) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val color by animateColorAsState(when (item.status) {
        ToolStatus.RUNNING -> Gold
        ToolStatus.OK -> Ok
        ToolStatus.FAILED -> Bad
    }, label = "tool-status")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))) {
        Column(Modifier.shimmer(item.status == ToolStatus.RUNNING).clickable(enabled = item.result != null || item.images.isNotEmpty()) { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, pulse = item.status == ToolStatus.RUNNING, size = 8)
                Spacer(Modifier.width(10.dp))
                Icon(Glyphs.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(item.args, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = if (open) 20 else 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                item.durationSec?.let {
                    Text(" %.1fs".format(it), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.result != null || item.images.isNotEmpty()) {
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                    Icon(Glyphs.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).rotate(rot))
                }
            }
            // Pictures a tool returned show without opening the row, like the desktop.
            if (item.images.isNotEmpty()) ImageStrip(item.images, Modifier.padding(top = 8.dp))
            if (open && item.result != null) {
                Text(readableToolResult(item.result).take(4000), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState()).padding(8.dp))
            }
        }
    }
}

/** Tools return JSON like {"output": "...", "exit_code": 0}; show the text, plus the exit code if it failed. */
internal fun readableToolResult(raw: String): String {
    io.github.nideta231.hermesremote.data.multimodal(raw)?.let { return it.first.ifBlank { "[picture]" } }
    val o = runCatching { org.json.JSONObject(raw.trim()) }.getOrNull() ?: return raw
    val body = listOf("output", "content", "result", "text").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotEmpty() } }
        ?: return o.toString(2)
    val code = if (o.has("exit_code") && o.optInt("exit_code") != 0) "\n[exit ${o.optInt("exit_code")}]" else ""
    val err = o.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { "\n$it" } ?: ""
    return body.trimEnd() + code + err
}

@Composable
private fun CommandOutputCard(item: ChatItem.CommandOutput) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(copyable(item.text)).animateContentSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Terminal, null, tint = Gold, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.command, style = MaterialTheme.typography.labelLarge, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.text.isNotBlank()) {
                val shown = remember(item.text) { displaySafe(item.text.trimEnd()) }
                Text(shown, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp).heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
            }
        }
    }
}

/** The approval as it stays in the transcript; the buttons live in [ApprovalDock]. */
@Composable
private fun ApprovalRecord(item: ChatItem.Approval) {
    Surface(color = Warn.copy(alpha = 0.10f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Shield, null, tint = Warn, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Approval: ${choiceLabel(item.decided ?: "")}", style = MaterialTheme.typography.labelLarge, color = Warn)
            }
            item.request.command?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ClarifyRecord(item: ChatItem.Clarify) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            item.request.questions.forEach { q ->
                Text(q.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.answer.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun choiceLabel(choice: String) =
    mapOf("once" to "Allow once", "session" to "Allow for session", "always" to "Always allow", "deny" to "Deny")[choice] ?: choice

/** Sticky panel above the composer while a command waits for the user: big targets, no hunting. */
@Composable
private fun ApprovalDock(item: ChatItem.Approval, onApproval: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(item.key) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Warn.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Shield, null, tint = Warn, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Allow this command?", fontWeight = FontWeight.SemiBold)
                    item.request.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item.request.command?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(10.dp))
            }
            val choices = item.request.choices
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("deny" in choices) {
                    OutlinedButton(onClick = { onApproval("deny") }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)) { Text("Deny") }
                }
                if ("once" in choices) {
                    Button(onClick = { onApproval("once") }, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
            }
            val extra = choices.filter { it == "session" || it == "always" }
            if (extra.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                    extra.forEach { c -> TextButton(onClick = { onApproval(c) }) { Text(choiceLabel(c), maxLines = 1) } }
                }
            }
        }
    }
}

/** The clarify tool's question(s): tap a choice, or type an answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarifyDock(item: ChatItem.Clarify, onAnswer: (List<String>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(item.key) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val questions = item.request.questions
    val answers = remember(item.key) { mutableStateListOf(*Array(questions.size) { "" }) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Gold.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(if (questions.size > 1) "Hermes has ${questions.size} questions" else "Hermes asks", fontWeight = FontWeight.SemiBold)
            }
            questions.forEachIndexed { i, q ->
                Text(q.question, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                if (q.choices.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        q.choices.forEach { c ->
                            val picked = if (q.multiSelect) c in answers[i].split(", ") else answers[i] == c
                            val bg by animateColorAsState(if (picked) Gold.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHighest, label = "choice")
                            Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                answers[i] = if (q.multiSelect) {
                                    val set = answers[i].split(", ").filter { it.isNotBlank() }.toMutableList()
                                    if (c in set) set.remove(c) else set.add(c)
                                    set.joinToString(", ")
                                } else c
                                // One question, one choice: answer right away like the desktop.
                                if (questions.size == 1 && !q.multiSelect) onAnswer(listOf(c))
                            }) {
                                Text(c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                OutlinedTextField(answers[i], { answers[i] = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text(if (q.choices.isEmpty()) "Your answer" else "Or type your own") },
                    shape = RoundedCornerShape(14.dp), maxLines = 4)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAnswer(questions.map { "" }) }, modifier = Modifier.weight(1f)) { Text("Skip") }
                Button(onClick = { onAnswer(answers.toList()) }, enabled = answers.any { it.isNotBlank() }, modifier = Modifier.weight(1f)) { Text("Answer") }
            }
        }
    }
}

// ------------------------------------------------------------------ composer

@Composable
private fun SlashSuggestions(state: ChatState, suggestions: List<SlashSuggestion>, onDraft: (String) -> Unit) {
    val typing = state.draft.startsWith("/") && !state.draft.contains(' ')
    AnimatedVisibility(typing && suggestions.isNotEmpty(),
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(suggestions, key = { it.text }) { c ->
                    Row(Modifier.fillMaxWidth().animateItem().clickable { onDraft(c.text.trimEnd() + " ") }
                        .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (c.skill) Glyphs.Spark else Glyphs.Terminal, null,
                            tint = if (c.skill) Gold else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.display, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.meta.isNotBlank()) {
                                Text(c.meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun effortLabel(level: String) = when (level) {
    "none" -> "Off"
    "xhigh" -> "Extra high"
    else -> level.replaceFirstChar { it.uppercase() }
}

@Composable
private fun Composer(
    state: ChatState,
    catalog: ModelCatalog?,
    model: ModelOption?,
    reasoning: String?,
    actions: ChatActions,
    onModel: () -> Unit,
    onReasoning: () -> Unit,
    onProject: (() -> Unit)? = null,
) {
    val text = state.draft
    val haptics = LocalHapticFeedback.current
    val submit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // The view model clears the draft only when the send is accepted, so a
        // refused send keeps its text here instead of losing it.
        // Pictures can't ride a steer: with any attached, the message is queued as its own turn.
        val plain = !text.trimStart().startsWith("/")
        when {
            state.busy && plain && ThemePrefs.queueWhileBusy -> actions.queue(text)
            state.busy && plain && state.attachments.isEmpty() && state.files.isEmpty() -> actions.steer(text)
            else -> actions.send(text)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.animateContentSize()) {
                    if (state.attachments.isNotEmpty()) {
                        LazyRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.attachments, key = { it }) { uri ->
                                Box(Modifier.animateItem()) {
                                    ChatImage(uri, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), crop = true)
                                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.6f)).clickable { actions.removeAttachment(uri) },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Glyphs.Close, "Remove picture", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (state.files.isNotEmpty()) {
                        LazyRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.files, key = { it.first }) { (uri, name) ->
                                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.animateItem()) {
                                    Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Glyphs.Paperclip, null, tint = Gold, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 160.dp))
                                        IconButton(onClick = { actions.removeFile(uri) }, modifier = Modifier.size(26.dp)) {
                                            Icon(Glyphs.Close, "Remove file", modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Keeps the cursor so Shift+Enter can insert at it; follows the draft when it changes elsewhere.
                    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
                    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length)).also { field = it }
                    // Tablet (smallest width >= 600dp) or landscape: Enter sends, Shift+Enter is a new line.
                    // Phone portrait keeps Enter = new line (Ctrl+Enter still sends on a hardware keyboard).
                    val cfg = LocalConfiguration.current
                    val enterSends = cfg.smallestScreenWidthDp >= 600 || cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val canSend = !state.sending && (text.isNotBlank() || state.attachments.isNotEmpty() || state.files.isNotEmpty())
                    TextField(
                        value = value,
                        onValueChange = { next ->
                            // Soft keyboards type Enter as a "\n" instead of a key event: catch that one inserted
                            // newline at the cursor and send instead of keeping it.
                            val typedEnter = enterSends && next.text.length == value.text.length + 1 &&
                                next.selection.collapsed && next.selection.start > 0 &&
                                next.text[next.selection.start - 1] == '\n' &&
                                next.text.removeRange(next.selection.start - 1, next.selection.start) == value.text
                            if (typedEnter) { if (canSend) submit(); return@TextField }
                            field = next; if (next.text != text) actions.draft(next.text)
                        },
                        keyboardOptions = if (enterSends) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                        keyboardActions = KeyboardActions(onSend = { if (canSend) submit() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).onPreviewKeyEvent { e ->
                            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (!enter) return@onPreviewKeyEvent false
                            if (e.isCtrlPressed || (enterSends && !e.isShiftPressed)) {
                                if (e.type == KeyEventType.KeyDown && canSend) submit()
                                return@onPreviewKeyEvent true
                            }
                            if (!e.isShiftPressed) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) {
                                val next = insertNewline(value)
                                field = next
                                actions.draft(next.text)
                            }
                            true
                        },
                        placeholder = { Text(when {
                            !state.busy -> "Message Hermes"
                            ThemePrefs.queueWhileBusy -> "Queue the next message…"
                            else -> "Steer the running task…"
                        }) },
                        maxLines = 7,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    )
                    if (onProject != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 2.dp)) {
                            Pill(state.project?.label ?: "No project", Glyphs.Folder,
                                tint = if (state.project != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onProject)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val modelLabel = model?.label ?: catalog?.currentModel?.substringAfterLast('/') ?: "Model"
                        IconButton(onClick = actions.pickImages, enabled = !state.sending, modifier = Modifier.size(36.dp)) {
                            Icon(Glyphs.Image, "Attach a picture", tint = if (state.attachments.isNotEmpty()) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = actions.pickFiles, enabled = !state.sending, modifier = Modifier.size(36.dp)) {
                            Icon(Glyphs.Paperclip, "Attach a file", tint = if (state.files.isNotEmpty()) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Pill(modelLabel, Glyphs.Chip, modifier = Modifier.weight(1f, fill = false),
                                tint = if (model != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onModel)
                            Spacer(Modifier.width(6.dp))
                            Pill(reasoning?.let(::effortLabel) ?: "Auto", Glyphs.Brain,
                                tint = if (reasoning != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onReasoning)
                        }
                        Spacer(Modifier.width(8.dp))
                        // While the agent works: pick what a typed message does, steer it or queue the next turn.
                        AnimatedVisibility(state.busy, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                            BusyModeToggle(ThemePrefs.queueWhileBusy) { ThemePrefs.pickQueueWhileBusy(it) }
                        }
                        SendButton(state, text, onSend = submit, onStop = actions.stop, queue = ThemePrefs.queueWhileBusy)
                    }
                }
            }
        }
    }
}

private enum class SendMode { SEND, STEER, QUEUE, STOP, SENDING }

/** Steer / Queue switch beside Send while a turn runs; tap flips it, and the choice is remembered. */
@Composable
private fun BusyModeToggle(queue: Boolean, onPick: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(21.dp),
        modifier = Modifier.padding(end = 6.dp).height(34.dp)) {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Steer", true to "Queue").forEach { (value, label) ->
                val on = value == queue
                val bg by animateColorAsState(if (on) Gold else Color.Transparent, label = "busy-mode")
                Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(bg)
                    .clickable { if (!on) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(value) } }
                    .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SendButton(state: ChatState, text: String, onSend: () -> Unit, onStop: () -> Unit, queue: Boolean = false) {
    val mode = when {
        state.sending -> SendMode.SENDING
        state.busy && text.isBlank() && state.attachments.isEmpty() && state.files.isEmpty() -> SendMode.STOP
        state.busy && queue && !text.trimStart().startsWith("/") -> SendMode.QUEUE
        state.busy && (state.attachments.isNotEmpty() || state.files.isNotEmpty()) -> SendMode.SEND
        state.busy && !text.trimStart().startsWith("/") -> SendMode.STEER
        else -> SendMode.SEND
    }
    val enabled = when (mode) {
        SendMode.SEND, SendMode.QUEUE -> text.isNotBlank() || state.attachments.isNotEmpty() || state.files.isNotEmpty()
        SendMode.STEER -> text.isNotBlank()
        SendMode.STOP -> true
        SendMode.SENDING -> false
    }
    val bg by animateColorAsState(when {
        mode == SendMode.STOP -> Bad
        enabled -> Gold
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }, label = "send-bg")
    val scale by animateFloatAsState(if (enabled) 1f else 0.92f, spring(dampingRatio = 0.6f), label = "send-scale")
    Box(Modifier.size(42.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(bg).clickable(enabled = enabled) {
        if (mode == SendMode.STOP) onStop() else onSend()
    }, contentAlignment = Alignment.Center) {
        AnimatedContent(mode, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { m ->
            val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            when (m) {
                SendMode.SENDING -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Gold)
                SendMode.STOP -> Icon(Glyphs.Stop, "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
                SendMode.STEER -> Icon(Glyphs.Steer, "Steer", tint = tint, modifier = Modifier.size(20.dp))
                SendMode.QUEUE -> Icon(Glyphs.Clock, "Queue", tint = tint, modifier = Modifier.size(20.dp))
                SendMode.SEND -> Icon(Glyphs.Send, "Send", tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    catalog: ModelCatalog?, current: ModelOption?, newChat: Boolean, onDismiss: () -> Unit, onPick: (ModelOption?) -> Unit,
    title: String = "Model", subtitle: String? = null, offerHermesDefault: Boolean = newChat,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle ?: if (newChat) "Used by the chat you start next." else "Switches this chat, here and on the desktop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            if (catalog == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                return@Column
            }
            var query by remember { mutableStateOf("") }
            SearchField(query, { query = it }, "Search models")
            Spacer(Modifier.size(8.dp))
            val groups = remember(catalog, query) {
                catalog.options.filter {
                    query.isBlank() || it.label.contains(query, true) || it.providerName.contains(query, true) || it.id.contains(query, true)
                }.groupBy { it.providerName }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (offerHermesDefault) item {
                    SheetOption("Hermes default", "Whatever Hermes is configured to use", selected = current == null) { onPick(null) }
                }
                groups.forEach { (provider, options) ->
                    item(key = "h-$provider") { SectionLabel(provider, Modifier.padding(top = 8.dp)) }
                    items(options, key = { "${it.provider}/${it.id}" }) { o ->
                        SheetOption(o.label, if (o.current) "Hermes default" else null,
                            selected = current?.id == o.id && (current.provider.isEmpty() || current.provider == o.provider)) { onPick(o) }
                    }
                }
                if (groups.isEmpty()) item {
                    Text("No model matches “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(
    projects: List<ProjectRef>, current: String?, onDismiss: () -> Unit,
    actions: ProjectActions, onPick: (String?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProjectRef?>(null) }
    var query by remember { mutableStateOf("") }
    val shown = remember(projects, query) {
        val q = query.trim()
        if (q.isEmpty()) projects else projects.filter { it.label.contains(q, true) || it.path?.contains(q, true) == true }
    }
    // A project without a folder can't host a chat yet: keep the sheet open so it shows in the list.
    if (creating) NewProjectDialog(onDismiss = { creating = false }, actions = actions,
        onCreated = { hasFolder -> creating = false; if (hasFolder) onDismiss() })
    editing?.let { p -> EditProjectDialog(p.id, p.label, actions, onDismiss = { editing = null }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Project", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("The new chat starts in this project's folder and shows under it on the desktop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            SearchField(query, { query = it }, "Search projects", Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (query.isBlank()) {
                    item { SheetOption("+ New project", "Create one on the PC; the folder is optional", selected = false) { creating = true } }
                    item { SheetOption("No project", "Hermes' default folder", selected = current == null) { onPick(null) } }
                }
                if (shown.isEmpty()) item {
                    Text("No project matches “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
                items(shown, key = { it.id }) { p ->
                    if (p.path == null) {
                        // Saved without a folder: chats need one to start in, so tapping asks for it.
                        SheetOption(p.label, "No folder yet: tap to choose one", selected = false,
                            onEdit = { editing = p }) { editing = p }
                    } else {
                        SheetOption(p.label, p.path, selected = current == p.id,
                            onEdit = if (p.auto) null else ({ editing = p })) { onPick(p.id) }
                    }
                }
            }
        }
    }
}

private val effortHints = mapOf(
    "none" to "Answer directly, no thinking",
    "minimal" to "Barely any thinking",
    "low" to "Quick, light reasoning",
    "medium" to "Balanced",
    "high" to "Thinks it through; slower",
    "xhigh" to "Deep reasoning for hard problems",
    "max" to "Everything it has; slowest",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningSheet(catalog: ModelCatalog?, reasoning: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text("Reasoning effort", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("How hard the model thinks before answering. Models without reasoning ignore it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            val default = catalog?.reasoningDefault?.let(::effortLabel)
            SheetOption("Auto", "Hermes' setting" + (default?.let { " ($it)" } ?: ""), selected = reasoning == null) { onPick(null) }
            (catalog?.reasoningLevels ?: REASONING_LEVELS).forEach { level ->
                SheetOption(effortLabel(level), effortHints[level], selected = reasoning == level) { onPick(level) }
            }
        }
    }
}

@Composable
private fun SheetOption(title: String, subtitle: String?, selected: Boolean, onEdit: (() -> Unit)? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Gold.copy(alpha = 0.14f) else Color.Transparent, label = "opt")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Glyphs.Check, null, tint = Gold, modifier = Modifier.size(20.dp))
        }
        onEdit?.let { IconButton(onClick = it) { Icon(Glyphs.Edit, "Edit project", modifier = Modifier.size(18.dp)) } }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    // A slim pill (40 dp, like the buttons next to it), not Material's 56 dp TextField, and it shrinks
    // with a narrow chat list: the hint is cut with an ellipsis instead of wrapping or pushing icons out.
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp, end = if (value.isEmpty()) 12.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Search, null, tint = muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Gold))
            }
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(36.dp)) {
                Icon(Glyphs.Close, "Clear", modifier = Modifier.size(16.dp))
            }
        }
    }
}


// ------------------------------------------------------------------ pictures

/** Longest side a chat picture is decoded to: sharp on a phone screen, about 4 MB per bitmap. */
private const val CHAT_IMAGE_SIDE = 1080

/**
 * Decoded chat pictures, shared by every bubble. Decoding at full size on every scroll (a 4K
 * screenshot is a 33 MB bitmap) ran the app out of memory when scrolling far up: it froze while the
 * GC thrashed, then crashed. Pictures are now downsampled and kept, so scrolling back is free.
 */
private object DecodedImages {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(ref: String, side: Int): ImageBitmap? = cache.get("$side|$ref")

    fun decode(ref: String, bytes: ByteArray, side: Int): ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, side)
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()?.also { cache.put("$side|$ref", it) }
}

/** The power-of-two sample size that keeps the long side at or above [side] (BitmapFactory's rule). */
internal fun sampleSizeFor(width: Int, height: Int, side: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= side) sample *= 2
    return sample
}

/** One chat picture, decoded off the main thread; a quiet placeholder while it loads or if it can't. */
@Composable
private fun ChatImage(ref: String, modifier: Modifier, crop: Boolean = false, onClick: (() -> Unit)? = null) {
    val load = LocalImageLoader.current
    val bitmap by produceState(DecodedImages.get(ref, CHAT_IMAGE_SIDE), ref) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            load(ref)?.let { b -> DecodedImages.decode(ref, b, CHAT_IMAGE_SIDE) }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    } else {
        Image(bmp, "Picture", contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier))
    }
}

/** Pictures of one message: a single one large, several as a scrolling row. Tap opens it full screen. */
@Composable
private fun ImageStrip(refs: List<String>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    if (refs.size == 1) {
        ChatImage(refs[0], modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)),
            onClick = { open = refs[0] })
    } else {
        LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(refs, key = { it }) { r ->
                ChatImage(r, Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)), crop = true, onClick = { open = r })
            }
        }
    }
    open?.let { ImageViewer(it) { open = null } }
}

/** Full-screen picture: pinch to zoom, drag to pan, Download saves the original, X (or back) closes. */
@Composable
private fun ImageViewer(ref: String, onClose: () -> Unit) {
    val load = LocalImageLoader.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    // Android 8/9 have no MediaStore folder writes without a permission: let the user pick a file.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val bytes = pending; pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            toast(if (ok) "Picture saved" else "Couldn't save the picture")
        }
    }
    fun download() = scope.launch {
        val bytes = load(ref) ?: return@launch toast("Couldn't load the picture")
        val name = pictureFileName(ref, bytes)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { savePicture(context, name, bytes) }
            toast(if (ok) "Saved to Pictures/Hermes Remote" else "Couldn't save the picture")
        } else { pending = bytes; pick.launch(name) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var dx by remember { mutableFloatStateOf(0f) }
        var dy by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ChatImage(ref, Modifier.fillMaxSize().pointerInput(ref) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale == 1f) { dx = 0f; dy = 0f } else { dx += pan.x; dy += pan.y }
                }
            }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = dx; translationY = dy })
            var info by remember { mutableStateOf(false) }
            if (info) FileInfoDialog(ref) { info = false }
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = { info = true }) { Icon(Glyphs.Info, "Info", tint = Color.White) }
                IconButton(onClick = { download() }) { Icon(Glyphs.Download, "Download", tint = Color.White) }
                IconButton(onClick = onClose) { Icon(Glyphs.Close, "Close", tint = Color.White) }
            }
        }
    }
}

/** The file name a saved picture gets: the original's when it has one, else a timestamp; extension from its bytes. */
internal fun pictureFileName(ref: String, bytes: ByteArray, now: Long = System.currentTimeMillis()): String {
    val ext = pictureExtension(bytes)
    val base = ref.substringBefore('?').substringAfterLast('/').substringAfterLast(':')
        .substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
    return (if (base.length >= 3) base else "hermes-$now") + "." + ext
}

/** png/jpg/gif/webp from the file's magic bytes (jpg when unknown). */
internal fun pictureExtension(b: ByteArray): String = when {
    b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
    b.size >= 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
    b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> "jpg"
}

/** Android 10+: writes into Pictures/Hermes Remote through MediaStore (no permission needed). */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
private fun savePicture(context: android.content.Context, name: String, bytes: ByteArray): Boolean = runCatching {
    val mime = when (name.substringAfterLast('.')) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Hermes Remote")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
    if (!ok) { resolver.delete(uri, null, null); return false }
    values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}.getOrDefault(false)

// ------------------------------------------------------------------ files

/** Files of one message, one card each: tap opens it in a phone app, the arrow saves it to Downloads. */
@Composable
internal fun FileCards(paths: List<String>, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    if (io.github.nideta231.hermesremote.data.HostFeature.CHAT_FILE_DOWNLOAD in LocalHiddenFeatures.current) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        paths.forEach { FileCard(it) }
    }
}

@Composable
private fun FileCard(path: String) {
    val fetch = LocalFileFetcher.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(path) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    val name = remember(path) { fileName(path) }
    var info by remember { mutableStateOf(false) }
    if (info) FileInfoDialog(path) { info = false }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    // Android 8/9: no MediaStore Downloads without a permission, so the user picks where it goes.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mimeOf(name))) { uri ->
        val file = pending; pending = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            toast(if (ok) "Saved $name" else "Couldn't save $name")
        }
    }
    fun withFile(action: (java.io.File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(fetch(path)) }
            catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                toast(fileError(name, t))
            } finally { busy = false }
        }
    }
    fun open() = withFile { f ->
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(name))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(android.content.Intent.createChooser(intent, name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { toast("No app on this phone opens $name. Use the download arrow.") }
    }
    fun save() = withFile { f ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveDownload(context, name, f) }
            toast(if (ok) "Saved to Downloads/Hermes Remote" else "Couldn't save $name")
        } else { pending = f; pick.launch(name) }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.widthIn(min = 180.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).clickable { open() }) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Doc, null, tint = Gold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (busy) "Getting it from the PC…" else name.substringAfterLast('.', "file").uppercase().take(6) + " · tap to open",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { info = true }) { Icon(Glyphs.Info, "Info about $name", modifier = Modifier.size(18.dp)) }
            if (busy) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Gold)
            } else IconButton(onClick = { save() }) { Icon(Glyphs.Download, "Save $name", modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Board attachments with a preview: pictures as thumbnails (tap opens them full screen with Info and
 *  Download), text files' first lines under their file card, anything else as a plain file card. */
@Composable
internal fun AttachmentPreviews(paths: List<String>, modifier: Modifier = Modifier) {
    if (io.github.nideta231.hermesremote.data.HostFeature.CHAT_FILE_DOWNLOAD in LocalHiddenFeatures.current) return
    val kind = io.github.nideta231.hermesremote.data.FileAttachments::previewOf
    val pics = paths.filter { kind(it) == io.github.nideta231.hermesremote.data.FileAttachments.Preview.PICTURE }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pics.isNotEmpty()) ImageStrip(pics)
        (paths - pics.toSet()).forEach { p ->
            FileCard(p)
            if (kind(p) == io.github.nideta231.hermesremote.data.FileAttachments.Preview.TEXT) TextPeek(p)
        }
    }
}

/** The first lines of a text attachment, fetched from the PC once (the file stays cached). */
@Composable
private fun TextPeek(path: String) {
    val fetch = LocalFileFetcher.current
    val peek by produceState<String?>(null, path) {
        value = runCatching {
            val f = fetch(path)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = f.inputStream().use { input -> ByteArray(4096).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
                io.github.nideta231.hermesremote.data.FileAttachments.textPeek(String(bytes, Charsets.UTF_8))
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
    }
    val text = peek?.takeIf { it.isNotBlank() } ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.widthIn(max = 300.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** Info sheet of a chat file or picture: name, full path (tap to copy), size, type, modified. */
@Composable
private fun FileInfoDialog(ref: String, onClose: () -> Unit) {
    val lookup = LocalFileInfo.current
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by produceState<Result<io.github.nideta231.hermesremote.data.FileInfo>?>(null, ref) {
        value = runCatching { lookup(ref) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    val info = state?.getOrNull()
    AlertDialog(onDismissRequest = onClose,
        title = { Text(info?.name ?: fileName(ref), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Gold)
                        Spacer(Modifier.width(8.dp)); Text("Asking the PC…", style = MaterialTheme.typography.bodySmall)
                    }
                    info == null -> Text(fileError(fileName(ref), state!!.exceptionOrNull()!!), style = MaterialTheme.typography.bodySmall)
                }
                val path = info?.path ?: ref
                InfoLine("Path", path) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(path))
                    android.widget.Toast.makeText(context, "Path copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (info != null) {
                    if (!info.exists) InfoLine("Size", "No longer on the PC")
                    info.size?.let { InfoLine("Size", io.github.nideta231.hermesremote.data.humanSize(it) + if (it >= 1024) " (%,d bytes)".format(it) else "") }
                    info.mime?.let { InfoLine("Type", it) }
                    info.modified?.let {
                        InfoLine("Modified", java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(java.util.Date(it * 1000)))
                    }
                    info.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()) {
        Text(label + if (onClick != null) " · tap to copy" else "", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What went wrong getting a chat file, in words. */
internal fun fileError(name: String, t: Throwable): String = when ((t as? io.github.nideta231.hermesremote.data.BridgeException)?.httpCode) {
    404 -> if (t.message?.contains("not found", true) == true) "$name is no longer on the PC" else "Update the PC bridge to open files from the phone"
    403 -> "The PC won't share $name"
    413 -> "$name is too large to send to the phone"
    else -> "Couldn't get $name: ${t.message ?: t.javaClass.simpleName}"
}

/** MIME type from the file name (for the viewer chooser and the save dialog). */
internal fun mimeOf(name: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

/** Android 10+: copies [file] into Downloads/Hermes Remote through MediaStore (no permission needed). */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
private fun saveDownload(context: android.content.Context, name: String, file: java.io.File): Boolean = runCatching {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeOf(name))
        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Hermes Remote")
        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching { resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
    if (!ok) { resolver.delete(uri, null, null); return false }
    values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}.getOrDefault(false)

// ------------------------------------------------------------------ masked prompts

/** How a masked prompt stays in the transcript: what was asked, and "sent" or "skipped" (never the value). */
@Composable
private fun SecretRecord(item: ChatItem.Secret) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("${item.request.title}: ${item.outcome}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Sudo / secret / password-manager prompts, answered from the phone like the desktop's masked card.
 * The value is sent once over the paired, encrypted link and not stored on the phone.
 */
@Composable
private fun SecretDock(item: ChatItem.Secret, onAnswer: (String, String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(item.key) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val r = item.request
    var identifier by remember(item.key) { mutableStateOf("") }
    var value by remember(item.key) { mutableStateOf("") }
    var shown by remember(item.key) { mutableStateOf(!r.masked) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Warn.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Shield, null, tint = Warn, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontWeight = FontWeight.SemiBold)
                    r.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            fontFamily = if (r.method == "sudo") FontFamily.Monospace else null)
                    }
                }
            }
            if (r.login) {
                OutlinedTextField(identifier, { identifier = it }, label = { Text("Username or email") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            }
            OutlinedTextField(value, { value = it }, label = { Text(r.label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = if (r.login) 8.dp else 12.dp), shape = RoundedCornerShape(14.dp),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = when {
                    r.method == "vault.code" -> KeyboardType.Number
                    shown -> KeyboardType.Text
                    else -> KeyboardType.Password
                }),
                trailingIcon = if (r.masked) ({
                    TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") }
                }) else null)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAnswer("", "") }, modifier = Modifier.weight(1f)) { Text("Skip") }
                Button(onClick = { onAnswer(identifier, value) }, modifier = Modifier.weight(1f),
                    enabled = value.isNotEmpty() && (!r.login || identifier.isNotBlank())) { Text(if (r.login) "Save" else "Send") }
            }
        }
    }
}

/** Shift+Enter: replace the selection with a newline and put the cursor after it. */
internal fun insertNewline(v: TextFieldValue): TextFieldValue {
    val start = minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
    val end = maxOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
    return TextFieldValue(v.text.substring(0, start) + "\n" + v.text.substring(end), TextRange(start + 1))
}
