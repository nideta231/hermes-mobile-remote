package io.github.nideta231.hermesremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.nideta231.hermesremote.data.Blueprint
import io.github.nideta231.hermesremote.data.BlueprintState
import io.github.nideta231.hermesremote.data.BpCard
import io.github.nideta231.hermesremote.data.BpCheck
import io.github.nideta231.hermesremote.data.BpNode
import io.github.nideta231.hermesremote.data.BpOp
import io.github.nideta231.hermesremote.data.BpSettings
import io.github.nideta231.hermesremote.data.BpLimits
import io.github.nideta231.hermesremote.data.agingAlpha
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.animation.core.animateFloat
import org.json.JSONObject
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

class BlueprintActions(
    val open: (String) -> Unit,
    val close: () -> Unit,
    val apply: (Array<JSONObject>, (List<JSONObject>) -> Unit) -> Unit,
    val trash: (String) -> Unit,
    /** Start a chat in this project asking Hermes to work from the blueprint (draft only). */
    val askHermes: (Blueprint, String) -> Unit,
    /** A file a card comment attaches, downloaded from the PC (open/save). */
    val fetchFile: suspend (String) -> java.io.File = { throw java.io.IOException("Not available") },
    val fileInfo: suspend (String) -> io.github.nideta231.hermesremote.data.FileInfo = { io.github.nideta231.hermesremote.data.FileInfo(io.github.nideta231.hermesremote.data.fileName(it), it) },
    /** Hermes comments on the card the user has not seen yet. */
    val unread: (BpCard) -> Int = { 0 },
    /** The user opened the card: its comments are read. */
    val markSeen: (BpCard) -> Unit = {},
    /** Open the app's Settings at the global board settings. */
    val openGlobalSettings: () -> Unit = {},
    /** Send a picked file (content uri) to the PC; answers its PC path, or null when it failed (already said why). */
    val upload: suspend (String) -> String? = { null },
)

private fun bpColor(name: String, dark: Boolean): Color = when (name) {
    "red" -> if (dark) Color(0xFF77172E) else Color(0xFFFAAFA8)
    "orange" -> if (dark) Color(0xFF692B17) else Color(0xFFF39F76)
    "yellow" -> if (dark) Color(0xFF7C4A03) else Color(0xFFFFF8B8)
    "green" -> if (dark) Color(0xFF264D3B) else Color(0xFFE2F6D3)
    "teal" -> if (dark) Color(0xFF0C625D) else Color(0xFFB4DDD3)
    "blue" -> if (dark) Color(0xFF256377) else Color(0xFFD4E4ED)
    "purple" -> if (dark) Color(0xFF472E5B) else Color(0xFFD3BFDB)
    "pink" -> if (dark) Color(0xFF6C394F) else Color(0xFFF6E2DD)
    "gray" -> if (dark) Color(0xFF3A3B3F) else Color(0xFFEFEFF1)
    else -> Color.Unspecified
}

private val BpPalette = listOf("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "gray")
private val NodeKinds = listOf("feature" to "Feature", "flow" to "Flow", "decision" to "Decision", "start" to "Start", "end" to "End", "note" to "Note")

@Composable
private fun isDark() = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3 < 0.5f }

/**
 * A project's blueprint: the flow diagram (like draw.io), the task board (like Trello) and what changed
 * (who did what, Hermes included). Hermes reads and updates the same blueprint through its skill.
 */
