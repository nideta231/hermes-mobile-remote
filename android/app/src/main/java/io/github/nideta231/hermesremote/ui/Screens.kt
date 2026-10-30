package io.github.nideta231.hermesremote.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import io.github.nideta231.hermesremote.data.KeepAlivePrefs
import io.github.nideta231.hermesremote.data.ModelCatalog
import io.github.nideta231.hermesremote.data.ModelOption
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.foundation.layout.defaultMinSize
import io.github.nideta231.hermesremote.ConnectionState
import io.github.nideta231.hermesremote.PairedPc
import io.github.nideta231.hermesremote.ProfilesState
import io.github.nideta231.hermesremote.SessionsState
import io.github.nideta231.hermesremote.data.SystemState
import io.github.nideta231.hermesremote.data.UpdateState
import io.github.nideta231.hermesremote.data.HostFeature
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.PairingParser
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.SessionGrouping
import io.github.nideta231.hermesremote.data.SessionOrdering
import io.github.nideta231.hermesremote.data.SessionStatus
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.SessionView
import io.github.nideta231.hermesremote.data.SESSION_FIRST_PAGE_CHOICES
import io.github.nideta231.hermesremote.data.arrangeSessions
import io.github.nideta231.hermesremote.data.GroupOrder
import io.github.nideta231.hermesremote.data.pageGroups
import io.github.nideta231.hermesremote.data.SessionSearchState
import io.github.nideta231.hermesremote.data.serverOnlyHits
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ================================================================ Sessions

/** The drawer's less used pages (PC, Terminal, Graph) behind a three-dot menu, so the top bar stays clear. */
@Composable
private fun DrawerMoreMenu(actions: SessionActions) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val upd = actions.bridgeUpdate
    val offer = upd != null && upd.state.available && upd.state.canUpdate && !upd.state.updating
    if (confirm && upd != null) BridgeUpdateConfirm(upd, onDismiss = { confirm = false })
    Box {
        IconButton(modifier = Modifier.size(40.dp), onClick = { open = true }) {
            Box {
                Icon(Glyphs.More, "More", modifier = Modifier.size(20.dp))
                if (offer) Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(Gold, CircleShape))
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) =
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, onClick = { open = false; onClick() })
            if (offer) item(if (upd!!.state.restartNeeded) "Restart PC bridge to load ${upd.state.latest}" else "Update PC bridge to ${upd.state.latest}", Glyphs.Download) { confirm = true }
            if (upd != null && upd.state.updating) item(upd.state.message ?: "Updating PC bridge…", Glyphs.Refresh) {}
            actions.openNotes?.let { item("Notes", Glyphs.Doc, it) }
            actions.openBridge?.let { item("PC bridge", Glyphs.Shield, it) }
            actions.openPc?.let { item("PC monitor", Glyphs.Chip, it) }
            actions.openTerminal?.let { item("Terminal", Glyphs.Terminal, it) }
            item("Graph", Glyphs.Graph, actions.openGraph)
        }
    }
}

class BlueprintDrawerActions(
    val have: Set<String>,
    /** Projects whose board has a question or review waiting for the user. */
    val needsYou: Set<String> = emptySet(),
    val trash: List<io.github.nideta231.hermesremote.data.BlueprintTrash>,
    val open: (ProjectRef) -> Unit,
    val add: (ProjectRef) -> Unit,
    val remove: (String) -> Unit,
    val restore: (String) -> Unit,
    val purge: (String) -> Unit,
)

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
    val linkPcs: (String, String) -> Unit = { _, _ -> },
    val unlinkPc: (String) -> Unit = {},
    val setView: (SessionView) -> Unit = {},
    val switchProfile: (String) -> Unit = {},
    val openScheduled: () -> Unit = {},
    val openVault: () -> Unit = {},
    /** The Obsidian-style map of chats, projects and topics. */
    val openGraph: () -> Unit = {},
    /** Keep-style notes Hermes can read and edit; null when the bridge has none. */
    val openNotes: (() -> Unit)? = null,
    /** Blueprints (null = the bridge has none): which projects have one, open, add, remove, trash. */
    val blueprints: BlueprintDrawerActions? = null,
    /** PC bridge admin page (doctor, restart, networks, devices). */
    val openBridge: (() -> Unit)? = null,
    /** The PC bridge's self-update, offered in the menu when a newer release is out. */
    val bridgeUpdate: BridgeUpdateActions? = null,
    /** The PC page (stats, processes, Claude usage) and the terminal; null hides the button
     *  (the bridge doesn't serve it). */
    val openPc: (() -> Unit)? = null,
    val openTerminal: (() -> Unit)? = null,
    val repairPc: () -> Unit = {},
    /** Move a group: (group keys as drawn, key, delta; Int.MIN_VALUE/MAX_VALUE = top/bottom). */
    val moveGroup: (List<String>, String, Int) -> Unit = { _, _, _ -> },
    val resetGroupOrder: () -> Unit = {},
    /** New chat preselected to a project id (null = no project, Home). */
    val newChatIn: (String?) -> Unit = {},
    /** Rename / re-folder / describe / delete projects, from a project group's long-press. */
    val projects: ProjectActions = ProjectActions(),
    /** Move a stored chat into a project (session id, project id). */
    val moveToProject: (String, String) -> Unit = { _, _ -> },
    /** The search box changed: ask the PC for chats whose messages match (debounced there). */
    val search: (String) -> Unit = {},
    /** Open a chat found by search in the profile it was found in (session id, profile). */
    val openFound: (String, String?) -> Unit = { id, _ -> open(id) },
)

