package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.ChatState
import io.github.nideta231.hermesremote.ConnectionState
import io.github.nideta231.hermesremote.Link
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import io.github.nideta231.hermesremote.data.REASONING_LEVELS
import io.github.nideta231.hermesremote.data.SlashCommand
import io.github.nideta231.hermesremote.data.ToolStatus
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.matchCommands
import kotlinx.coroutines.launch

/** Everything the chat screen can ask the app to do. */
class ChatActions(
    val send: (String) -> Unit,
    val stop: () -> Unit,
    val steer: (String) -> Unit,
    val approve: (String) -> Unit,
    val draft: (String) -> Unit,
    val togglePin: () -> Unit,
    val newChat: () -> Unit,
    val openDrawer: () -> Unit,
    val openSettings: () -> Unit,
    val loadModels: () -> Unit,
    val chooseModel: (ModelOption?) -> Unit,
    val setReasoning: (String?) -> Unit,
    val loadCommands: () -> Unit,
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
    choice: ModelOption?,
    reasoning: String?,
    commands: List<SlashCommand>,
    openModelPicker: Boolean,
    showMenuButton: Boolean,
    actions: ChatActions,
) {
    var modelSheet by remember { mutableStateOf(false) }
    var reasoningSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (catalog == null) actions.loadModels()
        actions.loadCommands() // warm the PC-side command process before the user types "/"
    }
    LaunchedEffect(openModelPicker) {
        if (openModelPicker) { modelSheet = true; actions.modelPickerOpened() }
    }
    val pending = state.items.lastOrNull { it is ChatItem.Approval && it.decided == null } as? ChatItem.Approval

    Column(Modifier.fillMaxSize().imePadding()) {
        ChatTopBar(state, health, showMenuButton, actions)
        ConnectionBanner(state, health, actions.openSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = when {
                    state.loading -> 0
                    state.items.isEmpty() -> 1
                    else -> 2
                },
                transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut() },
                label = "chat-body",
            ) { body ->
                when (body) {
                    0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                    1 -> EmptyChat(onPick = { actions.draft(it) })
                    else -> MessageList(state)
                }
            }
        }
        AnimatedVisibility(pending != null && state.run?.status == "waiting_for_approval",
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            pending?.let { ApprovalDock(it, actions.approve) }
        }
        SlashSuggestions(state, commands, actions.loadCommands, actions.draft)
        Composer(state, catalog, choice, reasoning, actions,
            onModel = { actions.loadModels(); modelSheet = true },
            onReasoning = { actions.loadModels(); reasoningSheet = true })
    }

    if (modelSheet) ModelSheet(catalog, choice, onDismiss = { modelSheet = false }) { actions.chooseModel(it); modelSheet = false }
    if (reasoningSheet) ReasoningSheet(catalog, reasoning, onDismiss = { reasoningSheet = false }) { actions.setReasoning(it); reasoningSheet = false }
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
                    state.run?.status == "waiting_for_approval" -> Triple("Needs your approval", Warn, true)
                    state.run?.status == "stopping" -> Triple("Stopping…", Warn, true)
                    state.busy -> Triple("Working…", Gold, true)
                    state.remoteActive -> Triple("Running on another device", MaterialTheme.colorScheme.primary, true)
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