@Composable
fun BlueprintScreen(projectId: String, projectName: String, state: BlueprintState, actions: BlueprintActions, onBack: () -> Unit) {
    DisposableEffect(projectId) { actions.open(projectId); onDispose { actions.close() } }
    var tab by rememberSaveable { mutableStateOf(1) }
    var menu by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    /** The instructions dialog: (open, then hand control to Hermes on save). */
    var instructions by remember { mutableStateOf(false to false) }
    var boardSettings by remember { mutableStateOf(false) }
    var inbox by remember { mutableStateOf(false) }
    /** A card to open on the board (from the inbox). */
    var openCard by remember { mutableStateOf<String?>(null) }
    val bp = state.open?.takeIf { it.projectId == projectId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Glyphs.Back, "Back") }
            Column(Modifier.weight(1f)) {
                Text(bp?.name ?: projectName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val waiting = bp?.needsYou?.size ?: 0
                val working = bp?.cards?.count { it.agent != null } ?: 0
                val unreadCards = bp?.cards?.count { actions.unread(it) > 0 } ?: 0
                Text(listOfNotNull(if (bp?.hermesControl == true) "Blueprint · Hermes in control" else "Blueprint",
                        working.takeIf { it > 0 }?.let { "$it in progress" }, unreadCards.takeIf { it > 0 }?.let { "$it with new comments" }, waiting.takeIf { it > 0 }?.let { "$it waiting for you" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { waiting > 0 -> Warn; bp?.hermesControl == true -> Gold; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
            if (bp != null) {
                val pending = inboxItems(bp, actions.unread).size
                IconButton(onClick = { inbox = true }) {
                    Box {
                        Icon(Glyphs.Bell, "Comments and questions", tint = if (pending > 0) Gold else LocalContentColor.current)
                        if (pending > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-6).dp)
                            .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp).background(Gold, CircleShape).padding(horizontal = 3.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (pending > 99) "99+" else "$pending", color = MaterialTheme.colorScheme.surface,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (bp != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Glyphs.More, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Ask Hermes to work on this") }, { menu = false; actions.askHermes(bp, "work") }, leadingIcon = { Icon(Glyphs.Spark, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text(if (bp.hermesControl) "Take back control" else "Let Hermes take control") }, {
                        menu = false
                        if (bp.hermesControl) actions.apply(arrayOf(BpOp.of("control.set", "mode" to "shared"))) {}
                        else instructions = true to true
                    }, leadingIcon = { Icon(Glyphs.Agent, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text(when { bp.instructions.isNotBlank() -> "Agent instructions (own)"; !bp.globalInstructions.isNullOrBlank() -> "Agent instructions (global)"; else -> "Agent instructions" }) }, { menu = false; instructions = true to false },
                        leadingIcon = { Icon(Glyphs.Edit, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text("Board settings") }, { menu = false; boardSettings = true },
                        leadingIcon = { Icon(Glyphs.Settings, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text("Tidy diagram") }, { menu = false; actions.apply(arrayOf(BpOp.of("diagram.layout"))) {} }, leadingIcon = { Icon(Glyphs.Graph, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text("Remove blueprint", color = Bad) }, { menu = false; confirmTrash = true }, leadingIcon = { Icon(Glyphs.Trash, null, Modifier.size(18.dp), tint = Bad) })
                }
            }
        }
        TabRow(tab, containerColor = Color.Transparent, contentColor = Gold) {
            listOf("Diagram", "Board", "Activity").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalFileFetcher provides actions.fetchFile, LocalFileInfo provides actions.fileInfo,
                LocalImageLoader provides { p: String -> runCatching { val f = actions.fetchFile(p)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.readBytes() } }.getOrNull() }) {
            when {
                bp == null && state.error != null -> Text(state.error, color = Bad, modifier = Modifier.padding(24.dp))
                bp == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                tab == 0 -> DiagramView(bp, actions)
                tab == 1 -> BoardView(bp, actions, openCard) { openCard = it }
                else -> ActivityView(bp)
            }
            }
        }
        if (instructions.first && bp != null) InstructionsDialog(bp.instructions, handOver = instructions.second, global = bp.globalInstructions,
            onGlobal = { instructions = false to false; actions.openGlobalSettings() },
            onDismiss = { instructions = false to false }) { text ->
            val hand = instructions.second
            instructions = false to false
            val ops = buildList {
                if (text != bp.instructions) add(BpOp.of("instructions.set", "text" to text))
                if (hand) add(BpOp.of("control.set", "mode" to "hermes"))
            }
            if (ops.isNotEmpty()) actions.apply(ops.toTypedArray()) {}
            if (hand) actions.askHermes(bp.copy(instructions = text), "control")
        }
    }
    if (inbox && bp != null) InboxSheet(bp, actions, onDismiss = { inbox = false }) { id -> inbox = false; tab = 1; openCard = id }
    if (boardSettings && bp != null) BoardSettingsDialog(bp.settings, bp.defaults.over(BpSettings()), onDismiss = { boardSettings = false },
        onGlobal = { boardSettings = false; actions.openGlobalSettings() }) { st ->
        boardSettings = false
        if (st != bp.settings) actions.apply(arrayOf(BpOp.of("settings.set", "new_cards" to (st.newCards ?: "default"), "aging_days" to (st.agingDays ?: "default"),
            "sticky_add" to (st.stickyAdd ?: "default")))) {}
    }
    if (confirmTrash) AlertDialog(onDismissRequest = { confirmTrash = false }, title = { Text("Remove this blueprint?") },
        text = { Text("It goes to the trash (drawer > project long-press > Blueprint trash) and can be restored.") },
        confirmButton = { TextButton(onClick = { confirmTrash = false; actions.trash(projectId); onBack() }) { Text("Remove", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } })
}

// ================================================================ Diagram

/**
 * Pan with one finger, pinch to zoom; drag a node to move it; tap a node to select it (edit, connect,
 * delete). In connect mode the next tapped node gets an arrow from the selected one.
 */
@Composable
private fun DiagramView(bp: Blueprint, actions: BlueprintActions) {
    val dark = isDark()
    val density = LocalDensity.current
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Unspecified) }
    var selected by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BpNode?>(null) }
    var adding by remember { mutableStateOf(false) }
    var edgeSel by remember { mutableStateOf<String?>(null) }
    // A node being dragged: its live position, sent once on release.
    val dragPos = remember { mutableStateMapOf<String, Offset>() }
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(bp)
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val gold = Gold
    val dpScale = with(density) { 1.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        if (pan == Offset.Unspecified && bp.nodes.isNotEmpty()) {
            // Fit the diagram on first show.
            val minX = bp.nodes.minOf { it.x }; val maxX = bp.nodes.maxOf { it.x + it.w }
            val minY = bp.nodes.minOf { it.y }; val maxY = bp.nodes.maxOf { it.y + it.h }
            val s = minOf(w / ((maxX - minX) * dpScale + 80 * dpScale), h / ((maxY - minY) * dpScale + 120 * dpScale), 1.4f).coerceAtLeast(0.3f)
            scale = s
            pan = Offset(w / 2 - (minX + maxX) / 2 * dpScale * s, 40 * dpScale - minY * dpScale * s)
        }
        val p = if (pan == Offset.Unspecified) Offset(w / 2, 40 * dpScale) else pan
        fun toWorld(o: Offset) = Offset((o.x - p.x) / (scale * dpScale), (o.y - p.y) / (scale * dpScale))
        fun pos(n: BpNode) = dragPos[n.id] ?: Offset(n.x, n.y)
        fun hit(world: Offset) = current.nodes.lastOrNull { n -> val q = pos(n); world.x in q.x..(q.x + n.w) && world.y in q.y..(q.y + n.h) }

        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panBy, zoom, _ ->
                    val old = scale
                    val ns = (old * zoom).coerceIn(0.25f, 3f)
                    val base = if (pan == Offset.Unspecified) p else pan
                    pan = centroid - (centroid - base) * (ns / old) + panBy
                    scale = ns
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at -> hit(toWorld(at))?.let { n -> selected = n.id; dragPos[n.id] = Offset(n.x, n.y) } },
                    onDrag = { ch, d -> ch.consume(); selected?.let { id -> dragPos[id]?.let { q -> dragPos[id] = q + d / (scale * dpScale) } } },
                    onDragEnd = {
                        selected?.let { id -> dragPos[id]?.let { q ->
                            actions.apply(arrayOf(BpOp.of("node.update", "id" to id, "x" to q.x.toDouble(), "y" to q.y.toDouble()))) { dragPos.remove(id) }
                        } }
                    },
                    onDragCancel = { dragPos.clear() })
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at -> hit(toWorld(at))?.let { editing = it } }, onTap = { at ->
                    val wp = toWorld(at)
                    val n = hit(wp)
                    val sel = selected
                    when {
                        connecting && n != null && sel != null && n.id != sel -> {
                            actions.apply(arrayOf(BpOp.of("edge.add", "from" to sel, "to" to n.id))) {}
                            connecting = false; selected = n.id
                        }
                        n != null -> { selected = if (selected == n.id) null else n.id; edgeSel = null; connecting = false }
                        else -> {
                            // An arrow near the tap?
                            edgeSel = current.edges.firstOrNull { e ->
                                val a = current.nodes.firstOrNull { it.id == e.from } ?: return@firstOrNull false
                                val b = current.nodes.firstOrNull { it.id == e.to } ?: return@firstOrNull false
                                distToSegment(wp, centerOf(a, pos(a)), centerOf(b, pos(b))) < 14f / scale
                            }?.id
                            selected = null; connecting = false
                        }
                    }
                })
            }) {
            withTransform({ translate(p.x, p.y); scale(scale * dpScale, scale * dpScale, Offset.Zero) }) {
                // dotted grid, like the sketch paper
                val dotStep = 40f
                val tl = toWorld(Offset.Zero); val br = toWorld(Offset(w, h))
                var gx = (tl.x / dotStep).toInt() * dotStep
                while (gx < br.x) {
                    var gy = (tl.y / dotStep).toInt() * dotStep
                    while (gy < br.y) { drawCircle(muted.copy(alpha = 0.18f), 1.2f, Offset(gx, gy)); gy += dotStep }
                    gx += dotStep
                }
                bp.edges.forEach { e ->
                    val a = bp.nodes.firstOrNull { it.id == e.from } ?: return@forEach
                    val b = bp.nodes.firstOrNull { it.id == e.to } ?: return@forEach
                    val c = if (e.id == edgeSel) gold else muted
                    drawArrow(a, pos(a), b, pos(b), c, if (e.id == edgeSel) 3f else 2f)
                    if (e.label.isNotBlank()) {
                        val mid = (centerOf(a, pos(a)) + centerOf(b, pos(b))) / 2f
                        drawLabel(measurer, e.label, mid, 140f, TextStyle(fontSize = 11.sp, color = muted))
                    }
                }
                bp.nodes.forEach { n ->
                    val q = pos(n)
                    val fill = bpColor(n.color, dark).takeIf { it != Color.Unspecified } ?: surface
                    val sel = n.id == selected
                    val stroke = Stroke(if (sel) 3.5f else 2f, pathEffect = if (n.kind == "note") PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null)
                    val edge = if (sel) gold else when (n.kind) { "start" -> Ok; "end" -> Bad; "decision" -> Warn; else -> gold.copy(alpha = 0.75f) }
                    when (n.kind) {
                        "start", "end" -> {
                            drawOval(fill, q, Size(n.w, n.h)); drawOval(edge, q, Size(n.w, n.h), style = stroke)
                        }
                        "decision" -> {
                            val path = Path().apply {
                                moveTo(q.x + n.w / 2, q.y); lineTo(q.x + n.w, q.y + n.h / 2); lineTo(q.x + n.w / 2, q.y + n.h); lineTo(q.x, q.y + n.h / 2); close()
                            }
                            drawPath(path, fill); drawPath(path, edge, style = stroke)
                        }
                        "flow" -> {
                            // a parallelogram, like the sketch's slanted Flow box
                            val k = 14f
                            val path = Path().apply { moveTo(q.x + k, q.y); lineTo(q.x + n.w, q.y); lineTo(q.x + n.w - k, q.y + n.h); lineTo(q.x, q.y + n.h); close() }
                            drawPath(path, fill); drawPath(path, edge, style = stroke)
                        }
                        else -> {
                            drawRoundRect(fill, q, Size(n.w, n.h), androidx.compose.ui.geometry.CornerRadius(10f))
                            drawRoundRect(edge, q, Size(n.w, n.h), androidx.compose.ui.geometry.CornerRadius(10f), style = stroke)
                        }
                    }
                    val card = bp.cards.firstOrNull { it.node == n.id }
                    drawLabel(measurer, n.label.ifBlank { n.kind }, Offset(q.x + n.w / 2, q.y + n.h / 2 - if (card != null) 7f else 0f), n.w - 20f,
                        TextStyle(fontSize = 13.sp, color = ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
                    if (card != null) {
                        val col = bp.columns.firstOrNull { it.id == card.column }?.name ?: ""
                        drawLabel(measurer, col, Offset(q.x + n.w / 2, q.y + n.h / 2 + 12f), n.w - 20f, TextStyle(fontSize = 10.sp, color = gold, textAlign = TextAlign.Center))
                    }
                }
            }
        }

        // Selection toolbar
        val sel = bp.nodes.firstOrNull { it.id == selected }
        val edge = bp.edges.firstOrNull { it.id == edgeSel }
        Column(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
            if (connecting) Surface(shape = RoundedCornerShape(12.dp), color = Gold) {
                Text("Tap the node the arrow goes to", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (sel != null || edge != null) Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel != null) {
                    TextButton(onClick = { editing = sel }) { Text("Edit") }
                    TextButton(onClick = { connecting = true }) { Text("Connect") }
                    TextButton(onClick = {
                        actions.apply(arrayOf(BpOp.of("node.add", "kind" to "feature", "label" to "New step", "after" to sel.id))) { a ->
                            a.firstOrNull()?.optString("id")?.let { selected = it }
                        }
                    }) { Text("Add next") }
                    TextButton(onClick = { actions.apply(arrayOf(BpOp.of("node.remove", "id" to sel.id))) {}; selected = null }) { Text("Delete", color = Bad) }
                } else if (edge != null) {
                    Text("Arrow", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { actions.apply(arrayOf(BpOp.of("edge.remove", "id" to edge.id))) {}; edgeSel = null }) { Text("Delete", color = Bad) }
                }
            }
        }
        if (sel == null && edge == null) Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { pan = Offset.Unspecified }, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Icon(Glyphs.Refresh, "Fit to screen", Modifier.size(18.dp))
            }
            androidx.compose.material3.ExtendedFloatingActionButton(onClick = { adding = true }, containerColor = Gold, contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Glyphs.Plus, null) }, text = { Text("Node") })
        }
        if (bp.nodes.size <= 2 && bp.edges.size <= 1 && selected == null) Text(
            "Long-press and drag to move a box · tap to select · pinch to zoom",
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp).width(200.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { n -> NodeDialog(n, onDismiss = { editing = null }, onSave = { label, kind, note, color ->
        editing = null
        actions.apply(arrayOf(BpOp.of("node.update", "id" to n.id, "label" to label, "kind" to kind, "note" to note, "color" to color))) {}
    }) }
    if (adding) NodeDialog(null, onDismiss = { adding = false }, onSave = { label, kind, note, color ->
        adding = false
        actions.apply(arrayOf(BpOp.of("node.add", "label" to label, "kind" to kind, "note" to note, "color" to color,
            "after" to selected))) { a -> a.firstOrNull()?.optString("id")?.let { selected = it } }
    })
}

