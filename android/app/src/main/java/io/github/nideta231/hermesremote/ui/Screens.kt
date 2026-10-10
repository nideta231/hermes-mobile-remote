package io.github.nideta231.hermesremote.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import io.github.nideta231.hermesremote.ConnectionState
import io.github.nideta231.hermesremote.PairedPc
import io.github.nideta231.hermesremote.SessionsState
import io.github.nideta231.hermesremote.SystemState
import io.github.nideta231.hermesremote.UpdateState
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.PairingParser
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ================================================================ Sessions

class SessionActions(
    val open: (String) -> Unit,
    val refresh: () -> Unit,
    val loadMore: () -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val setPinned: (String, Boolean) -> Unit,
    val newChat: () -> Unit,
    val openSettings: () -> Unit,
    val switchPc: (String) -> Unit = {},
    val addPc: () -> Unit = {},
    val renamePc: (String, String) -> Unit = { _, _ -> },
    val forgetPc: (String) -> Unit = {},
)

private fun groupOf(s: SessionSummary): String {
    if (s.pinned) return "Pinned"
    val ts = s.lastActive ?: return "Older"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
    val days = ((now.timeInMillis - then.timeInMillis) / 86_400_000L).toInt()
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today"
        days < 2 -> "Yesterday"
        days < 7 -> "This week"
        days < 31 -> "This month"
        else -> "Older"
    }
}

private val groupOrder = listOf("Pinned", "Today", "Yesterday", "This week", "This month", "Older")