/** The session list. On phones it lives in the navigation drawer; on tablets beside the chat. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsPane(
    state: SessionsState,
    currentId: String?,
    actions: SessionActions,
    modifier: Modifier = Modifier,
    pcs: List<PairedPc> = emptyList(),
    profiles: ProfilesState = ProfilesState(),
    view: SessionView = SessionView(),
    update: UpdateState? = null,
    onOpenUpdate: () -> Unit = {},
    groupOrder: List<String> = emptyList(),
    /** Active (not paused) scheduled jobs; shown as a count on the Scheduled icon. */
    scheduledCount: Int = 0,
    search: SessionSearchState = SessionSearchState(),
) {
    var query by remember { mutableStateOf("") }
    var filterSheet by remember { mutableStateOf(false) }
    var pcSheet by remember { mutableStateOf(false) }
    var profileSheet by remember { mutableStateOf(false) }
    val activePc = pcs.firstOrNull { it.active }
    // Ask again when the PC or profile changes too: switching forgets the old hits.
    LaunchedEffect(query, activePc?.id, profiles.active) { actions.search(query) }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    var moving by remember { mutableStateOf<SessionSummary?>(null) }
    // Collapsed groups as "GROUPING/key", so collapsing "Today" never hides a project group of the same name.
    // Persisted (ThemePrefs "ui" prefs) so reopening the app keeps folders the way they were left.
    val collapsedKeys = ThemePrefs.collapsedGroups
    fun collapseKey(groupKey: String) = "${view.grouping.name}/$groupKey"

    val grouped = remember(state.items, state.live, state.projects, view, query, groupOrder) {
        arrangeSessions(state.items, view, state.live, state.projects, query, customOrder = groupOrder)
    }
    // Long-pressed group header: its move menu.
    var movingGroup by remember { mutableStateOf<String?>(null) }
    var editingProject by remember { mutableStateOf<ProjectRef?>(null) }
    var blueprintTrash by remember { mutableStateOf(false) }
    // "Show more" per group: group key -> rows shown. Search, filters and paging are applied in that
    // order (see pageGroups), and a new query, view, PC or profile starts every group back at one page.
    var shown by remember(query, view, pcs.firstOrNull { it.active }?.id, profiles.active) { mutableStateOf(mapOf<String, Int>()) }
    val perGroup = ThemePrefs.sessionsPerGroup
    val pages = remember(grouped, shown, currentId, perGroup) { pageGroups(grouped, shown, currentId, perGroup) }
    // Chats only Hermes' message search found; shown while the box still holds the query they answer.
    val found = remember(grouped, search, query) {
        if (search.query != query.trim()) emptyList()
        else serverOnlyHits(search.hits, grouped.flatMapTo(HashSet()) { g -> g.items.map { it.id } })
    }

    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        // Account switcher in the Instagram style: the header is the active PC (avatar + name + chevron).
        // Tap opens the switcher; long-press jumps straight to the next PC.
        // Compact header: PC switcher plus Scheduled and Settings as icons, so the chat list gets the height.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = { pcSheet = true },
                    onLongClick = {
                        val next = nextPc(pcs)
                        if (next != null) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actions.switchPc(next.id) }
                        else pcSheet = true
                    },
                ).padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (activePc != null) PcAvatar(activePc.name, 28.dp, ring = pcs.size > 1)
                else Box(Modifier.size(28.dp).background(Gold.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(activePc?.name ?: "Hermes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (pcs.size > 1) "Hermes · ${pcs.size} PCs" else "Hermes", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Glyphs.Chevron, "Switch PC", modifier = Modifier.padding(start = 4.dp).size(18.dp))
            }
            IconButton(modifier = Modifier.size(40.dp), onClick = actions.openScheduled) {
                Box {
                    Icon(Glyphs.Clock, "Scheduled", modifier = Modifier.size(20.dp))
                    if (scheduledCount > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-6).dp)
                        .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp).background(Gold, CircleShape).padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (scheduledCount > 99) "99+" else "$scheduledCount", color = MaterialTheme.colorScheme.surface,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp, maxLines = 1)
                    }
                }
            }
            IconButton(modifier = Modifier.size(40.dp), onClick = actions.openSettings) { Icon(Glyphs.Settings, "Settings", modifier = Modifier.size(20.dp)) }
            DrawerMoreMenu(actions)
        }
        if (pcSheet) PcSheet(pcs, actions, onDismiss = { pcSheet = false })
        // Only when the PC has more than one profile: with just the default there is nothing to pick.
        if (profiles.items.size > 1) ProfileRow(profiles, onClick = { profileSheet = true })
        if (profileSheet) ProfileSheet(profiles, onPick = { profileSheet = false; actions.switchProfile(it) },
            onDismiss = { profileSheet = false })
        update?.let { DrawerUpdateRow(it, onOpenUpdate) }
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchField(query, { query = it }, "Search chats", Modifier.weight(1f))
            // Group / sort / filter, like the desktop sidebar's filter menu. A gold dot marks a non-default view.
            Box {
                IconButton(onClick = { filterSheet = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Glyphs.Filter, "Group, sort and filter", modifier = Modifier.size(20.dp),
                        tint = if (view.customized) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (view.filtersActive) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(7.dp).background(Gold, CircleShape))
            }
            // New chat, as a compact gold round button instead of a full-width bar.
            FilledIconButton(onClick = actions.newChat, modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gold, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(Glyphs.Compose, "New chat", modifier = Modifier.size(18.dp))
            }
        }
        if (filterSheet) SessionViewSheet(view, state.projects, actions.setView, actions.resetGroupOrder, onDismiss = { filterSheet = false })
        if (blueprintTrash) actions.blueprints?.let { BlueprintTrashSheet(it, onDismiss = { blueprintTrash = false }) }
        editingProject?.let { p -> EditProjectDialog(p.id, p.label, actions.projects, onDismiss = { editingProject = null }) }
        movingGroup?.let { key ->
            val keys = grouped.map { it.key }.filter { it != PINNED_KEY }
            val label = grouped.firstOrNull { it.key == key }?.label ?: key
            // A saved project (not Home, not an auto repo) can be edited or deleted, like desktop's project menu.
            val saved = if (view.grouping == SessionGrouping.PROJECT && key.startsWith("p-"))
                state.projects.firstOrNull { it.id == key.removePrefix("p-") && !it.auto && it.id != SessionView.NO_PROJECT } else null
            GroupMoveSheet(label, keys.indexOf(key), keys.size, view.groupOrder == GroupOrder.CUSTOM,
                onMove = { d -> actions.moveGroup(keys, key, d) },
                onReset = { actions.resetGroupOrder(); movingGroup = null },
                onDismiss = { movingGroup = null },
                // Project folders get "New chat here": the new chat starts with that project selected.
                onNewChat = if (view.grouping == SessionGrouping.PROJECT && key.startsWith("p-")) ({
                    movingGroup = null
                    actions.newChatIn(key.removePrefix("p-").takeIf { it != SessionView.NO_PROJECT })
                }) else null,
                onEditProject = saved?.let { p -> { movingGroup = null; editingProject = p } },
                blueprint = actions.blueprints?.let { bpa ->
                    val pid = key.removePrefix("p-")
                    val ref = state.projects.firstOrNull { it.id == pid && it.id != SessionView.NO_PROJECT }
                    if (view.grouping == SessionGrouping.PROJECT && key.startsWith("p-") && ref != null) BlueprintSheetActions(
                        has = pid in bpa.have, trashCount = bpa.trash.size,
                        open = { movingGroup = null; bpa.open(ref) }, add = { movingGroup = null; bpa.add(ref) },
                        remove = { movingGroup = null; bpa.remove(pid) }, openTrash = { movingGroup = null; blueprintTrash = true }) else null
                })
        }

        PullToRefreshBox(isRefreshing = state.loading && state.items.isNotEmpty(), onRefresh = actions.refresh,
            modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                state.error?.let { err ->
                    item(key = "err") {
                        Text(err, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
                pages.forEach { page ->
                    val group = page.group
                    // A searched group is always open, so a match is never hidden behind a collapsed header.
                    val ck = collapseKey(group.key)
                    val closed = query.isBlank() && ck in collapsedKeys
                    item(key = "g-${group.key}") {
                        GroupHeader(group.label, group.items.size, closed, Modifier.animateItem(),
                            project = view.grouping == SessionGrouping.PROJECT && group.key != PINNED_KEY,
                            live = page.group.items.count { state.live[it.id] != null },
                            onBlueprint = actions.blueprints?.let { bpa ->
                                val pid = group.key.removePrefix("p-")
                                val ref = state.projects.firstOrNull { it.id == pid }
                                if (view.grouping == SessionGrouping.PROJECT && group.key.startsWith("p-") && pid in bpa.have && ref != null) ({ bpa.open(ref) }) else null
                            },
                            blueprintAlert = actions.blueprints?.needsYou?.contains(group.key.removePrefix("p-")) == true,
                            onLongClick = if (group.key == PINNED_KEY) null else ({
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress); movingGroup = group.key
                            })) {
                            ThemePrefs.toggleCollapsed(ck)
                        }
                    }
                    if (!closed) {
                        items(page.items, key = { it.id }) { s ->
                            SessionRow(s, current = s.id == currentId, live = state.live[s.id],
                                modifier = Modifier.animateItem(), nested = view.grouping == SessionGrouping.PROJECT,
                                onClick = { actions.open(s.id) }, onLongClick = { menuFor = s })
                        }
                        if (page.remaining > 0 || page.canShowLess) item(key = "more-${group.key}") {
                            // Each tap adds the same number as "Chats per group" in Settings.
                            ShowMoreRow(page.remaining, perGroup, page.canShowLess, Modifier.animateItem(),
                                onLess = { shown = shown - group.key }) {
                                shown = shown + (group.key to page.items.size + perGroup)
                            }
                        }
                    }
                }
                if (found.isNotEmpty()) {
                    item(key = "found-header") { GroupHeader("In messages", found.size, false, Modifier.animateItem()) {} }
                    items(found, key = { "found-" + it.session.id }) { hit ->
                        SessionRow(hit.session, current = hit.session.id == currentId, live = state.live[hit.session.id],
                            modifier = Modifier.animateItem(), snippet = hit.snippet,
                            onClick = { actions.openFound(hit.session.id, search.target?.profile) },
                            onLongClick = { menuFor = hit.session })
                    }
                }
                if (search.loading && search.query == query.trim()) item(key = "found-loading") {
                    Text("Searching messages…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                item {
                    when {
                        state.hasMore && query.isBlank() -> {
                            LaunchedEffect(state.items.size) { actions.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { TypingDots() }
                        }
                        state.loading && state.items.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                        grouped.isEmpty() && view.filtersActive -> Column(Modifier.padding(20.dp)) {
                            Text("No chats match the current filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { actions.setView(view.copy(statuses = emptySet(), projects = emptySet())) },
                                contentPadding = PaddingValues(0.dp)) { Text("Clear filters", color = Gold) }
                        }
                        grouped.isEmpty() && found.isEmpty() && !search.loading -> Text(if (query.isBlank()) "No chats yet." else "Nothing matches “$query”.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
    }

    menuFor?.let { s ->
        SessionActionsSheet(s, onDismiss = { menuFor = null },
            onPin = { actions.setPinned(s.id, !s.pinned); menuFor = null },
            onRename = { renaming = s; menuFor = null },
            onMove = if (state.projects.any { it.path != null }) ({ moving = s; menuFor = null }) else null,
            onDelete = { deleting = s; menuFor = null })
    }
    moving?.let { s ->
        MoveToProjectSheet(s, state.projects, onDismiss = { moving = null },
            onPick = { pid -> actions.moveToProject(s.id, pid); moving = null })
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
    var linking by remember { mutableStateOf<PairedPc?>(null) }
    var forgetting by remember { mutableStateOf<PairedPc?>(null) }
    val haptics = LocalHapticFeedback.current

    // Instagram/Facebook account switcher: centred title, one row per account with a big avatar and a
    // radio mark on the one in use, then "Add PC". Options (rename, forget) sit behind each row's ⋮.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Switch PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
            Text("Each PC keeps its own chats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            pcs.forEach { pc ->
                Row(Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { onDismiss(); if (!pc.active) actions.switchPc(pc.id) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = pc },
                    ).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PcAvatar(pc.name, 52.dp, ring = pc.active)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pc.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (pc.active) "In use · ${hostOf(pc.url)}" else hostOf(pc.url), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (linkMenu(pcs, pc).tag) Text("Gateway + Bridge", style = MaterialTheme.typography.labelSmall, color = Gold)
                    }
                    RadioMark(pc.active)
                    IconButton(onClick = { menuFor = pc }) { Icon(Glyphs.More, "Options for ${pc.name}", modifier = Modifier.size(18.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { onDismiss(); actions.addPc() }
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Plus, null, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Add PC", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Scan the pair QR from another PC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    menuFor?.let { pc ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PcAvatar(pc.name, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(pc.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pc.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.size(8.dp))
                if (!pc.active) PanelRow("Switch to ${pc.name}", icon = Glyphs.Check, iconTint = Gold,
                    onClick = { menuFor = null; onDismiss(); actions.switchPc(pc.id) })
                if (pc.active && !pc.gateway) PanelRow("Re-pair", "Scan a fresh code; chats and drafts stay", icon = Glyphs.Refresh,
                    onClick = { menuFor = null; onDismiss(); actions.repairPc() })
                val lm = linkMenu(pcs, pc)
                if (lm.unlink) PanelRow("Unlink", "No longer the same PC as its gateway/bridge entry", icon = Glyphs.Refresh,
                    onClick = { menuFor = null; actions.unlinkPc(pc.id) })
                else if (lm.linkWith) PanelRow("Link with...", icon = Glyphs.Plus, onClick = { menuFor = null; linking = pc })
                PanelRow("Rename", icon = Glyphs.Edit, onClick = { menuFor = null; renaming = pc })
                PanelRow("Forget this PC", icon = Glyphs.Trash, iconTint = Bad, titleColor = Bad, onClick = { menuFor = null; forgetting = pc })
            }
        }
    }
    linking?.let { pc ->
        // Only hosts of the other kind: a bridge links with a gateway host and the other way round.
        AlertDialog(onDismissRequest = { linking = null }, title = { Text("Link ${pc.name} with...") },
            text = {
                Column {
                    linkCandidates(pcs, pc).forEach { other ->
                        TextButton(onClick = { linking = null; actions.linkPcs(pc.id, other.id) }) { Text(other.name) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } })
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

/** The PC after the active one, wrapping; null with fewer than two PCs. */
private fun nextPc(pcs: List<PairedPc>): PairedPc? {
    if (pcs.size < 2) return null
    val i = pcs.indexOfFirst { it.active }
    return pcs[(i + 1).mod(pcs.size)]
}

/** The Hermes profile in use, under the PC header: tap to pick another. */
@Composable
private fun ProfileRow(profiles: ProfilesState, onClick: () -> Unit) {
    val current = profiles.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.Person, null, tint = Gold, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Profile: ${current?.label ?: profiles.active}", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            current?.model?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Glyphs.Chevron, "Switch profile", modifier = Modifier.size(16.dp))
    }
}

/** Every Hermes profile on the PC: tap one to see its chats and start new chats in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(profiles: ProfilesState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Switch profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
            Text("Each profile has its own chats, model and skills", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            profiles.items.forEach { p ->
                val active = p.name == profiles.active
                Row(Modifier.fillMaxWidth().clickable { if (active) onDismiss() else onPick(p.name) }
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PcAvatar(p.label, 40.dp, ring = active)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label + if (p.isDefault) " (default)" else "", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(p.model, p.description).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    RadioMark(active)
                }
            }
        }
    }
}

private fun hostOf(url: String) = Uri.parse(url).host ?: url

private val avatarColors = listOf(Color(0xFFE8B54A), Color(0xFF5B9BE8), Color(0xFF5BC27A), Color(0xFFC77DDB), Color(0xFFE5675C), Color(0xFF4FC1C6))

/** Letter avatar, colour stable per name. [ring]: the gold story-style ring that marks the PC in use. */
@Composable
private fun PcAvatar(name: String, size: androidx.compose.ui.unit.Dp, ring: Boolean = false) {
    val color = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
    val letters = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }
    Box(Modifier.size(size).then(if (ring) Modifier.border(2.dp, Gold, CircleShape).padding(3.dp) else Modifier)
        .background(color.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
        Text(letters, color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
    }
}

/** Filled radio dot for the account in use, empty ring for the others. */
@Composable
private fun RadioMark(selected: Boolean) {
    Box(Modifier.size(22.dp).border(2.dp, if (selected) Gold else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(12.dp).background(Gold, CircleShape))
    }
}

/** "Show N more" under a long group (each tap adds [step] rows, the Chats per group setting) and "Show less" back to the first page. */
@Composable
private fun ShowMoreRow(remaining: Int, step: Int, canShowLess: Boolean, modifier: Modifier, onLess: () -> Unit, onMore: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (remaining > 0) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onMore)
                .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Chevron, null, tint = Gold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show ${minOf(remaining, step)} more", style = MaterialTheme.typography.labelLarge, color = Gold,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("$remaining left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.weight(1f))
        if (canShowLess) {
            Text("Show less", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onLess)
                    .padding(horizontal = 10.dp, vertical = 10.dp))
        }
    }
}

/**
 * Tappable group header: label, count and a chevron that turns when the group is collapsed.
 * Project groups get a folder card in sentence case so each project stands out from its chats;
 * date/status groups stay as quiet uppercase labels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(label: String, count: Int, collapsed: Boolean, modifier: Modifier,
                        project: Boolean = false, live: Int = 0, onLongClick: (() -> Unit)? = null,
                        /** The project's blueprint: a small button on the header (null = none). */
                        onBlueprint: (() -> Unit)? = null, blueprintAlert: Boolean = false, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (collapsed) -90f else 0f, label = "chevron")
    if (project) {
        Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Folder, null, tint = Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 7.dp, vertical = 1.dp))
            if (live > 0) { Spacer(Modifier.width(6.dp)); StatusDot(Gold, pulse = true) }
            Spacer(Modifier.weight(1f))
            if (onBlueprint != null) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBlueprint), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Blueprint, if (blueprintAlert) "Blueprint of $label, waiting for you" else "Blueprint of $label", tint = Gold, modifier = Modifier.size(17.dp))
                    if (blueprintAlert) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(8.dp).background(Warn, CircleShape))
                }
                Spacer(Modifier.width(4.dp))
            }
            Icon(Glyphs.Chevron, if (collapsed) "Expand $label" else "Collapse $label", modifier = Modifier.size(18.dp).rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onToggle, onLongClick = onLongClick)
        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Label and count share the weighted slot, so every chevron lines up on the right edge.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Icon(Glyphs.Chevron, if (collapsed) "Expand $label" else "Collapse $label", modifier = Modifier.size(16.dp).rotate(angle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, current: Boolean, live: String?, modifier: Modifier, nested: Boolean = false,
                       /** For a search hit: the matching text from inside the chat. */
                       snippet: String? = null,
                       onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (current) Gold.copy(alpha = 0.13f) else Color.Transparent, label = "row")
    // Same cue as the desktop sidebar: a working session shimmers, one waiting for you pulses amber.
    // Chats inside a project sit indented on a guide line, so it's clear which project they belong to.
    val guide = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.fillMaxWidth()
        .then(if (nested) Modifier.padding(start = 14.dp).drawBehind {
            drawLine(guide, Offset(-6.dp.toPx(), 0f), Offset(-6.dp.toPx(), size.height), strokeWidth = 1.5.dp.toPx())
        } else Modifier)
        .clip(RoundedCornerShape(14.dp)).background(bg).shimmer(live != null, if (live == "waiting") Warn else Gold)
        .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            snippet?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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
private fun SessionActionsSheet(s: SessionSummary, onDismiss: () -> Unit, onPin: () -> Unit, onRename: () -> Unit,
                                onMove: (() -> Unit)?, onDelete: () -> Unit) {
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
            if (onMove != null) PanelRow("Move to project", icon = Glyphs.Folder, iconTint = Gold, onClick = onMove)
            PanelRow("Delete", icon = Glyphs.Trash, iconTint = Bad, titleColor = Bad, onClick = onDelete)
        }
    }
}