private fun centerOf(n: BpNode, at: Offset) = Offset(at.x + n.w / 2, at.y + n.h / 2)

private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/** Where the line from the centre towards [toward] leaves the node's box. */
private fun borderPoint(n: BpNode, at: Offset, toward: Offset): Offset {
    val c = centerOf(n, at)
    val d = toward - c
    if (d.x == 0f && d.y == 0f) return c
    val sx = if (d.x != 0f) (n.w / 2) / abs(d.x) else Float.MAX_VALUE
    val sy = if (d.y != 0f) (n.h / 2) / abs(d.y) else Float.MAX_VALUE
    return c + d * minOf(sx, sy)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(a: BpNode, ap: Offset, b: BpNode, bp: Offset, color: Color, width: Float) {
    val from = borderPoint(a, ap, centerOf(b, bp))
    val to = borderPoint(b, bp, centerOf(a, ap))
    drawLine(color, from, to, width)
    val ang = atan2(to.y - from.y, to.x - from.x)
    val len = 12f
    val p1 = Offset(to.x - len * cos(ang - 0.45f), to.y - len * sin(ang - 0.45f))
    val p2 = Offset(to.x - len * cos(ang + 0.45f), to.y - len * sin(ang + 0.45f))
    drawPath(Path().apply { moveTo(to.x, to.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close() }, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(m: TextMeasurer, text: String, center: Offset, maxW: Float, style: TextStyle) {
    val r = m.measure(text, style, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxW.coerceAtLeast(20f).toInt()))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

@Composable
private fun NodeDialog(n: BpNode?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf(n?.label ?: "") }
    var kind by remember { mutableStateOf(n?.kind ?: "feature") }
    var note by remember { mutableStateOf(n?.note ?: "") }
    var color by remember { mutableStateOf(n?.color ?: "default") }
    val dark = isDark()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (n == null) "New node" else "Edit node") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = label.trim().length > BpLimits.LABEL, supportingText = { LimitText(label, BpLimits.LABEL) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NodeKinds.forEach { (k, t) -> FilterChip(kind == k, { kind = k }, { Text(t) }, colors = themedChip()) }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BpPalette.forEach { c ->
                    Box(Modifier.size(28.dp).background(bpColor(c, dark).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(if (color == c) 2.dp else 1.dp, if (color == c) Gold else MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { color = c })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(label.trim(), kind, note.trim(), color) }, enabled = label.trim().length <= BpLimits.LABEL) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ================================================================ Board

/**
 * Columns side by side (scroll sideways), cards in each. Long-press a card and drag it to another
 * column or position; tap to open it. Filter by tag at the top.
 */
@Composable
private fun BoardView(bp: Blueprint, actions: BlueprintActions, openCard: String?, setOpenCard: (String?) -> Unit) {
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    var columnMenu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var limiting by remember { mutableStateOf<String?>(null) }
    var newColumn by remember { mutableStateOf(false) }
    // Drag state: the card, the finger in root coordinates, and every column's bounds to drop into.
    var dragging by remember { mutableStateOf<BpCard?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val colBounds = remember { mutableStateMapOf<String, Rect>() }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    val listBounds = remember { mutableStateMapOf<String, Rect>() }
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    var boardBounds by remember { mutableStateOf<Rect?>(null) }
    val dark = isDark()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // The gesture lambdas outlive recompositions: read the newest board and filter through these.
    val bpNow by rememberUpdatedState(bp)
    val tagNow by rememberUpdatedState(tag)
    fun shownIn(b: Blueprint, colId: String, t: String?) =
        b.cardsIn(colId).filter { t == null || (if (t == WAITING) it.openAsks.isNotEmpty() else t in it.tags) }
    // Where the dragged card would land: (column, slot among the column's shown cards without it).
    fun dropTarget(): Pair<String, Int>? {
        val c = dragging ?: return null
        val col = colBounds.entries.firstOrNull { (_, r) -> r.width > 0f && finger.x >= r.left && finger.x <= r.right }?.key ?: return null
        val others = shownIn(bpNow, col, tagNow).map { it.id }.filter { it != c.id }
        val onScreen = listStates[col]?.layoutInfo?.visibleItemsInfo?.mapNotNull { it.key as? String }?.toSet().orEmpty()
        val visible = others.filter { it in onScreen }.mapNotNull { id -> cardBounds[id]?.let { id to it.center.y } }.sortedBy { it.second }
        return col to dropSlot(others, visible, finger.y)
    }
    // Scroll while the finger holds near an edge: the column under it vertically, the board sideways.
    LaunchedEffect(dragging?.id) {
        if (dragging == null) return@LaunchedEffect
        val zone = with(density) { 64.dp.toPx() }
        val speed = with(density) { 12.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val f = finger
            boardBounds?.let { b -> edgeScroll(f.x, b.left, b.right, zone, speed).takeIf { it != 0f }?.let { scroll.dispatchRawDelta(it) } }
            dropTarget()?.first?.let { col ->
                val r = listBounds[col] ?: return@let
                edgeScroll(f.y, r.top, r.bottom, zone, speed).takeIf { it != 0f }?.let { listStates[col]?.dispatchRawDelta(it) }
            }
        }
    }
    // Column drag: long-press a column's header and drag it sideways.
    var draggingCol by remember { mutableStateOf<String?>(null) }
    var colFinger by remember { mutableStateOf(Offset.Zero) }
    var colStart by remember { mutableStateOf(Offset.Zero) }
    var colStartScroll by remember { mutableStateOf(0) }
    fun columnTarget(): Int? {
        val id = draggingCol ?: return null
        val centers = bpNow.columns.filter { it.id != id }.mapNotNull { c -> colBounds[c.id]?.let { it.center.x } }
        return columnSlot(centers, colFinger.x)
    }
    LaunchedEffect(draggingCol) {
        if (draggingCol == null) return@LaunchedEffect
        val zone = with(density) { 64.dp.toPx() }
        val speed = with(density) { 12.dp.toPx() }
        while (true) {
            withFrameNanos { }
            boardBounds?.let { b -> edgeScroll(colFinger.x, b.left, b.right, zone, speed).takeIf { it != 0f }?.let { scroll.dispatchRawDelta(it) } }
        }
    }
    val colTarget = columnTarget()
    val drop = dropTarget()
    val sticky = bp.effective.stickyAdd == true

    Column(Modifier.fillMaxSize()) {
        if (bp.tags.isNotEmpty() || bp.needsYou.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tag == null, { tag = null }, { Text("All") }, colors = themedChip())
            if (bp.needsYou.isNotEmpty()) FilterChip(tag == WAITING, { tag = if (tag == WAITING) null else WAITING }, { Text("Waiting for you ${bp.needsYou.size}", color = Warn) }, colors = themedChip())
            bp.tags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text("#$t") }, colors = themedChip()) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { boardBounds = it.boundsInRoot() }) {
            Row(Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                bp.columns.forEach { col ->
                    val cards = shownIn(bp, col.id, tag)
                    val target = drop?.first == col.id
                    val listState = listStates.getOrPut(col.id) { LazyListState() }
                    // The gold slot showing where the card lands; none when the drop would not move it.
                    val dragged = dragging
                    val placeAt = if (dragged == null || drop?.first != col.id) -1 else {
                        val others = cards.filter { it.id != dragged.id }
                        val all = bp.cardsIn(col.id).map { it.id }.filter { it != dragged.id }
                        val idx = fullIndex(others.map { it.id }, all, drop.second)
                        if (dragged.column == col.id && idx == bp.cardsIn(col.id).indexOfFirst { it.id == dragged.id }) -1
                        else others.getOrNull(drop.second)?.let { o -> cards.indexOfFirst { it.id == o.id } } ?: cards.size
                    }
                    val slotHeight = with(density) { (dragged?.let { cardBounds[it.id]?.height } ?: 0f).toDp() }.coerceAtLeast(48.dp)
                    val tint = bpColor(col.color, dark)
                    val movingCol = draggingCol == col.id
                    Surface(Modifier.width(280.dp).fillMaxHeight().onGloballyPositioned { if (!movingCol) colBounds[col.id] = it.boundsInRoot() }
                        .zIndex(if (movingCol) 5f else 0f)
                        .graphicsLayer {
                            if (movingCol) {
                                translationX = colFinger.x - colStart.x + (scroll.value - colStartScroll)
                                alpha = 0.92f; scaleX = 1.02f; scaleY = 1.02f
                            }
                        },
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        shadowElevation = if (movingCol) 12.dp else 0.dp,
                        border = if (target || movingCol) BorderStroke(2.dp, Gold) else null) {
                        Column {
                            Row(Modifier.fillMaxWidth()
                                .pointerInput(col.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            draggingCol = col.id; colStartScroll = scroll.value
                                            colFinger = (colBounds[col.id]?.topLeft ?: Offset.Zero) + at; colStart = colFinger
                                        },
                                        onDrag = { ch, d -> ch.consume(); colFinger += d },
                                        onDragEnd = {
                                            val id = draggingCol; val to = columnTarget(); draggingCol = null
                                            if (id != null && to != null && to != bpNow.columns.indexOfFirst { it.id == id })
                                                actions.apply(arrayOf(BpOp.of("column.move", "id" to id, "index" to to))) {}
                                        },
                                        onDragCancel = { draggingCol = null })
                                }
                                .padding(start = 12.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (tint != Color.Unspecified) Box(Modifier.size(10.dp).background(tint, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(col.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                val total = bp.cardsIn(col.id).size
                                Text(if (col.limit != null) "${cards.size}/${col.limit}" else "${cards.size}", style = MaterialTheme.typography.labelSmall,
                                    color = if (col.over(total)) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (col.over(total)) FontWeight.Bold else null)
                                Box {
                                    IconButton(onClick = { columnMenu = col.id }, Modifier.size(36.dp)) { Icon(Glyphs.More, "Column menu", Modifier.size(18.dp)) }
                                    DropdownMenu(columnMenu == col.id, { columnMenu = null }) {
                                        DropdownMenuItem({ Text("Rename") }, { columnMenu = null; renaming = col.id })
                                        DropdownMenuItem({ Text(if (col.limit != null) "Card limit (${col.limit})" else "Card limit") }, { columnMenu = null; limiting = col.id })
                                        val i = bp.columns.indexOf(col)
                                        if (i > 0) DropdownMenuItem({ Text("Move left") }, { columnMenu = null; actions.apply(arrayOf(BpOp.of("column.move", "id" to col.id, "index" to i - 1))) {} })
                                        if (i < bp.columns.size - 1) DropdownMenuItem({ Text("Move right") }, { columnMenu = null; actions.apply(arrayOf(BpOp.of("column.move", "id" to col.id, "index" to i + 1))) {} })
                                        BpPalette.chunked(5).forEach { row ->
                                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                row.forEach { c ->
                                                    Box(Modifier.size(22.dp).background(bpColor(c, dark).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                                        .border(1.dp, if (col.color == c) Gold else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                                        .clickable { columnMenu = null; actions.apply(arrayOf(BpOp.of("column.update", "id" to col.id, "color" to c))) {} })
                                                }
                                            }
                                        }
                                        if (bp.columns.size > 1) DropdownMenuItem({ Text("Delete column", color = Bad) }, {
                                            columnMenu = null; actions.apply(arrayOf(BpOp.of("column.remove", "id" to col.id))) {}
                                        })
                                    }
                                }
                            }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { listBounds[col.id] = it.boundsInRoot() },
                                state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(cards, key = { _, c -> c.id }) { i, card ->
                                    Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (i == placeAt) DropSlot(slotHeight)
                                    // Keep the dragged card composed when its column scrolls it away, or the drag would end.
                                    val pinnable = LocalPinnableContainer.current
                                    val isDragged = dragging?.id == card.id
                                    DisposableEffect(isDragged) {
                                        val handle = if (isDragged) pinnable?.pin() else null
                                        onDispose { handle?.release() }
                                    }
                                    DisposableEffect(card.id) { onDispose { cardBounds.remove(card.id) } }
                                    BoardCard(card, bp, dark, ghost = dragging?.id == card.id, unread = actions.unread(card),
                                        modifier = Modifier.onGloballyPositioned { cardBounds[card.id] = it.boundsInRoot() }
                                            .pointerInput(card.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { at -> dragging = card; finger = (cardBounds[card.id]?.topLeft ?: Offset.Zero) + at },
                                                    onDrag = { ch, d -> ch.consume(); finger += d },
                                                    onDragEnd = {
                                                        val c = dragging
                                                        val target = dropTarget()
                                                        dragging = null
                                                        if (c != null && target != null) {
                                                            val (dest, slot) = target
                                                            val b = bpNow
                                                            val all = b.cardsIn(dest).map { it.id }.filter { it != c.id }
                                                            val shown = shownIn(b, dest, tagNow).map { it.id }.filter { it != c.id }
                                                            val idx = fullIndex(shown, all, slot)
                                                            if (dest != c.column || idx != b.cardsIn(dest).indexOfFirst { it.id == c.id })
                                                                actions.apply(arrayOf(BpOp.of("card.move", "id" to c.id, "column" to dest, "index" to idx))) {}
                                                        }
                                                    },
                                                    onDragCancel = { dragging = null })
                                            },
                                        onOpen = { setOpenCard(card.id) })
                                    }
                                }
                                if (placeAt == cards.size) item(key = "drop-slot") { DropSlot(slotHeight, Modifier.animateItem()) }
                                if (!sticky) item {
                                    TextButton(onClick = { addingTo = col.id }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Glyphs.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add card")
                                    }
                                }
                            }
                            // Sticky: pinned under the list, reachable however long the column is.
                            if (sticky) TextButton(onClick = { addingTo = col.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Icon(Glyphs.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add card")
                            }
                        }
                    }
                }
                Surface(Modifier.width(200.dp).height(56.dp).clickable { newColumn = true }, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add column")
                    }
                }
            }
            // Where a dragged column will land: a gold bar between columns (drawn over them, so nothing shifts).
            draggingCol?.let { id ->
                val others = bp.columns.filter { it.id != id }
                val to = colTarget ?: return@let
                val gap = with(density) { 5.dp.toPx() }
                val x = (if (to < others.size) colBounds[others[to].id]?.left?.minus(gap) else others.lastOrNull()?.let { colBounds[it.id]?.right?.plus(gap) }) ?: return@let
                val left = boardBounds?.left ?: 0f
                Box(Modifier.fillMaxHeight().width(4.dp).offset { IntOffset((x - left - 2.dp.toPx()).roundToInt(), 0) }
                    .background(Gold, RoundedCornerShape(2.dp)))
            }
            // The card under the finger while dragging.
            dragging?.let { c ->
                val root = remember { mutableStateOf(Offset.Zero) }
                Box(Modifier.fillMaxSize().onGloballyPositioned { root.value = it.boundsInRoot().topLeft }) {
                    BoardCard(c, bp, dark, ghost = false, lifted = true, modifier = Modifier.width(260.dp).zIndex(10f)
                        .offset { IntOffset((finger.x - root.value.x - 130.dp.toPx()).roundToInt(), (finger.y - root.value.y - 30.dp.toPx()).roundToInt()) }, onOpen = {})
                }
            }
        }
    }
    addingTo?.let { colId ->
        TextDialog("New card in ${bp.columns.firstOrNull { it.id == colId }?.name}", "", "Title", onDismiss = { addingTo = null }, maxLength = BpLimits.TITLE) { t ->
            addingTo = null
            if (t.isNotBlank()) actions.apply(arrayOf(BpOp.of("card.add", "column" to colId, "title" to t, "tags" to listOfNotNull(tag?.takeIf { it != WAITING })))) {}
        }
    }
    renaming?.let { colId ->
        TextDialog("Rename column", bp.columns.firstOrNull { it.id == colId }?.name ?: "", "Name", onDismiss = { renaming = null }, maxLength = BpLimits.COLUMN) { t ->
            renaming = null; if (t.isNotBlank()) actions.apply(arrayOf(BpOp.of("column.update", "id" to colId, "name" to t))) {}
        }
    }
    limiting?.let { colId ->
        val col = bp.columns.firstOrNull { it.id == colId }
        TextDialog("Card limit for ${col?.name}", col?.limit?.toString() ?: "", "Max cards, empty = no limit", onDismiss = { limiting = null }) { t ->
            limiting = null
            val n = t.toIntOrNull()?.coerceIn(0, 999) ?: 0
            if (n != (col?.limit ?: 0)) actions.apply(arrayOf(BpOp.of("column.update", "id" to colId, "limit" to n))) {}
        }
    }
    if (newColumn) TextDialog("New column", "", "Name", onDismiss = { newColumn = false }, maxLength = BpLimits.COLUMN) { t ->
        newColumn = false; if (t.isNotBlank()) actions.apply(arrayOf(BpOp.of("column.add", "name" to t))) {}
    }
    openCard?.let { id -> bp.cards.firstOrNull { it.id == id }?.let { CardSheet(it, bp, actions, onDismiss = { setOpenCard(null) }) } ?: run { setOpenCard(null) } }
}

/** One board's settings: each one follows the global default (Settings > Blueprint boards) or is set here. */
@Composable
private fun BoardSettingsDialog(initial: BpSettings, global: BpSettings, onDismiss: () -> Unit, onGlobal: () -> Unit, onSave: (BpSettings) -> Unit) {
    var st by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Board settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoardSettingsFields(st, global) { st = it }
            Text("Card limit per column: column menu > Card limit. The count turns red when a column holds more.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onGlobal, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Icon(Glyphs.Settings, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Global board settings (every board)")
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(st) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun newCardsLabel(v: String?) = if (v == "top") "On top" else "At the bottom"
private fun agingLabel(d: Int?) = if (d == null || d == 0) "Off" else "$d days"

/**
 * The board settings choices. With [global] (a single board) each row starts with "Global (…)",
 * which follows the global default; without it (the global settings themselves) only real values.
 */
@Composable
internal fun BoardSettingsFields(st: BpSettings, global: BpSettings?, onChange: (BpSettings) -> Unit) {
    Text("New cards", style = MaterialTheme.typography.labelLarge)
    Text("Where a new card, or one moved into another column, lands. Dragging still drops it where you let go.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (global != null) FilterChip(st.newCards == null, { onChange(st.copy(newCards = null)) }, { Text("Global (${newCardsLabel(global.newCards)})") }, colors = themedChip())
        listOf("top", "bottom").forEach { v ->
            FilterChip(st.newCards == v || (global == null && st.newCards == null && v == "bottom"), { onChange(st.copy(newCards = v)) }, { Text(newCardsLabel(v)) }, colors = themedChip())
        }
    }
    Text("Card aging", style = MaterialTheme.typography.labelLarge)
    Text("Fade cards nobody has touched for a while, so stale work stands out. Cards waiting for you, with new comments or being worked on never fade.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (global != null) FilterChip(st.agingDays == null, { onChange(st.copy(agingDays = null)) }, { Text("Global (${agingLabel(global.agingDays)})") }, colors = themedChip())
        BpSettings.AGING_DAYS.forEach { d ->
            FilterChip(st.agingDays == d || (global == null && st.agingDays == null && d == 0), { onChange(st.copy(agingDays = d)) }, { Text(agingLabel(d)) }, colors = themedChip())
        }
    }
    Text("Add card button", style = MaterialTheme.typography.labelLarge)
    Text("Sticky keeps \"+ Add card\" pinned at the bottom of each column instead of after the last card.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fun label(v: Boolean?) = if (v == true) "Sticky" else "After the cards"
        if (global != null) FilterChip(st.stickyAdd == null, { onChange(st.copy(stickyAdd = null)) }, { Text("Global (${label(global.stickyAdd)})") }, colors = themedChip())
        listOf(true, false).forEach { v ->
            FilterChip(st.stickyAdd == v || (global == null && st.stickyAdd == null && !v), { onChange(st.copy(stickyAdd = v)) }, { Text(label(v)) }, colors = themedChip())
        }
    }
}

/** Where a dragged card will land: an empty card-sized slot with a gold outline. */
@Composable
private fun DropSlot(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height)
        .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
        .border(BorderStroke(2.dp, Gold.copy(alpha = 0.7f)), RoundedCornerShape(12.dp)))
}

@Composable
private fun BoardCard(card: BpCard, bp: Blueprint, dark: Boolean, ghost: Boolean, modifier: Modifier, lifted: Boolean = false, unread: Int = 0, onOpen: () -> Unit) {
    val tint = bpColor(card.color, dark)
    val asks = card.openAsks
    val working = card.agent != null
    // Waiting for the user beats "in progress": a pulsing amber border; an agent at work gets a gold one.
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "card")
    val glow by pulse.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    val border = when {
        lifted -> BorderStroke(2.dp, Gold)
        asks.isNotEmpty() -> BorderStroke(2.dp, Warn.copy(alpha = glow))
        working -> BorderStroke(1.5.dp, Gold.copy(alpha = 0.4f + glow * 0.6f))
        else -> null
    }
    // Card aging: untouched cards fade (board setting), unless something about them needs attention.
    val aged = if (lifted || asks.isNotEmpty() || working || unread > 0) 1f
        else agingAlpha(card.updated, System.currentTimeMillis() / 1000.0, bp.effective.agingDays ?: 0)
    Surface(modifier.fillMaxWidth().graphicsLayer { alpha = aged }.clickable(onClick = onOpen), shape = RoundedCornerShape(12.dp),
        color = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = if (lifted) 10.dp else 0.dp, border = border) {
        Column(Modifier.padding(10.dp).then(if (ghost) Modifier.background(Color.Transparent) else Modifier)) {
            val alpha = if (ghost) 0.35f else 1f
            if (asks.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp).background(Warn.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (asks.any { it.kind == "review" }) "Review needed" else "Question for you", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = Warn)
            } else if (working) Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Gold, pulse = true)
                Spacer(Modifier.width(6.dp))
                Text("${card.agent} is working on this", style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (unread > 0) Row(Modifier.padding(bottom = 6.dp).background(Gold.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Gold, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(if (unread == 1) "1 new comment" else "$unread new comments", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = Gold)
            }
            if (card.tags.isNotEmpty() || card.priority != null) Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                card.priority?.let { p ->
                    val c = when (p) { "high" -> Bad; "medium" -> Warn; else -> Ok }
                    Box(Modifier.height(6.dp).width(28.dp).background(c.copy(alpha = alpha), RoundedCornerShape(3.dp)).align(Alignment.CenterVertically))
                }
                card.tags.take(3).forEach { t -> Text("#$t", style = MaterialTheme.typography.labelSmall, color = Gold.copy(alpha = alpha), maxLines = 1) }
            }
            Text(card.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            val (done, total) = card.progress
            val meta = buildList {
                if (total > 0) add("☑ $done/$total")
                card.due?.let { add("⏰ $it") }
                card.assignee?.let { add(if (it == "hermes") "✦ Hermes" else "● Me") }
                if (card.comments.isNotEmpty()) add("💬 ${card.comments.size}")
                if (card.files.isNotEmpty()) add("📎 ${card.files.size}")
                card.node?.let { id -> bp.nodes.firstOrNull { it.id == id }?.let { add("◇ ${it.label}") } }
            }
            if (meta.isNotEmpty()) Text(meta.joinToString("   "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, maxLength: Int? = null, onDone: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    val over = maxLength != null && t.trim().length > maxLength
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, placeholder = { Text(hint) }, singleLine = maxLength == null, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            isError = over, supportingText = maxLength?.let { m -> { LimitText(t, m) } }) },
        confirmButton = { TextButton(onClick = { onDone(t.trim()) }, enabled = !over) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** "123 / 600", red with "too long" once over: shown under a limited field instead of cutting the text. */
@Composable
private fun LimitText(text: String, max: Int) {
    val n = text.trim().length
    if (n < max * 0.8) return
    Text(if (n > max) "$n / $max · too long, shorten it to save" else "$n / $max", style = MaterialTheme.typography.labelSmall,
        color = if (n > max) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A card in full: title, description, checklist, tags, column, priority, due date, who's on it, linked diagram node. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardSheet(card: BpCard, bp: Blueprint, actions: BlueprintActions, onDismiss: () -> Unit) {
    // Open = read; a comment arriving while it is open is read too.
    androidx.compose.runtime.LaunchedEffect(card.id, card.comments.size) { actions.markSeen(card) }
    var title by remember(card.id) { mutableStateOf(card.title) }
    var body by remember(card.id) { mutableStateOf(card.body) }
    var newItem by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    var due by remember(card.id) { mutableStateOf(card.due ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    fun upd(vararg kv: Pair<String, Any?>) = actions.apply(arrayOf(BpOp.of("card.update", "id" to card.id, *kv))) {}
    fun saveText() {
        if (title.isNotBlank() && title.trim().length <= BpLimits.TITLE && title != card.title) upd("title" to title.trim())
        if (body != card.body) upd("body" to body)
        val d = due.trim()
        if (d != (card.due ?: "") && (d.isEmpty() || Regex("""\d{4}-\d{2}-\d{2}""").matches(d))) upd("due" to d.ifEmpty { null })
    }
    BackHandler { saveText(); onDismiss() }
    ModalBottomSheet(onDismissRequest = { saveText(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium,
                isError = title.trim().length > BpLimits.TITLE, supportingText = { LimitText(title, BpLimits.TITLE) })
            SectionLabel("Column")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bp.columns.forEach { c -> FilterChip(card.column == c.id, { actions.apply(arrayOf(BpOp.of("card.move", "id" to card.id, "column" to c.id))) {} }, { Text(c.name) }, colors = themedChip()) }
            }
            DescriptionEditor(body, { body = it })
            CardFiles(card.files, actions, onChange = { upd("files" to it) })
            SectionLabel("Checklist" + card.progress.let { (d, t) -> if (t > 0) "  $d/$t" else "" })
            card.checklist.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.done, { d -> upd("checklist" to BpOp.checklist(card.checklist.mapIndexed { j, it -> if (j == i) it.copy(done = d) else it })) })
                    Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    IconButton(onClick = { upd("checklist" to BpOp.checklist(card.checklist.filterIndexed { j, _ -> j != i })) }) { Icon(Glyphs.Close, "Remove item", Modifier.size(16.dp)) }
                }
            }
            OutlinedTextField(newItem, { newItem = it }, placeholder = { Text("Add an item") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (newItem.isNotBlank()) TextButton(onClick = {
                    upd("checklist" to BpOp.checklist(card.checklist + BpCheck(newItem.trim(), false))); newItem = "" }) { Text("Add") } })
            SectionLabel("Tags")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                card.tags.forEach { t -> FilterChip(true, { upd("tags" to card.tags - t) }, { Text("#$t  ×") }, colors = themedChip()) }
                (bp.tags - card.tags.toSet()).take(8).forEach { t -> FilterChip(false, { upd("tags" to card.tags + t) }, { Text("#$t") }, colors = themedChip()) }
            }
            OutlinedTextField(newTag, { newTag = it.replace(" ", "-").take(40) }, placeholder = { Text("New tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (newTag.isNotBlank()) TextButton(onClick = { upd("tags" to (card.tags + newTag.trim('#'))); newTag = "" }) { Text("Add") } })
            SectionLabel("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "None", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (k, t) ->
                    FilterChip(card.priority == k, { upd("priority" to k) }, { Text(t) }, colors = themedChip())
                }
            }
            SectionLabel("Who's on it")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Nobody", "me" to "Me", "hermes" to "Hermes").forEach { (k, t) ->
                    FilterChip(card.assignee == k, { upd("assignee" to k) }, { Text(t) }, colors = themedChip())
                }
            }
            OutlinedTextField(due, { due = it.take(10) }, label = { Text("Due date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = due.isNotBlank() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(due))
            SectionLabel("Diagram node")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(card.node == null, { upd("node" to null) }, { Text("None") }, colors = themedChip())
                bp.nodes.filter { it.kind != "start" && it.kind != "end" }.forEach { n ->
                    FilterChip(card.node == n.id, { upd("node" to n.id) }, { Text(n.label.ifBlank { n.kind }) }, colors = themedChip())
                }
            }
            CommentsSection(card, bp, actions, onAskHermes = { saveText(); actions.askHermes(bp, "reply:" + card.id); onDismiss() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { saveText(); actions.askHermes(bp, "card:" + card.title); onDismiss() }) {
                    Icon(Glyphs.Spark, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Ask Hermes to do this")
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Bad) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this card?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.apply(arrayOf(BpOp.of("card.remove", "id" to card.id))) {}; onDismiss() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

/** Marks the "Waiting for you" board filter (not a real tag). */
private const val WAITING = "\u0000waiting"

/**
 * A card's comments, oldest first. Questions and review requests (from Hermes or anyone) stay
 * highlighted until resolved; answering one resolves it. Files attached on the PC open or save here.
 */
@Composable
private fun CommentsSection(card: BpCard, bp: Blueprint, actions: BlueprintActions, onAskHermes: () -> Unit) {
    var draft by remember(card.id) { mutableStateOf("") }
    /** Files picked for the comment being written (PC paths, already uploaded). */
    var pendingFiles by remember(card.id) { mutableStateOf(listOf<String>()) }
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val asks = card.openAsks
    SectionLabel("Comments" + if (card.comments.isNotEmpty()) "  ${card.comments.size}" else "")
    card.comments.forEach { m ->
        val open = m.needsYou
        Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            color = if (open) Warn.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (open) BorderStroke(1.dp, Warn) else null) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val who = when (m.by) { "hermes" -> "Hermes"; "phone" -> "You"; else -> m.by.replaceFirstChar { it.uppercase() } }
                    Text(who, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (m.by == "hermes") Gold else MaterialTheme.colorScheme.onSurface)
                    if (m.kind != "comment") {
                        Spacer(Modifier.width(6.dp))
                        Text(when { m.resolved -> "resolved"; m.kind == "review" -> "needs review"; else -> "asks you" },
                            style = MaterialTheme.typography.labelSmall, color = if (open) Warn else Ok)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(fmt.format(java.util.Date((m.at * 1000).toLong())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
                if (m.files.isNotEmpty()) AttachmentPreviews(m.files, Modifier.padding(top = 2.dp))
                if (m.kind != "comment") Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { actions.apply(arrayOf(BpOp.of("comment.resolve", "id" to card.id, "comment_id" to m.id, "resolved" to !m.resolved))) {} }) {
                        Text(if (m.resolved) "Reopen" else if (m.kind == "review") "Mark reviewed" else "Mark answered")
                    }
                }
            }
        }
    }
    if (pendingFiles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pendingFiles.forEach { f -> FilterChip(true, { pendingFiles = pendingFiles - f }, { Text(io.github.nideta231.hermesremote.data.fileName(f) + "  ×") }, colors = themedChip()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AttachButton(actions, "Attach to comment") { pendingFiles = (pendingFiles + it).distinct().take(10) }
        OutlinedTextField(draft, { draft = it }, placeholder = { Text(if (asks.isNotEmpty()) "Answer Hermes…" else "Add a comment") },
            minLines = 1, maxLines = 6, modifier = Modifier.weight(1f),
            trailingIcon = { if (draft.isNotBlank() || pendingFiles.isNotEmpty()) TextButton(onClick = {
                actions.apply(arrayOf(BpOp.of("card.comment", "id" to card.id, "text" to draft.trim(), "files" to pendingFiles, "resolves" to asks.isNotEmpty()))) {}
                draft = ""; pendingFiles = emptyList()
            }) { Text(if (asks.isNotEmpty()) "Answer" else "Send") } })
    }
    if (card.comments.any { it.by == "phone" } && card.comments.lastOrNull()?.by == "phone")
        TextButton(onClick = onAskHermes) {
            Icon(Glyphs.Spark, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Tell Hermes I replied")
        }
}

/** Ready-made rules for the agent instructions dialog, short enough to read at a glance. */
internal val InstructionTemplates = listOf(
    "Read the project docs first.",
    "Work on the Working column first. Ask me which Todo card to take, unless I said to finish them all.",
    "Always attach the result (file, screenshot or report) to the card when work is done.",
    "Check for open pull requests first and put them at the top of Todo.",
    "Never touch LIVE unless I ask.",
    "Ask on the card before anything risky.",
    "Comment on each card how it was tested and what was not checked.",
    "Commit and push after every finished card; release once at the end.",
    "Do every Todo card, then report on Telegram.",
)

/**
 * Standing rules for any agent on this project; sent with every ask and shown first in Hermes' view of the
 * blueprint. A board with no rules of its own follows the global ones ([global], set in Settings); *Use global*
 * clears this board's text so it follows them again.
 */
@Composable
private fun InstructionsDialog(initial: String, handOver: Boolean, global: String?, onGlobal: () -> Unit,
                               onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (handOver) "Hand control to Hermes" else "Agent instructions") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rules for this project, followed every time Hermes works on it. Saved with the blueprint.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (global != null) GlobalInstructionsNote(global, usingGlobal = text.isBlank(), onUseGlobal = { text = "" }, onEdit = onGlobal)
            InstructionsEditor(text, { text = it }, if (global.isNullOrBlank()) "e.g. Never deploy to LIVE unless I ask. Read the docs folder first. Report on Telegram when a card is done."
                else "Empty: this board follows the global instructions above.")
        }
    }, confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(if (handOver) "Save and hand over" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** What the global instructions say, whether this board follows them, and a way back to them. */
@Composable
private fun GlobalInstructionsNote(global: String, usingGlobal: Boolean, onUseGlobal: () -> Unit, onEdit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (usingGlobal) "Following the global instructions" else "Global instructions (not used: this board has its own)",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (usingGlobal) Gold else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Text(global.ifBlank { "None set yet." }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!usingGlobal) TextButton(onClick = onUseGlobal) { Text("Reset to global") }
                TextButton(onClick = onEdit) { Text("Edit global") }
            }
        }
    }
}

/** The instructions text box plus the tap-to-toggle templates (board dialog and Settings share it). */
@Composable
internal fun InstructionsEditor(text: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(text, onChange, minLines = 5, modifier = Modifier.fillMaxWidth(), placeholder = { Text(placeholder) })
    Text("Templates (tap to add or remove)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InstructionTemplates.forEach { s ->
            FilterChip(s in text, { onChange(toggleTemplate(text, s)) }, { Text(s) }, colors = themedChip())
        }
    }
}

/** Add [s] on its own line, or take it out (and the blank line it leaves). */
internal fun toggleTemplate(text: String, s: String): String =
    if (s in text) text.replace(s, "").replace(Regex("\n{2,}"), "\n").trim() else (text.trim() + "\n" + s).trim()

// ================================================================ Activity

@Composable
private fun ActivityView(bp: Blueprint) {
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(bp.log.reversed()) { l ->
            Row(verticalAlignment = Alignment.Top) {
                val who = when (l.by) { "hermes" -> "Hermes"; "phone" -> "You"; else -> l.by.replaceFirstChar { it.uppercase() } }
                Text(who, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (l.by == "hermes") Gold else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.text, style = MaterialTheme.typography.bodyMedium)
                    Text(fmt.format(java.util.Date((l.at * 1000).toLong())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


// ================================================================ Inbox

/** One thing on the board for the user: a card with Hermes comments not seen yet, or an open question/review. */
internal data class InboxItem(val card: BpCard, val unread: Int, val asks: Int, val at: Double, val by: String, val text: String)

/** Cards that want the user's attention, newest first: open questions/reviews and unread Hermes comments. */
internal fun inboxItems(bp: Blueprint, unread: (BpCard) -> Int): List<InboxItem> = bp.cards.mapNotNull { c ->
    val u = unread(c)
    val asks = c.openAsks.size
    if (u == 0 && asks == 0) return@mapNotNull null
    val last = (c.openAsks.lastOrNull().takeIf { u == 0 } ?: c.comments.lastOrNull { it.by != "phone" }) ?: c.comments.lastOrNull()
    InboxItem(c, u, asks, last?.at ?: c.updated, last?.by.orEmpty(), last?.text.orEmpty())
}.sortedByDescending { it.at }

/**
 * Bell in the blueprint header: every card with unread Hermes comments or an open question/review, so it
 * is clear which card the notification was about. Tap opens the card; *Mark all read* clears every
 * unread badge (open questions stay until answered, they are waiting for you).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSheet(bp: Blueprint, actions: BlueprintActions, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val items = inboxItems(bp, actions.unread)
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val cols = bp.columns.associate { it.id to it.name }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Comments and questions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val u = items.count { it.unread > 0 }; val a = items.count { it.asks > 0 }
                Text(listOfNotNull(u.takeIf { it > 0 }?.let { "$it with new comments" }, a.takeIf { it > 0 }?.let { "$it waiting for you" })
                    .joinToString(" · ").ifEmpty { "Nothing new" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.any { it.unread > 0 }) TextButton(onClick = { bp.cards.forEach(actions.markSeen) }) { Text("Mark all read") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) item {
                Text("No unread comments and no open questions.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(items, key = { it.card.id }) { it ->
                Surface(onClick = { onOpen(it.card.id) }, shape = RoundedCornerShape(12.dp),
                    color = if (it.asks > 0) Warn.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (it.asks > 0) Pill(if (it.asks == 1) "Waiting for you" else "${it.asks} waiting", tint = Warn)
                            if (it.unread > 0) Pill(if (it.unread == 1) "1 new" else "${it.unread} new", tint = Gold)
                            Text(cols[it.card.column].orEmpty(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Text(it.card.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (it.text.isNotBlank()) Text((if (it.by == "hermes") "Hermes: " else if (it.by.isNotEmpty()) "${it.by}: " else "") + it.text,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (it.at > 0) Text(fmt.format(java.util.Date((it.at * 1000).toLong())), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


// ================================================================ Description + attachments

/**
 * The card description as a small rich text editor: shown formatted (Markdown) and tap *Edit* for a
 * toolbar that formats the selection (bold, italic, heading, list, checklist, code, link). The text
 * stays Markdown, so Hermes reads exactly what the board shows.
 */
@Composable
private fun DescriptionEditor(body: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(body.isBlank()) }
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(body)) }
    if (value.text != body && !editing) value = androidx.compose.ui.text.input.TextFieldValue(body)
    fun set(v: androidx.compose.ui.text.input.TextFieldValue) { value = v; onChange(v.text) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Description")
        Spacer(Modifier.weight(1f))
        if (body.isNotBlank()) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
    }
    if (!editing) {
        Surface(onClick = { editing = true }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            MarkdownText(body, Modifier.padding(12.dp))
        }
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        @Composable fun tool(label: String, desc: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge, act: () -> Unit) =
            Surface(onClick = act, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.semantics { contentDescription = desc }) {
                Text(label, style = style, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        tool("B", "Bold", MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)) { set(RichEdit.wrap(value, "**")) }
        tool("I", "Italic", MaterialTheme.typography.labelLarge.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { set(RichEdit.wrap(value, "*")) }
        tool("H", "Heading") { set(RichEdit.prefixLines(value, "## ")) }
        tool("•", "Bulleted list") { set(RichEdit.prefixLines(value, "- ")) }
        tool("1.", "Numbered list") { set(RichEdit.prefixLines(value, "1. ")) }
        tool("☐", "Checklist") { set(RichEdit.prefixLines(value, "- [ ] ")) }
        tool("❝", "Quote") { set(RichEdit.prefixLines(value, "> ")) }
        tool("</>", "Code") { set(RichEdit.wrap(value, "`")) }
        tool("Link", "Link") { set(RichEdit.link(value)) }
    }
    OutlinedTextField(value, { set(it) }, placeholder = { Text("What needs doing, details, links…") }, minLines = 4, modifier = Modifier.fillMaxWidth())
}

/** Files attached to the card itself (open/save/info like chat files); *Attach* sends one from the phone. */
@Composable
private fun CardFiles(files: List<String>, actions: BlueprintActions, onChange: (List<String>) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Attachments" + if (files.isNotEmpty()) "  ${files.size}" else "")
        Spacer(Modifier.width(8.dp))
        Text("up to ${io.github.nideta231.hermesremote.data.FileAttachments.MAX_BYTES / (1024 * 1024)} MB per file",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        AttachButton(actions, "Attach file or picture", label = "Attach") { onChange((files + it).distinct()) }
    }
    files.forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachmentPreviews(listOf(f), Modifier.weight(1f))
            IconButton(onClick = { onChange(files - f) }) { Icon(Glyphs.Close, "Remove attachment", Modifier.size(16.dp)) }
        }
    }
}

/** Pick any file or picture on the phone and upload it to the PC; [onUploaded] gets its PC path. */
@Composable
private fun AttachButton(actions: BlueprintActions, desc: String, label: String? = null, onUploaded: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch { try { actions.upload(uri.toString())?.let(onUploaded) } finally { busy = false } }
    }
    if (label == null) IconButton(onClick = { if (!busy) pick.launch("*/*") }) {
        if (busy) TypingDots(Gold) else Icon(Glyphs.Paperclip, desc, Modifier.size(20.dp))
    } else TextButton(onClick = { if (!busy) pick.launch("*/*") }) {
        if (busy) TypingDots(Gold) else { Icon(Glyphs.Paperclip, desc, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
    }
}