/** The session list. On phones it lives in the navigation drawer; on tablets beside the chat. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsPane(
    state: SessionsState,
    currentId: String?,
    actions: SessionActions,
    modifier: Modifier = Modifier,
    pcs: List<PairedPc> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    var pcSheet by remember { mutableStateOf(false) }
    val activePc = pcs.firstOrNull { it.active }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }

    val grouped = remember(state.items, query) {
        state.items
            .filter { query.isBlank() || it.displayTitle.contains(query, true) || (it.preview?.contains(query, true) ?: false) }
            .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActive ?: 0.0 })
            .groupBy(::groupOf)
            .toSortedMap(compareBy { groupOrder.indexOf(it) })
    }

    Column(modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(Gold.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            // The header doubles as the PC switcher: tap to pick another paired PC or add one.
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { pcSheet = true }.padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text("Hermes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    activePc?.let {
                        Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Glyphs.Down, "Switch PC", modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = actions.refresh) { Icon(Glyphs.Refresh, "Refresh", modifier = Modifier.size(20.dp)) }
        }
        if (pcSheet) PcSheet(pcs, actions, onDismiss = { pcSheet = false })
        Button(onClick = actions.newChat, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            Icon(Glyphs.Compose, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New chat", fontWeight = FontWeight.SemiBold)
        }
        SearchField(query, { query = it }, "Search chats", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        PullToRefreshBox(isRefreshing = state.loading && state.items.isNotEmpty(), onRefresh = actions.refresh,
            modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                state.error?.let { err ->
                    item(key = "err") {
                        Text(err, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
                grouped.forEach { (group, list) ->
                    item(key = "g-$group") { SectionLabel(group, Modifier.padding(start = 12.dp, top = 6.dp).animateItem()) }
                    items(list, key = { it.id }) { s ->
                        SessionRow(s, current = s.id == currentId, live = state.live[s.id],
                            modifier = Modifier.animateItem(),
                            onClick = { actions.open(s.id) }, onLongClick = { menuFor = s })
                    }
                }
                item {
                    when {
                        state.hasMore && query.isBlank() -> {
                            LaunchedEffect(state.items.size) { actions.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { TypingDots() }
                        }
                        state.loading && state.items.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                        grouped.isEmpty() -> Text(if (query.isBlank()) "No chats yet." else "Nothing matches “$query”.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        PanelRow("Settings", icon = Glyphs.Settings, onClick = actions.openSettings)
    }

    menuFor?.let { s ->
        SessionActionsSheet(s, onDismiss = { menuFor = null },
            onPin = { actions.setPinned(s.id, !s.pinned); menuFor = null },
            onRename = { renaming = s; menuFor = null },
            onDelete = { deleting = s; menuFor = null })
    }
    renaming?.let { s ->
        var title by remember { mutableStateOf(s.title ?: "") }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { actions.rename(s.id, title.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete chat?") },
            text = { Text("“${s.displayTitle}” will be permanently removed from Hermes on your PC.") },
            confirmButton = { TextButton(onClick = { actions.delete(s.id); deleting = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** Paired PCs: tap to switch, long-press to rename or forget, plus "Add PC". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PcSheet(pcs: List<PairedPc>, actions: SessionActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var menuFor by remember { mutableStateOf<PairedPc?>(null) }
    var renaming by remember { mutableStateOf<PairedPc?>(null) }
    var forgetting by remember { mutableStateOf<PairedPc?>(null) }
    val haptics = LocalHapticFeedback.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text("Paired PCs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            pcs.forEach { pc ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (pc.active) Gold.copy(alpha = 0.13f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onDismiss(); if (!pc.active) actions.switchPc(pc.id) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = pc },
                    ).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Desktop, null, modifier = Modifier.size(20.dp), tint = if (pc.active) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pc.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pc.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (pc.active) Icon(Glyphs.Check, "In use", tint = Gold, modifier = Modifier.size(18.dp))
                    IconButton(onClick = { menuFor = pc }) { Icon(Glyphs.More, "Options", modifier = Modifier.size(18.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onDismiss(); actions.addPc() }
                .padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Plus, null, modifier = Modifier.size(20.dp), tint = Gold)
                Spacer(Modifier.width(12.dp))
                Text("Add PC", fontWeight = FontWeight.SemiBold, color = Gold)
            }
        }
    }

    menuFor?.let { pc ->
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(pc.name) },
            text = { Text(pc.url) },
            confirmButton = { TextButton(onClick = { menuFor = null; renaming = pc }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { menuFor = null; forgetting = pc }) { Text("Forget", color = Bad) } })
    }
    renaming?.let { pc ->
        var name by remember(pc.id) { mutableStateOf(pc.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename PC") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { actions.renamePc(pc.id, name.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    forgetting?.let { pc ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget ${pc.name}?") },
            text = { Text("Removes its token and drafts from this phone. Also run `hermes-remote-bridge revoke <name>` on that PC to invalidate it there.") },
            confirmButton = { TextButton(onClick = { forgetting = null; onDismiss(); actions.forgetPc(pc.id) }) { Text("Forget", color = Bad) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, current: Boolean, live: String?, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (current) Gold.copy(alpha = 0.13f) else Color.Transparent, label = "row")
    // Same cue as the desktop sidebar: a working session shimmers, one waiting for you pulses amber.
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).shimmer(live != null, if (live == "waiting") Warn else Gold)
        .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            val meta = listOfNotNull(s.lastActive?.let { relative(it) }, sourceLabel(s.source), "${s.messageCount} msgs").joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (live != null) { Spacer(Modifier.width(8.dp)); StatusDot(if (live == "waiting") Warn else Gold, pulse = true) }
        else if (s.pinned) { Spacer(Modifier.width(8.dp)); Icon(Glyphs.Pin, null, tint = Gold, modifier = Modifier.size(14.dp)) }
    }
}

private fun sourceLabel(source: String?) = when (source) {
    null, "", "api_server" -> null
    "cli" -> "terminal"
    "tui" -> "desktop"
    else -> source
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionsSheet(s: SessionSummary, onDismiss: () -> Unit, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(s.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Text(listOfNotNull("${s.messageCount} messages", s.model?.substringAfterLast('/'), sourceLabel(s.source)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.size(8.dp))
            PanelRow(if (s.pinned) "Unpin" else "Pin", icon = Glyphs.Pin, iconTint = Gold, onClick = onPin)
            PanelRow("Rename", icon = Glyphs.Edit, onClick = onRename)
            PanelRow("Delete", icon = Glyphs.Trash, iconTint = Bad, titleColor = Bad, onClick = onDelete)
        }
    }
}

private fun relative(epochSec: Double): String {
    val diff = System.currentTimeMillis() / 1000.0 - epochSec
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${(diff / 60).toInt()}m ago"
        diff < 86400 -> "${(diff / 3600).toInt()}h ago"
        diff < 7 * 86400 -> "${(diff / 86400).toInt()}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date((epochSec * 1000).toLong()))
    }
}

// ================================================================ Shared page chrome

/** Top bar for secondary pages (settings, desktop): back arrow and a title. */
@Composable
fun PageScaffold(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Glyphs.Back, "Back") } else Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// ================================================================ Settings