/** Pick the project a chat moves into; only projects with a folder can take it (the move re-homes its cwd). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToProjectSheet(s: SessionSummary, projects: List<ProjectRef>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Move \"${s.displayTitle}\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.size(8.dp))
            projects.filter { it.path != null && it.id != SessionView.NO_PROJECT }.forEach { p ->
                val here = s.id in p.sessionIds
                PanelRow(p.label, icon = if (here) Glyphs.Check else Glyphs.Folder, iconTint = Gold,
                    onClick = { if (here) onDismiss() else onPick(p.id) })
            }
        }
    }
}

/** Group by / sort by / status / project, mirroring the desktop sidebar's filter menu. Changes apply live. */
/** Selected chips in the app's accent (Gold follows the chosen theme), not Material's default secondary container. */
@Composable
internal fun themedChip() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Gold.copy(alpha = 0.18f), selectedLabelColor = Gold, selectedLeadingIconColor = Gold)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionViewSheet(view: SessionView, projects: List<ProjectRef>, onChange: (SessionView) -> Unit,
                             onResetGroupOrder: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("View", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = view.customized, onClick = { onChange(SessionView()) }) { Text("Reset") }
            }
            SectionLabel("Group by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionGrouping.entries.forEach { g ->
                    FilterChip(colors = themedChip(), selected = view.grouping == g, onClick = { onChange(view.copy(grouping = g)) }, label = { Text(g.label) })
                }
            }
            SectionLabel("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionOrdering.entries.forEach { o ->
                    FilterChip(colors = themedChip(), selected = view.ordering == o, onClick = { onChange(view.copy(ordering = o)) }, label = { Text(o.label) })
                }
            }
            SectionLabel("Group order")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupOrder.entries.forEach { o ->
                    FilterChip(colors = themedChip(), selected = view.groupOrder == o, label = { Text(o.label) }, onClick = { onChange(view.copy(groupOrder = o)) })
                }
                if (view.groupOrder == GroupOrder.CUSTOM) TextButton(onClick = onResetGroupOrder) { Text("Clear custom order") }
            }
            Text("Long-press a group header to move it. The custom order is saved on this phone, per grouping.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionStatus.entries.forEach { st ->
                    val on = st in view.statuses
                    FilterChip(colors = themedChip(), selected = on, label = { Text(st.label) },
                        onClick = { onChange(view.copy(statuses = if (on) view.statuses - st else view.statuses + st)) })
                }
            }
            SectionLabel("Project")
            if (projects.isEmpty()) {
                Text("No projects on this PC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // The backend lists its "Home" bucket (chats outside every project) among the projects.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { p ->
                        val on = p.id in view.projects
                        FilterChip(colors = themedChip(), selected = on, label = { Text(if (p.id == SessionView.NO_PROJECT) SessionView.NO_PROJECT_LABEL else p.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { onChange(view.copy(projects = if (on) view.projects - p.id else view.projects + p.id)) })
                    }
                }
            }
            Text("Empty filters show everything. Pinned chats stay on top.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private const val PINNED_KEY = "Pinned"

class BlueprintSheetActions(val has: Boolean, val trashCount: Int, val open: () -> Unit, val add: () -> Unit, val remove: () -> Unit, val openTrash: () -> Unit)

/** Removed blueprints: restore or delete for good. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlueprintTrashSheet(a: BlueprintDrawerActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Blueprint trash", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (a.trash.isEmpty()) Text("Empty.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            a.trash.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Removed ${relative(t.removedAt)} · ${t.cards} cards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { a.restore(t.id) }) { Text("Restore") }
                    TextButton(onClick = { a.purge(t.id) }) { Text("Delete", color = Bad) }
                }
            }
        }
    }
}

/** Move one drawer group up/down/top/bottom. Stays open so a group can be moved several places in a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMoveSheet(label: String, index: Int, count: Int, custom: Boolean,
                           onMove: (Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit,
                           onNewChat: (() -> Unit)? = null, onEditProject: (() -> Unit)? = null,
                           blueprint: BlueprintSheetActions? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Position ${index + 1} of $count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            if (onNewChat != null) {
                Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Glyphs.Compose, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New chat in $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(4.dp))
            }
            if (onEditProject != null) {
                OutlinedButton(onClick = onEditProject, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Glyphs.Edit, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit project", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Rename, change folder or description, delete", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
            }
            blueprint?.let { b ->
                var confirm by remember { mutableStateOf(false) }
                if (b.has) {
                    OutlinedButton(onClick = b.open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Glyphs.Blueprint, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open blueprint")
                    }
                    TextButton(onClick = { confirm = true }) { Text("Remove blueprint", color = Bad) }
                } else OutlinedButton(onClick = b.add, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Glyphs.Blueprint, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add blueprint (diagram + board)")
                }
                if (b.trashCount > 0) TextButton(onClick = b.openTrash) { Text("Blueprint trash (${b.trashCount})") }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Remove the blueprint of $label?") },
                    text = { Text("It goes to the blueprint trash and can be restored from there.") },
                    confirmButton = { TextButton(onClick = { confirm = false; b.remove() }) { Text("Remove", color = Bad) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
            }
            val top = index <= 0
            val bottom = index >= count - 1
            TextButton(enabled = !top, onClick = { onMove(Int.MIN_VALUE) }) { Text("Move to top") }
            TextButton(enabled = !top, onClick = { onMove(-1) }) { Text("Move up") }
            TextButton(enabled = !bottom, onClick = { onMove(1) }) { Text("Move down") }
            TextButton(enabled = !bottom, onClick = { onMove(Int.MAX_VALUE) }) { Text("Move to bottom") }
            if (custom) TextButton(onClick = onReset) { Text("Clear custom order (follow sort)") }
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

class BridgeUpdateActions(
    val state: io.github.nideta231.hermesremote.data.BridgeUpdateState,
    val check: (Boolean) -> Unit,
    val start: () -> Unit,
    /** Set when held chats still have work (`ParkedSockets.confirmText` for an update): the confirm then asks that instead. */
    val held: String? = null,
    /** Reloads the held list; the confirm calls it when it opens so the question is current. */
    val refreshHeld: () -> Unit = {},
)

/** Settings > Bridge: its release, and Update when a newer one is out. Pairings stay through the restart. */
@Composable
private fun BridgeUpdatePanel(a: BridgeUpdateActions) {
    LaunchedEffect(Unit) { a.check(false) }
    var confirm by remember { mutableStateOf(false) }
    val s = a.state
    Spacer(Modifier.size(10.dp))
    Panel {
        val sub = when {
            s.updating -> s.message ?: "Updating…"
            s.restartNeeded && s.canUpdate -> "${s.latest} is installed but not running yet: restart to load it"
            s.available && s.canUpdate -> "${s.latest} is available (you have ${s.current ?: "?"})"
            s.available -> "${s.latest} is available. ${s.reason.orEmpty()}"
            s.current != null -> "Up to date" + (s.message?.let { " · $it" } ?: "")
            s.reason != null -> s.reason
            else -> "Checking…"
        }
        PanelRow("Bridge ${s.current ?: ""}".trim(), sub, Glyphs.Download,
            iconTint = if (s.failed) Bad else if (s.available) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { if (s.available && s.canUpdate && !s.updating) confirm = true else a.check(true) }) {
            when {
                s.updating || s.checking && s.current == null -> TypingDots()
                s.available && s.canUpdate -> TextButton(onClick = { confirm = true }) { Text("Update") }
            }
        }
    }
    if (confirm) BridgeUpdateConfirm(a, onDismiss = { confirm = false })
}

/** Shared yes/no for the bridge update (Settings row and the drawer menu). */
@Composable
internal fun BridgeUpdateConfirm(a: BridgeUpdateActions, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { a.refreshHeld() }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Update the PC bridge to ${a.state.latest}?") },
        text = { Text(a.held ?: ("The PC downloads it and restarts the bridge (about a minute; the connection drops briefly and comes " +
            "back on its own). If the new version doesn't start, the PC rolls back to ${a.state.current ?: "the current one"} " +
            "and restarts it. Pairings, Hermes and running chats are kept either way.")) },
        confirmButton = { TextButton(onClick = { onDismiss(); a.start() }) { Text(if (a.held != null) "Update anyway" else "Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

class SettingsActions(
    val refresh: () -> Unit,
    val unpair: () -> Unit,
    val setApprovalMode: (String) -> Unit,
    val useTransport: (Transport) -> Unit,
    val useAuto: () -> Unit,
    val checkUpdate: () -> Unit,
    val installUpdate: () -> Unit,
    val openUpdate: () -> Unit = {},
    val openChangelog: () -> Unit = {},
    val loadModels: () -> Unit = {},
    val setDefaultModel: (ModelOption, Boolean) -> Unit = { _, _ -> },
    val dismissDefaultConfirm: () -> Unit = {},
    val openVault: () -> Unit = {},
    val repair: () -> Unit = {},
    /** The PC bridge's self-update; null when the bridge can't do it (old, or turned off). */
    val openBridge: (() -> Unit)? = null,
    val bridgeUpdate: BridgeUpdateActions? = null,
    /** Global blueprint board settings on this PC; null when the bridge has none (older, or blueprints off). */
    val boardDefaults: io.github.nideta231.hermesremote.data.BpSettings? = null,
    val setBoardDefaults: (io.github.nideta231.hermesremote.data.BpSettings) -> Unit = {},
    /** Global agent instructions on this PC; null when the bridge has none (older, or blueprints off). */
    val boardInstructions: String? = null,
    val setBoardInstructions: (String) -> Unit = {},
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
    models: ModelCatalog? = null,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val hidden = LocalHiddenFeatures.current
    LaunchedEffect(Unit) { actions.refresh() }
    PageScaffold("Settings", onBack) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)) {
                UpdateBanner(update, actions.openUpdate)
                // The bridge's status card, transport choice, approvals and default model are bridge routes.
                val bridgeCard = HostFeature.BRIDGE_STATUS !in hidden
                if (bridgeCard) SettingsSection("Connection", "How this phone reaches your PC.") {
                    ConnectionPanel(conn, state.error, actions)
                }
                SettingsSection("Hermes on your PC", "Shared with the desktop app and messaging.") {
                    StatusPanel(state)
                    if (bridgeCard) actions.bridgeUpdate?.let { BridgeUpdatePanel(it) }
                    if (bridgeCard) actions.openBridge?.let { open ->
                        Spacer(Modifier.size(10.dp))
                        Panel { PanelRow("PC bridge", "Health check and fixes, restart, trusted networks, paired devices, log", Glyphs.Shield, onClick = open) }
                    }
                    if (bridgeCard) {
                        Spacer(Modifier.size(10.dp))
                        DefaultModelPanel(state, models) { actions.loadModels(); modelSheet = true }
                        Spacer(Modifier.size(10.dp))
                        ApprovalPanel(state, actions.setApprovalMode)
                    }
                    // Vault is Hermes' own vault.* RPC over the live socket, so a gateway host has it too.
                    Spacer(Modifier.size(10.dp))
                    Panel {
                        PanelRow("Vault", "Saved logins, cards and addresses Hermes can fill", Glyphs.Shield, onClick = actions.openVault)
                    }
                }
                SettingsSection("Appearance", "Only changes this phone.") {
                    ThemePanel()
                    Spacer(Modifier.size(10.dp))
                    LayoutPanel()
                }
                SettingsSection("Background", "What happens when you close or swipe away the app.") {
                    KeepAlivePanel()
                }
                SettingsSection("Chat list", "How the drawer lists your chats.") {
                    DrawerPanel()
                }
                actions.boardDefaults?.let { d ->
                    SettingsSection("Blueprint boards", "Every project's board on this PC, unless that board chose otherwise (board ⋮ > Board settings). Hermes follows them too.") {
                        Panel {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                BoardSettingsFields(d, null, actions.setBoardDefaults)
                            }
                        }
                        actions.boardInstructions?.let { g ->
                            Spacer(Modifier.size(10.dp))
                            GlobalInstructionsPanel(g, actions.setBoardInstructions)
                        }
                    }
                }
                SettingsSection("About") {
                    Panel {
                        PanelRow("App version", update.installed.ifBlank { "?" }, Glyphs.Download, onClick = actions.checkUpdate) {
                            when {
                                update.checking -> TypingDots()
                                update.available != null -> Pill("Update", tint = MaterialTheme.colorScheme.onPrimary, container = Gold, onClick = actions.openUpdate)
                                update.checkedAt != null -> Text("Up to date", style = MaterialTheme.typography.labelMedium, color = Ok)
                                else -> Text("Check", style = MaterialTheme.typography.labelMedium, color = Gold)
                            }
                        }
                        update.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Bad, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        PanelDivider()
                        PanelRow("Changelog", "What changed in every version", Glyphs.Doc, onClick = actions.openChangelog)
                    }
                }
                SettingsSection("This device") {
                    Panel {
                        pairing?.let {
                            PanelRow("Paired as “${it.device}”", it.url, Glyphs.Qr)
                            PanelDivider()
                            PanelRow("Re-pair this PC", "Pairing broken or revoked? Scan a fresh code; chats and drafts stay",
                                Glyphs.Refresh, iconTint = Gold, onClick = actions.repair)
                            PanelDivider()
                        }
                        PanelRow("Unpair this device", "Signs this phone out of your PC", icon = Glyphs.Close, iconTint = Bad, titleColor = Bad, onClick = { confirmUnpair = true })
                    }
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
    if (modelSheet) {
        val current = models?.options?.firstOrNull { it.id == state.defaultModel && it.provider == state.defaultProvider }
        ModelSheet(models, current, newChat = true, onDismiss = { modelSheet = false },
            onPick = { o -> modelSheet = false; if (o != null) actions.setDefaultModel(o, false) },
            title = "Default model", subtitle = "Hermes uses it for every new chat: here, on the desktop and in messaging.",
            offerHermesDefault = false)
    }
    state.defaultConfirm?.let { c ->
        AlertDialog(onDismissRequest = actions.dismissDefaultConfirm, title = { Text("Make ${c.option.label} the default?") },
            text = { Text(c.message) },
            confirmButton = { TextButton(onClick = { actions.setDefaultModel(c.option, true) }) { Text("Use it") } },
            dismissButton = { TextButton(onClick = actions.dismissDefaultConfirm) { Text("Cancel") } })
    }
}

/** Settings' update card; "What's new" opens the sheet with the notes and Update now. */
@Composable
private fun UpdateBanner(u: UpdateState, onOpen: () -> Unit) {
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
                    Text("You have ${u.installed}. See what changed since then before updating.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = onOpen, modifier = Modifier.padding(top = 10.dp)) { Text("What's new · Update") }
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
            conn.transport == Transport.FUNNEL -> Triple(Glyphs.Shield, Warn, "Internet (Tailscale Funnel)")
            else -> Triple(Glyphs.Globe, MaterialTheme.colorScheme.secondary, "Tailscale")
        }
        val sub = when {
            error != null -> error
            conn.needsRepairForLan -> "Pair again from the PC to use the local network securely."
            conn.transport == Transport.TAILNET && conn.pcNetwork != null && conn.pcNetworkTrusted == false ->
                "Your PC is on “${conn.pcNetwork}”, which isn't trusted, so it only answers over Tailscale."
            conn.transport == Transport.LAN -> "Direct and encrypted, verified as your PC"
            conn.transport == Transport.TAILNET -> "Through your tailnet"
            conn.transport == Transport.FUNNEL -> "Over the public internet. Terminal, PC pages and notes ask for your fingerprint or PIN; the vault stays off."
            else -> null
        }
        PanelRow(title, listOfNotNull(sub, conn.via).joinToString(" · ").ifEmpty { null }, icon, iconTint = tint) { if (conn.searching) TypingDots(Warn) }
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
        Text("Command approvals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
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

@Composable
private fun DefaultModelPanel(state: SystemState, models: ModelCatalog?, onOpen: () -> Unit) {
    val label = models?.options?.firstOrNull { it.id == state.defaultModel && it.provider == state.defaultProvider }?.label
        ?: state.defaultModel.substringAfterLast('/').ifBlank { null }
    Panel {
        PanelRow("Default model", when {
            state.defaultSaving -> "Saving…"
            state.defaultProvider.isNotBlank() -> "${label ?: "Unknown"} · ${state.defaultProvider}"
            else -> label ?: "Unknown"
        }, Glyphs.Chip, onClick = onOpen) {
            Text("Change", style = MaterialTheme.typography.labelMedium, color = Gold)
        }
    }
}

@Composable
private fun ThemePanel() {
    val mode = ThemePrefs.mode
    val accent = ThemePrefs.accent
    val systemDark = isSystemInDarkTheme()
    var allAccents by remember { mutableStateOf(false) }
    Panel(Modifier.animateContentSize()) {
        Text("Mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                val scheme = colorScheme(m, accent, systemDark)
                ThemeSwatch(m.label, m == mode, if (m == ThemeMode.SYSTEM) colorScheme(ThemeMode.DARK, accent, true).background else scheme.background, scheme.primary, Modifier.weight(1f),
                    split = if (m == ThemeMode.SYSTEM) colorScheme(ThemeMode.LIGHT, accent, false).background else null) {
                    ThemePrefs.pickMode(m)
                }
            }
        }
        PanelDivider()
        // 30 accents: collapsed to the current one so the page stays short; tap to open the grid.
        val chevron by animateFloatAsState(if (allAccents) 90f else 0f, label = "accentChevron")
        PanelRow("Accent", accent.label, onClick = { allAccents = !allAccents }) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(accent.color(mode, systemDark)))
            Spacer(Modifier.width(10.dp))
            Icon(Glyphs.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(chevron))
        }
        // Grouped by hue family (see Accent), 5 per row.
        val bg = MaterialTheme.colorScheme.background
        if (allAccents) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Accent.entries.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a ->
                        ThemeSwatch(a.label, a == accent && ThemePrefs.customPrimary == null, bg, a.color(mode, systemDark), Modifier.weight(1f)) {
                            ThemePrefs.pickAccent(a); ThemePrefs.pickCustomPrimary(null)
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        PanelDivider()
        var editPrimary by remember { mutableStateOf(false) }
        var editBackground by remember { mutableStateOf(false) }
        val dark = mode.isDark(systemDark)
        PanelRow("Custom primary colour", ThemePrefs.customPrimary?.let { hex(it) } ?: "Off (uses the accent)", onClick = { editPrimary = !editPrimary }) {
            ThemePrefs.customPrimary?.let { Box(Modifier.size(20.dp).clip(CircleShape).background(it)) }
        }
        if (editPrimary) ColorPicker(ThemePrefs.customPrimary ?: accent.color(mode, systemDark), onPick = ThemePrefs::pickCustomPrimary,
            onReset = if (ThemePrefs.customPrimary != null) ({ ThemePrefs.pickCustomPrimary(null) }) else null)
        PanelDivider()
        PanelRow("Custom background", ThemePrefs.customBackground?.let { hex(it) } ?: "Off (uses the mode)", onClick = { editBackground = !editBackground }) {
            ThemePrefs.customBackground?.let { Box(Modifier.size(20.dp).clip(CircleShape).background(it).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) }
        }
        if (editBackground) {
            // Suggestions for anyone who'd rather not pick: built from the current primary colour.
            Text("Suggested for your colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                backgroundSuggestions(MaterialTheme.colorScheme.primary, dark).forEach { (label, c) ->
                    ThemeSwatch(label, ThemePrefs.customBackground == c, c, MaterialTheme.colorScheme.primary, Modifier.width(72.dp)) { ThemePrefs.pickCustomBackground(c) }
                }
            }
            ColorPicker(ThemePrefs.customBackground ?: MaterialTheme.colorScheme.background, onPick = ThemePrefs::pickCustomBackground,
                onReset = if (ThemePrefs.customBackground != null) ({ ThemePrefs.pickCustomBackground(null) }) else null)
        }
    }
}

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** Hue, saturation and brightness sliders plus a hex field; every change applies at once. */
@Composable
private fun ColorPicker(initial: Color, onPick: (Color) -> Unit, onReset: (() -> Unit)?) {
    val start = hsvOf(initial)
    var h by remember { mutableStateOf(start.first) }
    var sat by remember { mutableStateOf(start.second) }
    var v by remember { mutableStateOf(start.third) }
    var text by remember { mutableStateOf(hex(initial)) }
    fun push() { val c = Color.hsv(h, sat, v); text = hex(c); onPick(c) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.hsv(h, sat, v)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.OutlinedTextField(text, { t ->
                text = t
                Regex("#?([0-9a-fA-F]{6})").matchEntire(t.trim())?.let { m ->
                    val c = Color(0xFF000000 or m.groupValues[1].toLong(16)); val (hh, ss, vv) = hsvOf(c); h = hh; sat = ss; v = vv; onPick(c)
                }
            }, singleLine = true, label = { Text("Hex") }, modifier = Modifier.weight(1f))
            onReset?.let { TextButton(onClick = it) { Text("Reset") } }
        }
        Text("Hue", style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Slider(h, { h = it; push() }, valueRange = 0f..359f)
        Text("Saturation", style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Slider(sat, { sat = it; push() })
        Text("Brightness", style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Slider(v, { v = it; push() })
    }
}

/** Sessions shown per drawer group before "Show more" (Hermes desktop shows 3). */
@Composable
private fun DrawerPanel() {
    val choices = SESSION_FIRST_PAGE_CHOICES
    val current = ThemePrefs.sessionsPerGroup
    Panel {
        Text("Chats per group", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        Segmented(
            options = choices.map { if (it == 0) "All" else "$it" },
            selected = choices.indexOf(current).coerceAtLeast(0),
            enabled = choices.map { true },
            onSelect = { ThemePrefs.pickSessionsPerGroup(choices[it]) },
            modifier = Modifier.padding(16.dp),
        )
        Text(if (current == 0) "Every chat shows. No \"Show more\"."
            else "Each date or project shows $current chats; \"Show more\" adds $current at a time. The open chat always shows.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
    }
}

/** Compact layout, and the chat list beside the chat on wide screens (tablets, unfolded phones). */
@Composable
private fun LayoutPanel() {
    Panel {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Compact layout", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Everything a bit smaller, so more fits on screen.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(ThemePrefs.compact, { ThemePrefs.pickCompact(it) })
        }
        PanelDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat list beside the chat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Wide screens. Off gives the chat the full width; the menu button in the chat brings the list back. " +
                    "Drag the list's edge to resize it (now ${ThemePrefs.sidebarWidth} dp; double-tap the edge to reset).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(!ThemePrefs.sidebarHidden, { ThemePrefs.pickSidebarHidden(!it) })
        }
    }
}

/** "Keep connected in the background" and how long to linger after the last work ends; both persist in [KeepAlivePrefs]. */
@Composable
private fun KeepAlivePanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { KeepAlivePrefs(context.applicationContext) }
    var on by remember { mutableStateOf(prefs.keepConnected) }
    var minutes by remember { mutableStateOf(prefs.lingerMinutes) }
    val choices = KeepAlivePrefs.LINGER_CHOICES
    Panel {
        PanelRow("Keep connected in the background", "Keeps chats, delegations and loops running after you leave the app") {
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; prefs.keepConnected = it })
        }
        if (on) {
            Text("Stay connected after the last work ends", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Segmented(
                options = choices.map { "$it min" },
                selected = choices.indexOf(minutes).coerceAtLeast(0),
                enabled = choices.map { true },
                onSelect = { minutes = choices[it]; prefs.lingerMinutes = choices[it] },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** A titled settings group: header, one-line hint, then its panels, with even spacing between groups. */
@Composable
internal fun SettingsSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp))
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        Spacer(Modifier.size(10.dp))
        content()
    }
}

/** Hairline between rows inside a [Panel]. */
@Composable
internal fun PanelDivider() =
    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun ThemeSwatch(label: String, selected: Boolean, bg: Color, dot: Color, modifier: Modifier,
                        split: Color? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape)
            .background(if (split != null) Brush.linearGradient(0.5f to split, 0.5f to bg) else SolidColor(bg))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(dot))
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ================================================================ Pairing

/**
 * A gateway host whose tokens are gone or refused: say so and offer sign-in again (the host entry stays).
 * [baseUrl] is null when the tokens were lost with it, then the address is asked again.
 */
@Composable
fun SignInAgainScreen(
    name: String,
    baseUrl: String?,
    onSignIn: suspend (String) -> String?,
    onCancelSignIn: () -> Unit,
    onAddHost: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember(baseUrl) { mutableStateOf(baseUrl ?: "https://") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 16.dp)) {
        Spacer(Modifier.size(40.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Your session ended. Sign in again.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(20.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Gateway URL") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.size(10.dp))
        Button(onClick = {
            busy = true; error = null
            scope.launch { error = onSignIn(url); busy = false }
        }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)) { Text("Sign in again", fontWeight = FontWeight.SemiBold) }
        AnimatedVisibility(busy) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TypingDots(Gold); Spacer(Modifier.width(10.dp)); Text("Waiting for the sign-in", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancelSignIn) { Text("Cancel sign-in") }
            }
        }
        AnimatedVisibility(error != null) {
            Surface(color = Bad.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(error ?: "", color = Bad, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onAddHost, enabled = !busy) { Text("Add another host") }
    }
}

@Composable
fun PairScreen(
    initialUri: String?,
    onScan: () -> Unit,
    onPickImage: () -> Unit,
    onPair: suspend (Pairing) -> String?,
    imageError: String? = null,
    onCancel: (() -> Unit)? = null,
    onSignInGateway: (suspend (String) -> String?)? = null,
    onCancelSignIn: (() -> Unit)? = null,
    /** Re-pairing the PC in use as this device name (its pairing broke). */
    repairDevice: String? = null,
    /** False when an update strip already sits above this screen and took the status bar inset. */
    insetTop: Boolean = true,
    /** Other saved PCs to go back to (this screen is also where an empty slot lands). */
    otherPcs: List<PairedPc> = emptyList(),
    onSwitchPc: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var gatewayMode by remember { mutableStateOf(false) }
    var gatewayUrl by remember { mutableStateOf("https://") }
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

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).then(if (insetTop) Modifier.statusBarsPadding() else Modifier)
        .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.offset(x = (-12).dp)) { Text("Cancel") }
        } else {
            Spacer(Modifier.size(40.dp))
        }
        Box(Modifier.size(72.dp).background(Gold.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Spark, null, tint = Gold, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.size(20.dp))
        Text(when { repairDevice != null -> "Re-pair this PC"; onCancel != null -> "Add another PC"; else -> "Hermes Remote" },
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(when {
            repairDevice != null -> "Get a fresh code from the PC. Chats, drafts and the PC's name stay as they are."
            onCancel != null -> "Each PC keeps its own chats. Switch between them from the side menu."
            else -> "Your Hermes agent, from your phone."
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (otherPcs.isNotEmpty() && repairDevice == null) {
            Spacer(Modifier.size(20.dp))
            SectionLabel("Your PCs")
            Panel {
                otherPcs.forEach { pc ->
                    PanelRow(pc.name, if (pc.gateway) "Gateway" else "Bridge", Glyphs.Desktop, onClick = { onSwitchPc(pc.id) })
                }
            }
        }
        Spacer(Modifier.size(28.dp))
        if (onSignInGateway != null && repairDevice == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!gatewayMode, { gatewayMode = false; error = null }, label = { Text("Pair a bridge") }, enabled = !busy)
                FilterChip(gatewayMode, { gatewayMode = true; error = null }, label = { Text("Sign in to a gateway") }, enabled = !busy)
            }
        }
        Spacer(Modifier.size(8.dp))
        if (gatewayMode && onSignInGateway != null) {
            Text("Enter the gateway's address. Its own login opens in your browser.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(10.dp))
            OutlinedTextField(gatewayUrl, { gatewayUrl = it }, label = { Text("Gateway URL") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Spacer(Modifier.size(10.dp))
            Button(onClick = {
                busy = true; error = null
                scope.launch { error = onSignInGateway(gatewayUrl); busy = false }
            }, enabled = !busy && gatewayUrl.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)) { Text("Sign in", fontWeight = FontWeight.SemiBold) }
            AnimatedVisibility(busy) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(Gold); Spacer(Modifier.width(10.dp)); Text("Waiting for the sign-in", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onCancelSignIn?.invoke() }) { Text("Cancel sign-in") }
                }
            }
            AnimatedVisibility(error != null) {
                Surface(color = Bad.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(error ?: "", color = Bad, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Column
        }
        if (repairDevice != null) {
            Step(1, "On your PC, open the tray: Devices > $repairDevice > Re-pair. Or run", code = "hermes-remote-bridge pair $repairDevice --replace")
        } else {
            Step(1, "On your PC, run", code = "hermes-remote-bridge pair phone")
        }
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



/** Settings > Blueprint boards: the agent instructions every board follows unless it has its own. */
@Composable
private fun GlobalInstructionsPanel(saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent instructions (global)", style = MaterialTheme.typography.labelLarge)
            Text("Rules Hermes follows on every board that has none of its own (board ⋮ > Agent instructions > Reset to global).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InstructionsEditor(text, { text = it }, "e.g. Read the project docs first. Always attach the result to the card.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(text.trim()) }, enabled = text.trim() != saved.trim()) { Text("Save for every board") }
                if (text.trim() != saved.trim()) TextButton(onClick = { text = saved }) { Text("Undo") }
            }
        }
    }
}
