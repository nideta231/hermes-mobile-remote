package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.data.ChatGraph
import io.github.nideta231.hermesremote.data.GraphKind
import io.github.nideta231.hermesremote.data.KnowledgeGraph
import io.github.nideta231.hermesremote.data.LibraryFilter
import io.github.nideta231.hermesremote.data.LibraryRepo
import io.github.nideta231.hermesremote.data.LibraryState
import io.github.nideta231.hermesremote.data.LibraryText
import io.github.nideta231.hermesremote.data.Memory
import io.github.nideta231.hermesremote.data.ModelCatalog
import org.json.JSONObject

/** Colour per memory kind, shared by the list badges and the knowledge map. */
internal fun kindColor(kind: String): Color = when (kind) {
    "mistake" -> Color(0xFFE5675C); "rule" -> Color(0xFFE8B54A); "preference" -> Color(0xFF5BA8E8)
    "workflow" -> Color(0xFF4FC3A1); "decision" -> Color(0xFFB48CF2); else -> Color(0xFF9AA0A6)
}

private val ClientColor = Color(0xFFF08A5D)

class LibraryActions(
    val repo: LibraryRepo,
    /** The model list the chat picker uses, for the memory model. */
    val models: ModelCatalog?,
    val loadModels: () -> Unit,
    val openChat: (String) -> Unit,
)

/**
 * The Memory Library: everything Hermes remembers on this PC (for the profile in use), what it would
 * recall for a message and why, the knowledge map, and the setup (install with consent, on/off, doctor,
 * memory model).
 */
