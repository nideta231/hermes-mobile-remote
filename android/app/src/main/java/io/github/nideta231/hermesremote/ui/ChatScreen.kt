package io.github.nideta231.hermesremote.ui

import io.github.nideta231.hermesremote.UpdateState
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
import androidx.compose.runtime.getValue
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
import io.github.nideta231.hermesremote.ModelConfirm
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.LiveReducer
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
)

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
) {
    var modelSheet by remember { mutableStateOf(false) }
    var reasoningSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (catalog == null) actions.loadModels() }
    LaunchedEffect(openModelPicker) {
        if (openModelPicker) { modelSheet = true; actions.modelPickerOpened() }
    }
    val approval = LiveReducer.openApproval(state.items)
    val clarify = LiveReducer.openClarify(state.items)

    Column(Modifier.fillMaxSize().imePadding()) {
        ChatTopBar(state, health, showMenuButton, actions)
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
                    else -> MessageList(state)
                }
            }
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
        SlashSuggestions(state, suggestions, actions.draft)
        Composer(state, catalog, model, reasoning, actions,
            onModel = { actions.loadModels(); modelSheet = true },
            onReasoning = { actions.loadModels(); reasoningSheet = true })
    }

    if (modelSheet) ModelSheet(catalog, model, state.sessionId == null, onDismiss = { modelSheet = false }) {
        actions.chooseModel(it, false); modelSheet = false
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
private fun ChatTopBar(state: ChatState, health: LinkHealth, showMenu: Boolean, actions: ChatActions) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) IconButton(onClick = actions.openDrawer) { Icon(Glyphs.Menu, "Sessions") }
            else Spacer(Modifier.width(12.dp))
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
                }
            }
            LinkBadge(health, actions.openSettings)
            if (state.sessionId != null) {
                val pinTint by animateColorAsState(if (state.pinned) Gold else MaterialTheme.colorScheme.onSurfaceVariant, label = "pin")
                IconButton(onClick = actions.togglePin) {
                    Icon(Glyphs.Pin, if (state.pinned) "Unpin" else "Pin", tint = pinTint)
                }
            }
            IconButton(onClick = actions.newChat) { Icon(Glyphs.Compose, "New chat") }
        }
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
private fun MessageList(state: ChatState) {
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
    LaunchedEffect(state.items.size, lastText, viewport, state.busy) {
        if (follow && state.items.isNotEmpty()) listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2)
    }
    val last = state.items.lastOrNull()
    val thinking = state.busy && !state.waiting &&
        !(last is ChatItem.Assistant && last.streaming) && !(last is ChatItem.Thinking && last.streaming) &&
        !(last is ChatItem.Tool && last.status == ToolStatus.RUNNING)

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
                        is ChatItem.Tool -> ToolRow(item)
                        is ChatItem.Approval -> if (item.decided != null) ApprovalRecord(item)
                        is ChatItem.Clarify -> if (item.answer != null) ClarifyRecord(item)
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

@Composable
private fun UserBubble(item: ChatItem.User) {
    val alpha by animateFloatAsState(if (item.pending) 0.10f else 0.17f, label = "bubble")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = Gold.copy(alpha = alpha), shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 560.dp).padding(start = 40.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).then(copyable(item.text))) {
            Text(item.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = if (item.pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant) {
    val shown = rememberSmoothText(item.text, item.streaming)
    val caretOn by animateFloatAsState(if (item.streaming) 1f else 0f, tween(250), label = "caret")
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        MarkdownText(if (caretOn > 0.01f) "$shown ▍" else shown)
        // Copy the whole reply as markdown, like the desktop's copy action under each answer.
        if (!item.streaming && item.text.isNotBlank()) CopyButton(item.text, Modifier.offset(x = (-6).dp))
    }
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
            Text(item.text, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
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
        Column(Modifier.shimmer(item.status == ToolStatus.RUNNING).clickable(enabled = item.result != null) { open = !open }
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
                if (item.result != null) {
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                    Icon(Glyphs.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).rotate(rot))
                }
            }
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
                Text(item.text.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()))
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
) {
    val text = state.draft
    val haptics = LocalHapticFeedback.current
    val submit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // The view model clears the draft only when the send is accepted, so a
        // refused send keeps its text here instead of losing it.
        if (state.busy && !text.trimStart().startsWith("/")) actions.steer(text) else actions.send(text)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.animateContentSize()) {
                    TextField(
                        value = text, onValueChange = actions.draft,
                        // Hardware keyboard: Ctrl+Enter sends; Enter and Shift+Enter stay a newline.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).onPreviewKeyEvent { e ->
                            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (!enter || !e.isCtrlPressed) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown && !state.sending && text.isNotBlank()) submit()
                            true
                        },
                        placeholder = { Text(if (state.busy) "Steer the running task…" else "Message Hermes") },
                        maxLines = 7,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val modelLabel = model?.label ?: catalog?.currentModel?.substringAfterLast('/') ?: "Model"
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Pill(modelLabel, Glyphs.Chip, modifier = Modifier.weight(1f, fill = false),
                                tint = if (model != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onModel)
                            Spacer(Modifier.width(6.dp))
                            Pill(reasoning?.let(::effortLabel) ?: "Auto", Glyphs.Brain,
                                tint = if (reasoning != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onReasoning)
                        }
                        Spacer(Modifier.width(8.dp))
                        SendButton(state, text, onSend = submit, onStop = actions.stop)
                    }
                }
            }
        }
    }
}

private enum class SendMode { SEND, STEER, STOP, SENDING }

@Composable
private fun SendButton(state: ChatState, text: String, onSend: () -> Unit, onStop: () -> Unit) {
    val mode = when {
        state.sending -> SendMode.SENDING
        state.busy && text.isBlank() -> SendMode.STOP
        state.busy && !text.trimStart().startsWith("/") -> SendMode.STEER
        else -> SendMode.SEND
    }
    val enabled = when (mode) {
        SendMode.SEND, SendMode.STEER -> text.isNotBlank()
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
                SendMode.SEND -> Icon(Glyphs.Send, "Send", tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(catalog: ModelCatalog?, current: ModelOption?, newChat: Boolean, onDismiss: () -> Unit, onPick: (ModelOption?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (newChat) "Used by the chat you start next." else "Switches this chat, here and on the desktop.",
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
                if (newChat) item {
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
private fun SheetOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
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
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape, modifier = modifier.fillMaxWidth()) {
        TextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint) },
            leadingIcon = { Icon(Glyphs.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Glyphs.Close, "Clear", modifier = Modifier.size(18.dp)) } }) else null,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    }
}