// ------------------------------------------------------------------ empty state

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
        Text("It runs on your PC with its full toolset. Type / for commands and skills.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    val lastText = (state.items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    // The keyboard shrinks the viewport while the list keeps its top anchored; re-pin to the bottom.
    val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(state.items.size, lastText, viewport, state.busy) {
        if (follow && state.items.isNotEmpty()) listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, Int.MAX_VALUE / 2)
    }
    val thinking = state.busy && state.run?.status != "waiting_for_approval" &&
        state.items.lastOrNull().let { it !is ChatItem.Assistant || !it.streaming }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.items, key = { it.key }) { item ->
                Box(Modifier.animateItem(fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow))) {
                    when (item) {
                        is ChatItem.User -> UserBubble(item)
                        is ChatItem.Assistant -> AssistantBlock(item)
                        is ChatItem.Tool -> ToolRow(item)
                        is ChatItem.Approval -> if (item.decided != null) ApprovalRecord(item)
                        is ChatItem.Notice -> NoticeLine(item)
                        is ChatItem.CommandOutput -> CommandOutputCard(item)
                    }
                }
            }
            if (thinking) item(key = "thinking") {
                Row(Modifier.animateItem().padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) { TypingDots(Gold) }
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
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = Gold.copy(alpha = if (item.pending) 0.10f else 0.17f), shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 560.dp).padding(start = 40.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).then(copyable(item.text))) {
            Text(item.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = if (item.pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant) {
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        MarkdownText(item.text + if (item.streaming) " ▍" else "")
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
    val color = when (item.status) {
        ToolStatus.RUNNING -> Gold
        ToolStatus.OK -> Ok
        ToolStatus.FAILED -> Bad
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Column(Modifier.clickable(enabled = item.result != null) { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.status == ToolStatus.RUNNING) StatusDot(color, pulse = true, size = 8)
                else StatusDot(color, size = 8)
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
                    val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chev")
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(copyable(item.text))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Terminal, null, tint = Gold, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.command, style = MaterialTheme.typography.labelLarge, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.text.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()))
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
                Text("Approval: ${choiceLabel(item.decided ?: "")}",
                    style = MaterialTheme.typography.labelLarge, color = Warn)
            }
            item.request.command?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
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
                    androidx.compose.material3.OutlinedButton(onClick = { onApproval("deny") }, modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Bad)) { Text("Deny") }
                }
                if ("once" in choices) {
                    androidx.compose.material3.Button(onClick = { onApproval("once") }, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
            }
            val extra = choices.filter { it == "session" || it == "always" }
            if (extra.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                    extra.forEach { c ->
                        androidx.compose.material3.TextButton(onClick = { onApproval(c) }) { Text(choiceLabel(c), maxLines = 1) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ composer

@Composable
private fun SlashSuggestions(state: ChatState, commands: List<SlashCommand>, onLoad: () -> Unit, onDraft: (String) -> Unit) {
    val typing = state.draft.startsWith("/") && !state.busy
    LaunchedEffect(typing) { if (typing) onLoad() }
    val matches = remember(commands, state.draft) { if (typing) matchCommands(commands, state.draft) else emptyList() }
    AnimatedVisibility(typing && (matches.isNotEmpty() || (commands.isEmpty() && !state.draft.contains(' '))),
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            if (matches.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(); Spacer(Modifier.width(10.dp))
                    Text("Loading commands", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Surface
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(matches, key = { it.name }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onDraft("/${c.name} ") }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (c.kind) { "skill" -> Glyphs.Spark; "app" -> Glyphs.Bolt; else -> Glyphs.Terminal }
                        Icon(icon, null, tint = if (c.kind == "skill") Gold else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("/${c.name}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                if (c.args.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(c.args, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(c.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    choice: ModelOption?,
    reasoning: String?,
    actions: ChatActions,
    onModel: () -> Unit,
    onReasoning: () -> Unit,
) {
    val text = state.draft
    val haptics = LocalHapticFeedback.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.animateContentSize()) {
                    TextField(
                        value = text, onValueChange = actions.draft,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        placeholder = { Text(if (state.busy) "Steer the running task…" else "Message Hermes") },
                        maxLines = 7,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val modelLabel = choice?.label ?: state.sessionModel?.substringAfterLast('/') ?: catalog?.currentModel?.substringAfterLast('/') ?: "Model"
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Pill(modelLabel, Glyphs.Chip, modifier = Modifier.weight(1f, fill = false),
                                tint = if (choice != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onModel)
                            Spacer(Modifier.width(6.dp))
                            Pill(reasoning?.let(::effortLabel) ?: "Auto", Glyphs.Brain,
                                tint = if (reasoning != null) Gold else MaterialTheme.colorScheme.onSurfaceVariant, onClick = onReasoning)
                        }
                        Spacer(Modifier.width(8.dp))
                        SendButton(state, text, onSend = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // The view model clears the draft only when the send is accepted, so a
                            // refused send keeps its text here instead of losing it.
                            if (state.busy) actions.steer(text) else actions.send(text)
                        }, onStop = actions.stop)
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
        state.busy -> SendMode.STEER
        else -> SendMode.SEND
    }
    val enabled = when (mode) {
        SendMode.SEND, SendMode.STEER -> text.isNotBlank()
        SendMode.STOP -> state.run?.status != "stopping"
        SendMode.SENDING -> false
    }
    val bg by animateColorAsState(when {
        mode == SendMode.STOP -> Bad
        enabled -> Gold
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }, label = "send-bg")
    Box(Modifier.size(42.dp).clip(CircleShape).background(bg).clickable(enabled = enabled) {
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
private fun ModelSheet(catalog: ModelCatalog?, choice: ModelOption?, onDismiss: () -> Unit, onPick: (ModelOption?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Used for your next messages from this phone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                item {
                    SheetOption("Session default", "Whatever this chat already uses", selected = choice == null) { onPick(null) }
                }
                groups.forEach { (provider, options) ->
                    item(key = "h-$provider") { SectionLabel(provider, Modifier.padding(top = 8.dp)) }
                    items(options, key = { "${it.provider}/${it.id}" }) { o ->
                        SheetOption(o.label, if (o.current) "Current default" else null,
                            selected = choice?.id == o.id && choice.provider == o.provider) { onPick(o) }
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