@Composable
fun LibraryScreen(state: LibraryState, actions: LibraryActions, startTab: Int = 0, onBack: () -> Unit) {
    val repo = actions.repo
    DisposableEffect(Unit) { repo.watch(); onDispose { repo.stop() } }
    val st = state.status
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    // Not installed (or off) opens on Setup: there is nothing to browse yet.
    LaunchedEffect(st?.installed) { if (st != null && !st.installed) tab = 3 }
    PageScaffold("Memory Library", onBack) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.unsupported -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("This PC's bridge has no Memory Library. Update the bridge (PC bridge > Update).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                st == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    state.error?.let { Text(it, color = Bad, modifier = Modifier.padding(24.dp)) } ?: TypingDots(Gold)
                }
                else -> {
                    TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        listOf("Memories", "Test recall", "Map", "Setup").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            0 -> MemoriesTab(state, repo)
                            1 -> RecallTab(state, repo)
                            2 -> MapTab(state, repo, actions.openChat)
                            else -> SetupTab(state, actions)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ Memories

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoriesTab(state: LibraryState, repo: LibraryRepo) {
    val f = state.filter
    var open by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (state.status?.installed != true) return NotInstalled()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(f.query, { q -> repo.setFilter { it.copy(query = q) } }, "Search memories", Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("Add") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryText.KINDS.forEach { k ->
                FilterChip(f.kind == k, { repo.setFilter { it.copy(kind = if (it.kind == k) "" else k) } },
                    { Legend(kindColor(k), k.replaceFirstChar(Char::uppercase)) }, colors = themedChip())
            }
            FilterChip(f.minSeverity >= 4, { repo.setFilter { it.copy(minSeverity = if (it.minSeverity >= 4) 1 else 4) } },
                { Text("Critical only") }, colors = themedChip())
            FilterChip(f.review == true, { repo.setFilter { it.copy(review = if (it.review == true) null else true) } },
                { Text("Needs review") }, colors = themedChip())
            FilterChip(f.status != "active", { repo.setFilter { it.copy(status = if (it.status == "active") "" else "active") } },
                { Text("Show retired") }, colors = themedChip())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("severity" to "Severity", "repeats" to "Most repeated", "hits" to "Most recalled", "newest" to "Newest", "unused" to "Never recalled").forEach { (k, l) ->
                FilterChip(f.sort == k, { repo.setFilter { it.copy(sort = k) } }, { Text(l) }, colors = themedChip())
            }
            ScopePicker(f.scope, state, allowAll = true) { s -> repo.setFilter { it.copy(scope = s) } }
        }
        state.stats?.let { s ->
            Text("${state.total} shown · ${s.needsReview} need review · ${s.unused30d} never recalled in 30 days",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (state.memories.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (f == LibraryFilter()) "No memories yet. Hermes saves them as you correct it, or tap Add." else "Nothing matches these filters.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.memories, key = { it.id }) { m -> MemoryCard(m, state) { repo.get(m.id) { open = it } } }
        }
    }
    open?.let { m -> MemorySheet(m, state, repo, onDismiss = { open = null }, onChanged = { open = it }) }
    if (adding) MemoryEditDialog(null, state, onDismiss = { adding = false }) { text, kind, sev, scope, kw ->
        repo.add(text, kind, sev, scope, kw) { adding = false }
    }
}

@Composable
private fun NotInstalled() = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text("The Memory Library isn't set up on this PC yet. Open Setup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KindBadge(kind: String, severity: Int) {
    val c = kindColor(kind)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("$kind · ${LibraryText.severityLabel(severity)}", style = MaterialTheme.typography.labelSmall, color = c)
    }
}

@Composable
private fun MemoryCard(m: Memory, state: LibraryState, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(m.kind, m.severity)
                Spacer(Modifier.weight(1f))
                if (m.review) Pill("Review", tint = Warn)
                if (m.status != "active") Pill(m.status, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("#${m.id} · ${LibraryText.scopeLabel(m.scope, state.projects)} · recalled ${m.hits}× · repeated ${m.repeats}×",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MemorySheet(m: Memory, state: LibraryState, repo: LibraryRepo, onDismiss: () -> Unit, onChanged: (Memory) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(m.kind, m.severity)
                SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyLarge) }
                if (m.keywords.isNotBlank()) Text("Keywords: ${m.keywords}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("#${m.id} · ${LibraryText.scopeLabel(m.scope, state.projects)} · ${m.status} · recalled ${m.hits}× · repeated ${m.repeats}× · from ${m.source}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                    TextButton(onClick = { repo.bump(m.id) { onChanged(m.copy(repeats = it.repeats, severity = it.severity)) } }) { Text("Happened again") }
                    if (m.review) TextButton(onClick = { repo.update(m.id, JSONObject().put("review", false)) { onChanged(m.copy(review = false)) } }) { Text("Mark reviewed") }
                    TextButton(onClick = { merging = true }) { Text("Merge…") }
                    if (m.status == "active") TextButton(onClick = { repo.update(m.id, JSONObject().put("status", "archived")) { onChanged(m.copy(status = "archived")) } }) { Text("Archive") }
                    else TextButton(onClick = { repo.update(m.id, JSONObject().put("status", "active")) { onChanged(m.copy(status = "active")) } }) { Text("Restore") }
                    TextButton(onClick = { deleting = true }) { Text("Delete", color = Bad) }
                }
                if (m.links.isNotEmpty()) {
                    SectionLabel("Links")
                    m.links.forEach { l ->
                        val other = if (l.src == m.id) l.dst else l.src
                        val dir = if (l.src == m.id) l.rel else "${l.rel} (from)"
                        Text("$dir #$other · ${l.otherText}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (m.history.isNotEmpty()) {
                    SectionLabel("History")
                    m.history.take(12).forEach { h ->
                        Text("${h.at.take(16).replace('T', ' ')} · ${h.by} · ${h.action}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (editing) MemoryEditDialog(m, state, onDismiss = { editing = false }) { text, kind, sev, scope, kw ->
        repo.update(m.id, JSONObject().put("text", text).put("kind", kind).put("severity", sev).put("scope", scope).put("keywords", kw)) {
            editing = false; onChanged(it.copy(links = m.links, history = m.history))
        }
    }
    if (merging) {
        var other by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { merging = false }, title = { Text("Merge into #${m.id}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The other memory is folded into this one: its keywords, recalls and repeats add up, the higher severity wins, and it is retired (kept in history).")
                OutlinedTextField(other, { other = it.filter(Char::isDigit) }, label = { Text("Other memory #") }, singleLine = true)
            }
        }, confirmButton = { TextButton(enabled = other.isNotBlank() && other.toLongOrNull() != m.id,
            onClick = { merging = false; repo.merge(m.id, other.toLong()) { onChanged(it) } }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete memory #${m.id}?") },
        text = { Text("Hermes forgets it for good. Archive keeps it out of recall but restorable.") },
        confirmButton = { TextButton(onClick = { deleting = false; repo.delete(m.id); onDismiss() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditDialog(m: Memory?, state: LibraryState, onDismiss: () -> Unit,
                             onSave: (text: String, kind: String, severity: Int, scope: String, keywords: String) -> Unit) {
    var text by remember { mutableStateOf(m?.text ?: "") }
    var kind by remember { mutableStateOf(m?.kind ?: "rule") }
    var sev by remember { mutableIntStateOf(m?.severity ?: 3) }
    var scope by remember { mutableStateOf(m?.scope ?: "global") }
    var kw by remember { mutableStateOf(m?.keywords ?: "") }
    val okScope = LibraryText.validScope(scope)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (m == null) "New memory" else "Edit #${m.id}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { if (it.length <= 2000) text = it }, label = { Text("What Hermes should remember") }, minLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryText.KINDS.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k) }, colors = themedChip()) }
            }
            Text("Severity: ${LibraryText.severityLabel(sev)}" + if (sev >= 4) " (always recalled in its scope)" else "",
                style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { s -> FilterChip(sev == s, { sev = s }, { Text("$s") }, colors = themedChip()) }
            }
            ScopePicker(scope, state, allowAll = false) { scope = it }
            OutlinedTextField(kw, { if (it.length <= 600) kw = it }, label = { Text("Keywords and synonyms (all your languages)") }, minLines = 2)
            if (!okScope) Text("Scope must be global, project:<id> or client:<name>", color = Bad, style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(enabled = text.isNotBlank() && okScope, onClick = { onSave(text.trim(), kind, sev, scope, kw.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Everywhere / a Hermes project / a client name. [allowAll] adds "All scopes" (blank) for filtering. */
@Composable
private fun ScopePicker(value: String, state: LibraryState, allowAll: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf(false) }
    Box {
        Pill(if (value.isBlank()) "All scopes" else LibraryText.scopeLabel(value, state.projects), icon = Glyphs.Folder, onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            if (allowAll) DropdownMenuItem({ Text("All scopes") }, { open = false; onPick("") })
            DropdownMenuItem({ Text("Everywhere") }, { open = false; onPick("global") })
            state.projects.forEach { p -> DropdownMenuItem({ Text(p.name) }, { open = false; onPick(p.scope) }) }
            (state.stats?.scopes.orEmpty().map { it.first }.filter { it.startsWith("client:") }).forEach { c ->
                DropdownMenuItem({ Text(LibraryText.scopeLabel(c, state.projects)) }, { open = false; onPick(c) })
            }
            if (!allowAll) DropdownMenuItem({ Text("A client…") }, { open = false; client = true })
        }
    }
    if (client) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { client = false }, title = { Text("Client") }, text = {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("Client name") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = LibraryText.validScope("client:${name.trim()}"),
            onClick = { client = false; onPick("client:${name.trim()}") }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { client = false }) { Text("Cancel") } })
    }
}

// ================================================================ Test recall

@Composable
private fun RecallTab(state: LibraryState, repo: LibraryRepo) {
    if (state.status?.installed != true) return NotInstalled()
    var q by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Type a message the way you'd write it in a chat. You see exactly what Hermes would get before answering, and why.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(q, { q = it.take(2000) }, label = { Text("Message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat in", style = MaterialTheme.typography.labelMedium)
            ScopePicker(scope, state, allowAll = true) { scope = it }
            Spacer(Modifier.weight(1f))
            Button(enabled = q.isNotBlank() && state.busy != "recall", onClick = { repo.recall(q, listOfNotNull(scope.ifBlank { null })) }) { Text("Test") }
        }
        val r = state.recall
        if (state.busy == "recall") TypingDots(Gold)
        if (r != null) {
            SectionLabel("Always applied (${r.pinned.size})")
            if (r.pinned.isEmpty()) Text("No critical memories in this scope.", style = MaterialTheme.typography.bodySmall)
            r.pinned.forEach { m -> RecallRow(m, state, "Severity ${m.severity} in scope: always included") }
            SectionLabel("Matched this message (${r.relevant.size})")
            if (r.relevant.isEmpty()) Text("Nothing else matched. Try the words a saved memory would use, or add keywords to it.",
                style = MaterialTheme.typography.bodySmall)
            r.relevant.forEach { m -> RecallRow(m, state, LibraryText.scoreLine(m)) }
            SectionLabel("What Hermes receives")
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                SelectionContainer { Text(r.injected.ifBlank { "(nothing)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    lineHeight = 15.sp, modifier = Modifier.padding(12.dp)) }
            }
        }
    }
}

@Composable
private fun RecallRow(m: Memory, state: LibraryState, why: String?) {
    Panel {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            KindBadge(m.kind, m.severity)
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Text("#${m.id} · ${LibraryText.scopeLabel(m.scope, state.projects)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            why?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Gold) }
        }
    }
}

// ================================================================ Knowledge map

/** The knowledge map: memories coloured by kind and sized by severity, around the projects and clients they apply to. */
@Composable
internal fun KnowledgeMap(repo: LibraryRepo, state: LibraryState, openChat: (String) -> Unit, modifier: Modifier = Modifier) {
    var showChats by rememberSaveable { mutableStateOf(false) }
    var onlyMistakes by rememberSaveable { mutableStateOf(false) }
    var majorUp by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf("") }
    var graph by remember { mutableStateOf<ChatGraph?>(null) }
    var open by remember { mutableStateOf<Memory?>(null) }
    LaunchedEffect(showChats, onlyMistakes, majorUp, scope) {
        graph = repo.graph(scope, if (onlyMistakes) listOf("mistake") else emptyList(), if (majorUp) 3 else 1, showChats)?.let(KnowledgeGraph::build)
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ScopePicker(scope, state, allowAll = true) { scope = it }
            FilterChip(onlyMistakes, { onlyMistakes = !onlyMistakes }, { Legend(kindColor("mistake"), "Mistakes only") }, colors = themedChip())
            FilterChip(majorUp, { majorUp = !majorUp }, { Text("Major and up") }, colors = themedChip())
            FilterChip(showChats, { showChats = !showChats }, { Text("Chats they came from") }, colors = themedChip())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LibraryText.KINDS.forEach { Legend(kindColor(it), it) }
            Legend(Gold, "project"); Legend(ClientColor, "client")
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val g = graph
            when {
                g == null -> TypingDots(Gold)
                g.nodes.none { it.kind == GraphKind.MEMORY } -> Text("No memories to map yet.", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val chatColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val hub = MaterialTheme.colorScheme.onSurface
                    val gold = Gold
                    GraphCanvas(g, colorOf = { n -> when (n.kind) {
                        GraphKind.MEMORY -> kindColor(n.tag); GraphKind.PROJECT -> gold; GraphKind.CLIENT -> ClientColor
                        GraphKind.CHAT -> chatColor; else -> hub } },
                        sizeOf = { n -> if (n.kind == GraphKind.MEMORY) 0.6f + n.weight * 0.22f else 1.2f },
                        isLeaf = { it.kind == GraphKind.MEMORY || it.kind == GraphKind.CHAT },
                        kindLabel = { n -> when (n.kind) {
                            GraphKind.MEMORY -> "${n.tag.replaceFirstChar(Char::uppercase)} · ${LibraryText.severityLabel(n.weight)}"
                            GraphKind.PROJECT -> "Project"; GraphKind.CLIENT -> "Client"; GraphKind.CHAT -> "Chat"
                            else -> "Applies everywhere" } },
                        openLabel = { n -> when (n.kind) { GraphKind.MEMORY, GraphKind.CHAT -> "Open"; else -> null } },
                        onOpen = { n -> if (n.kind == GraphKind.CHAT) openChat(n.ref) else n.ref.toLongOrNull()?.let { id -> repo.get(id) { open = it } } })
                }
            }
        }
    }
    open?.let { m -> MemorySheet(m, state, repo, onDismiss = { open = null }, onChanged = { open = it }) }
}

@Composable
private fun MapTab(state: LibraryState, repo: LibraryRepo, openChat: (String) -> Unit) {
    if (state.status?.installed != true) return NotInstalled()
    KnowledgeMap(repo, state, openChat)
}

// ================================================================ Setup

@Composable
private fun SetupTab(state: LibraryState, actions: LibraryActions) {
    val repo = actions.repo
    val st = state.status ?: return
    var consent by remember { mutableStateOf(false) }
    var enableAsk by remember { mutableStateOf(false) }
    var disableAsk by remember { mutableStateOf(false) }
    LaunchedEffect(st.installed) { if (st.installed && state.doctor.isEmpty()) repo.doctor() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.error?.let { Panel { PanelRow("Something went wrong", it, Glyphs.Alert, iconTint = Bad) } }
        SettingsSection("This PC", "Hermes' own long-term memory, on a private database the feature installs (never your own Postgres). " +
            "Profile: ${st.profile}.") {
            Panel {
                if (!st.supported) PanelRow("Not supported here", st.reason, Glyphs.Alert, iconTint = Warn)
                else if (!st.installed) PanelRow("Database runtime", LibraryText.jobLine(st.job) ?: "Not installed", Glyphs.Download) {
                    if (st.installing || state.busy == "install") TypingDots()
                    else if (st.admin) TextButton(onClick = { consent = true }) { Text("Install…") }
                } else PanelRow("Database runtime", "Installed" + (st.benchmarkMs?.let { ", about ${it.toInt()} ms per query" } ?: "") +
                    if (st.running) ", running" else ", stopped", Glyphs.Check, iconTint = Ok)
                if (st.installed) PanelRow("Memory Library for this profile",
                    if (st.enabled) "On: new chats save and recall memories here" else "Off: memory is ${st.provider}", Glyphs.Brain,
                    iconTint = if (st.enabled) Ok else MaterialTheme.colorScheme.onSurfaceVariant) {
                    if (state.busy == "enable" || state.busy == "disable") TypingDots()
                    else if (st.admin) Switch(st.enabled, { on -> if (on) enableAsk = true else disableAsk = true })
                }
                if (!st.admin) PanelRow("Turned off on the PC", "remote_admin = false: install and on/off only from the PC.", Glyphs.Shield)
            }
        }
        if (st.installed) {
            SettingsSection("Memory model", "Does the background work: optional smart recall, reviewing finished chats for lessons, cleanup. " +
                "Same as the chat by default; any model Hermes already has.") {
                Panel {
                    MemoryModelRow(st.model, st.modelProvider, actions)
                    PanelRow("Smart recall", "The memory model picks what to recall before each reply (+1 model call, about 1 to 2 s).", Glyphs.Spark) {
                        Switch(st.smartRecall, { repo.setSettings(smartRecall = it) })
                    }
                    PanelRow("Review finished chats", "After a chat ends, the memory model saves corrections and rules the chat missed (marked for review).", Glyphs.Check) {
                        Switch(st.sessionReview, { repo.setSettings(sessionReview = it) })
                    }
                }
            }
            SettingsSection("Doctor", "Checks the database, the plugin, the provider setting and the memories.") {
                Panel {
                    state.doctor.forEach { c ->
                        val tint = when (c.status) { "ok" -> Ok; "warn" -> Warn; else -> Bad }
                        PanelRow(c.id.replaceFirstChar(Char::uppercase), c.text, if (c.status == "ok") Glyphs.Check else Glyphs.Alert, iconTint = tint) {
                            c.fix?.let { f ->
                                if (state.busy == f) TypingDots()
                                else if (st.admin) TextButton(onClick = { if (f == "install") consent = true else repo.fix(f) }) { Text(fixName(f)) }
                            }
                        }
                    }
                    PanelRow("Check again", null, Glyphs.Refresh, onClick = { repo.doctor() }) { if (state.busy == "doctor") TypingDots() }
                }
            }
            state.stats?.let { s ->
                if (s.mostRepeated.isNotEmpty()) SettingsSection("Most repeated mistakes", "Lessons Hermes needed more than once.") {
                    Panel { s.mostRepeated.take(5).forEach { m -> PanelRow(m.text, "repeated ${m.repeats}× · ${LibraryText.severityLabel(m.severity)}", Glyphs.Alert, iconTint = kindColor(m.kind)) } }
                }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
    val plan = st.plan
    if (consent && plan != null) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Install the Memory Library?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PostgreSQL 18 (pinned, checksum-checked), used only by this feature.")
            Text("Download: ${LibraryText.mb(plan.downloadBytes)}. On disk: about ${LibraryText.mb(plan.diskBytes)}. Free there: ${LibraryText.mb(plan.freeBytes)}.")
            Text("Location: ${plan.location}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("Network: ${plan.network}")
            Text("Remove: ${plan.remove}")
            if (!plan.enoughDisk) Text("Not enough free disk space.", color = Bad)
            Text("Installing changes nothing in Hermes yet: you switch it on per profile afterwards.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(enabled = plan.enoughDisk && plan.supported, onClick = { consent = false; repo.install() }) { Text("Install") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
    if (enableAsk) {
        var switch by remember { mutableStateOf(false) }
        var import by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { enableAsk = false }, title = { Text("Turn on for profile ${st.profile}?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New chats get recalled memories before each reply and the library_* tools to save lessons. MEMORY.md and USER.md stay on as they are.")
                st.customProvider?.let { cp ->
                    Text("This profile already uses the \"$cp\" memory provider. Only one can run.", color = Warn)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(switch, { switch = it }); Spacer(Modifier.width(8.dp))
                        Text("Switch to the Library ($cp comes back when you turn this off)")
                    }
                }
                if (st.pluginConflict) Text("Another plugin named \"library\" is installed in this profile; it won't be touched, so this can't turn on.", color = Bad)
                if (st.builtinEntries > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(import, { import = it }); Spacer(Modifier.width(8.dp))
                    Text("Copy my ${st.builtinEntries} built-in memories in (backed up, originals stay, marked for review)")
                }
            }
        }, confirmButton = { TextButton(enabled = !st.pluginConflict && (st.customProvider == null || switch),
            onClick = { enableAsk = false; repo.enable(switch, import) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { enableAsk = false }) { Text("Cancel") } })
    }
    if (disableAsk) AlertDialog(onDismissRequest = { disableAsk = false }, title = { Text("Turn off for profile ${st.profile}?") },
        text = { Text("Memory goes back to " + (st.customProvider ?: "the built-in MEMORY.md / USER.md") + " for new chats. Every memory stays in the Library; turning it on again picks them up.") },
        confirmButton = { TextButton(onClick = { disableAsk = false; repo.disable() }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { disableAsk = false }) { Text("Cancel") } })
}

private fun fixName(f: String) = when (f) {
    "install" -> "Install"; "start" -> "Start"; "enable" -> "Re-enable"; "disable" -> "Turn off"; "migrate" -> "Repair"; "merge" -> "Merge"; else -> "Fix"
}

@Composable
private fun MemoryModelRow(model: String, provider: String, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    val opts = actions.models?.options.orEmpty()
    val label = if (model.isBlank()) "Same as the chat (auto)" else opts.firstOrNull { it.id == model && (provider == "auto" || it.provider == provider) }
        ?.let { "${it.label} · ${it.providerName}" } ?: model
    Box {
        PanelRow("Memory model", label, Glyphs.Brain, onClick = { actions.loadModels(); open = true })
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem({ Text("Same as the chat (auto)") }, { open = false; actions.repo.setSettings(model = "", provider = "auto") })
            if (opts.isEmpty()) DropdownMenuItem({ Text("Loading models…") }, {}, enabled = false)
            opts.forEach { o ->
                DropdownMenuItem({ Text("${o.label} · ${o.providerName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    { open = false; actions.repo.setSettings(model = o.id, provider = o.provider) })
            }
        }
    }
}

/** The Graph page on a bridge with the Memory Library: the knowledge map (chats only as an optional layer). */
@Composable
fun KnowledgeMapScreen(state: LibraryState, repo: LibraryRepo, openChat: (String) -> Unit, openSetup: () -> Unit, onBack: () -> Unit) {
    DisposableEffect(Unit) { repo.watch(); onDispose { repo.stop() } }
    PageScaffold("Graph", onBack) {
        val st = state.status
        when {
            st == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(Gold) }
            !st.installed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The graph maps what Hermes has learned: memories by kind and severity, around the projects and clients they apply to.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Button(onClick = openSetup) { Text("Set up the Memory Library") }
            }
            else -> KnowledgeMap(repo, state, openChat)
        }
    }
}