class SettingsActions(
    val refresh: () -> Unit,
    val unpair: () -> Unit,
    val setApprovalMode: (String) -> Unit,
    val useTransport: (Transport) -> Unit,
    val useAuto: () -> Unit,
    val checkUpdate: () -> Unit,
    val installUpdate: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SystemState,
    pairing: Pairing?,
    conn: ConnectionState,
    update: UpdateState,
    actions: SettingsActions,
    onBack: (() -> Unit)?,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { actions.refresh() }
    PageScaffold("Settings", onBack) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)) {
                UpdateBanner(update, actions.installUpdate)
                SectionLabel("Connection")
                ConnectionPanel(conn, state.error, actions)
                SectionLabel("Your PC")
                StatusPanel(state)
                SectionLabel("Command approvals")
                ApprovalPanel(state, actions.setApprovalMode)
                SectionLabel("This device")
                Panel {
                    PanelRow("App version", update.installed.ifBlank { "?" }, Glyphs.Download, onClick = actions.checkUpdate) {
                        when {
                            update.checking -> TypingDots()
                            update.available != null -> Pill("Update", tint = Color.Black, container = Gold, onClick = actions.installUpdate)
                            update.checkedAt != null -> Text("Up to date", style = MaterialTheme.typography.labelMedium, color = Ok)
                            else -> Text("Check", style = MaterialTheme.typography.labelMedium, color = Gold)
                        }
                    }
                    update.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Bad, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    pairing?.let { PanelRow("Paired as “${it.device}”", it.url, Glyphs.Qr) }
                    PanelRow("Unpair this device", icon = Glyphs.Close, iconTint = Bad, titleColor = Bad, onClick = { confirmUnpair = true })
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("Unpair?") },
            text = { Text("Removes the token from this phone. Also run `hermes-remote-bridge revoke ${pairing?.device ?: "<name>"}` on the PC to invalidate it there.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; actions.unpair() }) { Text("Unpair", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } })
    }
}

@Composable
private fun UpdateBanner(u: UpdateState, onInstall: () -> Unit) {
    val available = u.available
    AnimatedVisibility(available != null || u.progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = Gold.copy(alpha = 0.14f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp).animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Download, null, tint = Gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (u.progress != null) "Downloading ${available?.version ?: "update"}…" else "Version ${available?.version} is ready",
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                val progress = u.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape))
                } else {
                    available?.notes?.takeIf { it.isNotBlank() }?.let {
                        Box(Modifier.padding(top = 6.dp).heightIn(max = 120.dp)) { MarkdownText(it.take(600)) }
                    }
                    Button(onClick = onInstall, modifier = Modifier.padding(top = 10.dp)) { Text("Update now") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(conn: ConnectionState, error: String?, actions: SettingsActions) {
    Panel {
        val (icon, tint, title) = when {
            conn.searching -> Triple(Glyphs.Refresh, Warn, "Connecting…")
            error != null || conn.activeUrl == null -> Triple(Glyphs.Offline, Bad, "Can't reach your PC")
            conn.transport == Transport.LAN -> Triple(Glyphs.Wifi, Ok, "Local network")
            else -> Triple(Glyphs.Globe, MaterialTheme.colorScheme.secondary, "Tailscale")
        }
        val sub = when {
            error != null -> error
            conn.needsRepairForLan -> "Pair again from the PC to use the local network securely."
            conn.transport == Transport.TAILNET && conn.pcNetwork != null && conn.pcNetworkTrusted == false ->
                "Your PC is on “${conn.pcNetwork}”, which isn't trusted, so it only answers over Tailscale."
            conn.transport == Transport.LAN -> "Direct and encrypted, verified as your PC"
            conn.transport == Transport.TAILNET -> "Through your tailnet"
            else -> null
        }
        PanelRow(title, sub, icon, iconTint = tint) { if (conn.searching) TypingDots(Warn) }
        conn.activeUrl?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp))
        }
        if (error != null) {
            Text("Check that the phone is on a trusted Wi-Fi or Tailscale, the PC is awake, and the bridge runs " +
                "(`hermes-remote-bridge doctor` on the PC).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp))
        }
        Text("Route", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        Segmented(
            options = listOf("Auto", "Local", "Tailscale"),
            selected = when (conn.mode) { TransportMode.AUTO -> 0; TransportMode.LAN -> 1; TransportMode.TAILNET -> 2 },
            enabled = listOf(true, conn.lanAvailable && !conn.needsRepairForLan, conn.tailnetAvailable),
            onSelect = { i -> when (i) { 0 -> actions.useAuto(); 1 -> actions.useTransport(Transport.LAN); else -> actions.useTransport(Transport.TAILNET) } },
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** A row of equal segments with a sliding highlight. */
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: List<Boolean>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val bg by animateColorAsState(if (on) Gold else Color.Transparent, label = "seg$i")
                val fg by animateColorAsState(when {
                    on -> MaterialTheme.colorScheme.onPrimary
                    enabled[i] -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }, label = "segfg$i")
                Box(Modifier.weight(1f).clip(CircleShape).background(bg)
                    .clickable(enabled = enabled[i] && !on) { onSelect(i) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: SystemState) {
    Panel {
        if (state.components.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                if (state.loading) TypingDots(Gold)
                else Text("No status yet. Pull to refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.components.forEach { c ->
            var open by remember(c.key) { mutableStateOf(false) }
            Column(Modifier.animateContentSize()) {
                PanelRow(c.label, c.summary, icon = when (c.key) {
                    "hermes" -> Glyphs.Spark; "model" -> Glyphs.Chip; "desktop" -> Glyphs.Desktop
                    "tailscale" -> Glyphs.Globe; else -> Glyphs.Bolt
                }, onClick = if (c.detail != null) ({ open = !open }) else null) {
                    StatusDot(if (c.ok) Ok else Bad)
                }
                if (open && c.detail != null) {
                    Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp))
                }
            }
        }
        state.checkedAt?.let {
            Text("Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))} · pull to refresh",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
    }
}

private val approvalModes = listOf(
    Triple("manual", "Manual", "Ask before every risky command"),
    Triple("smart", "Smart", "An AI check allows safe commands and asks only when unsure"),
    Triple("off", "Off", "Never ask. Every command runs"),
)

@Composable
private fun ApprovalPanel(state: SystemState, onPick: (String) -> Unit) {
    Panel {
        Text("Applies to Hermes everywhere: desktop, messaging and this app.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
        Segmented(
            options = approvalModes.map { it.second },
            selected = approvalModes.indexOfFirst { it.first == state.approvalMode },
            enabled = approvalModes.map { state.approvalMode != null && !state.approvalSaving },
            onSelect = { onPick(approvalModes[it].first) },
            modifier = Modifier.padding(16.dp),
        )
        val current = approvalModes.firstOrNull { it.first == state.approvalMode }
        AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { m ->
            Text(when {
                state.approvalSaving -> "Saving…"
                m == null -> "Unknown. Pull to refresh."
                else -> m.third
            }, style = MaterialTheme.typography.bodySmall,
                color = if (m?.first == "off") Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        }
    }
}

// ================================================================ Pairing

@Composable
fun PairScreen(
    initialUri: String?,
    onScan: () -> Unit,
    onPickImage: () -> Unit,
    onPair: suspend (Pairing) -> String?,
    imageError: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }

    fun attempt(p: Result<Pairing>) {
        val pairing = p.getOrElse { error = it.message; return }
        busy = true; error = null
        scope.launch {
            error = onPair(pairing)
            busy = false
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) attempt(runCatching { PairingParser.parseUri(initialUri) })
    }
    LaunchedEffect(imageError) { if (imageError != null) error = imageError }
    onCancel?.let { BackHandler(onBack = it) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.offset(x = (-12).dp)) { Text("Cancel") }
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Box(Modifier.size(72.dp).background(Gold.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.size(20.dp))
        Text(if (onCancel != null) "Add another PC" else "Hermes Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(if (onCancel != null) "Each PC keeps its own chats. Switch between them from the side menu."
            else "Your Hermes agent, from your phone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(28.dp))
        Step(1, "On your PC, run", code = "hermes-remote-bridge pair phone")
        Step(2, "Scan the QR code it shows, or pick a picture of it (add --qr-png qr.png to get a file you can send)")
        Spacer(Modifier.size(20.dp))
        Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            Icon(Glyphs.Qr, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scan pairing code", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(10.dp))
        OutlinedButton(onClick = { error = null; onPickImage() }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
            Icon(Glyphs.Image, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Pair from an image", fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(busy) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TypingDots(Gold); Spacer(Modifier.width(10.dp)); Text("Connecting to your PC", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(error != null) {
            Surface(color = Bad.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(error ?: "", color = Bad, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.size(12.dp))
        TextButton(onClick = { manual = !manual }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (manual) "Hide manual entry" else "Enter address and token manually")
        }
        AnimatedVisibility(manual, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Bridge URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { token = it }, label = { Text("Token (hrb_…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp))
                OutlinedButton(onClick = { attempt(runCatching { PairingParser.validate(url, token) }) }, enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String, code: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = Gold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            code?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}

