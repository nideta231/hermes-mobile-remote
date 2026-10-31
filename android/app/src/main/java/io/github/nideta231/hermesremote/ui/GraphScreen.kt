package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.data.ChatGraph
import io.github.nideta231.hermesremote.data.ChatGraphBuilder
import io.github.nideta231.hermesremote.data.GraphKind
import io.github.nideta231.hermesremote.data.GraphNode
import io.github.nideta231.hermesremote.data.GraphOptions
import io.github.nideta231.hermesremote.data.GraphSim
import io.github.nideta231.hermesremote.data.ProjectRef
import io.github.nideta231.hermesremote.data.SessionSummary
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val ProjectColor = Color(0xFFE8B54A)
private val TopicColor = Color(0xFFB48CF2)
private val ModelColor = Color(0xFF4FC3A1)

/** Milliseconds between two dots appearing, and how long each one's pop takes. */
private const val REVEAL_STEP_MS = 22L
private const val POP_MS = 420f

/**
 * Obsidian-style graph of the chats: each chat is a dot, joined to its project, to the topics its
 * title shares with other chats and (optionally) its model. The dots pop in one by one, newest chat
 * first, and fly apart into clusters as the layout settles. Pinch / drag to move around, drag a dot
 * to pull it, tap a dot to light up its links, tap a chat's label to open it.
 */
@Composable
fun GraphScreen(sessions: List<SessionSummary>, projects: List<ProjectRef>, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    var showProjects by rememberSaveable { mutableStateOf(true) }
    var showTopics by rememberSaveable { mutableStateOf(true) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val graph = remember(sessions, projects, showProjects, showTopics, showModels) {
        ChatGraphBuilder.build(sessions, projects, GraphOptions(showProjects, showTopics, showModels))
    }
    PageScaffold("Graph", onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(showProjects, { showProjects = !showProjects }, { Legend(ProjectColor, "Projects") }, colors = themedChip())
                FilterChip(showTopics, { showTopics = !showTopics }, { Legend(TopicColor, "Topics") }, colors = themedChip())
                FilterChip(showModels, { showModels = !showModels }, { Legend(ModelColor, "Models") }, colors = themedChip())
                Text("${graph.nodes.count { it.kind == GraphKind.CHAT }} chats · ${graph.edges.size} links",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (graph.nodes.isEmpty()) Text("No chats to map yet.", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val chatColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                    GraphCanvas(graph, colorOf = { n -> when (n.kind) {
                        GraphKind.PROJECT -> ProjectColor; GraphKind.TOPIC -> TopicColor; GraphKind.MODEL -> ModelColor; else -> chatColor } },
                        kindLabel = { n -> when (n.kind) { GraphKind.CHAT -> "Chat"; GraphKind.PROJECT -> "Project"; GraphKind.TOPIC -> "Topic"; else -> "Model" } },
                        openLabel = { n -> if (n.kind == GraphKind.CHAT) "Open" else null }, onOpen = { onOpenChat(it.ref) })
                }
            }
        }
    }
}

@Composable
internal fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/**
 * The force-laid-out map shared by the chat graph and the knowledge map. [colorOf] colours a dot,
 * [sizeOf] scales it (1 = normal), [kindLabel] names it in the selection card, and [openLabel] (null: none)
 * is the selection card's action, which calls [onOpen]. Leaves ([isLeaf]) show their label only zoomed in.
 */
@Composable
internal fun GraphCanvas(
    graph: ChatGraph,
    colorOf: (GraphNode) -> Color,
    kindLabel: (GraphNode) -> String,
    openLabel: (GraphNode) -> String?,
    onOpen: (GraphNode) -> Unit,
    sizeOf: (GraphNode) -> Float = { 1f },
    isLeaf: (GraphNode) -> Boolean = { it.kind == GraphKind.CHAT },
) {
    val sim = remember(graph) { GraphSim(graph) }
    val accent = Gold
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val density = LocalDensity.current
    // Reveal order: hubs (projects/topics) first so chats have somewhere to fly to, then chats newest first.
    val order = remember(graph) {
        graph.nodes.indices.sortedWith(compareBy<Int> { if (isLeaf(graph.nodes[it])) 1 else 0 }
            .thenByDescending { graph.degree[it] }.thenByDescending { graph.nodes[it].at })
    }
    val revealAt = remember(graph) { LongArray(graph.nodes.size).also { r -> order.forEachIndexed { k, i -> r[i] = k * REVEAL_STEP_MS } } }
    var tick by remember(graph) { mutableLongStateOf(0L) }
    var elapsed by remember(graph) { mutableLongStateOf(0L) }
    var scale by remember(graph) { mutableFloatStateOf(1f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    var selected by remember(graph) { mutableIntStateOf(-1) }
    var fitted by remember(graph) { mutableStateOf(false) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val revealDone = (order.size * REVEAL_STEP_MS + POP_MS.toLong())

    // The animation loop: a step of the layout and a frame per vsync, until it settles and every dot is in.
    LaunchedEffect(graph) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                elapsed = (now - start) / 1_000_000
                if (!sim.settled) { sim.step(); sim.step() }
                // Keep the whole map on screen while it is still spreading out (until the user moves it).
                if (!fitted && size.width > 0f) {
                    val b = sim.bounds()
                    val w = max(b[2] - b[0], 1f) + 80f; val h = max(b[3] - b[1], 1f) + 80f
                    val target = min(size.width / w, size.height / h).coerceIn(0.25f, 2.2f)
                    scale += (target - scale) * 0.08f
                    val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
                    pan += (Offset(-cx * scale, -cy * scale) - pan) * 0.08f
                }
                tick++
            }
            // Settled and fully revealed: stop burning frames; a drag reheats the layout and the loop picks up again.
            if (sim.settled && elapsed > revealDone && (fitted || elapsed > revealDone + 1500)) kotlinx.coroutines.delay(48)
        }
    }

    val latestOpen by rememberUpdatedState(onOpen)
    val hitR = with(density) { 22.dp.toPx() }
    fun toWorld(p: Offset): Offset = Offset((p.x - size.width / 2 - pan.x) / scale, (p.y - size.height / 2 - pan.y) / scale)

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().pointerInput(graph) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = toWorld(down.position)
                val grabbed = sim.hit(w.x, w.y, hitR / scale)
                var moved = false
                var multi = false
                if (grabbed >= 0) { sim.pinned = grabbed; sim.reheat(0.35f) }
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.none { it.pressed }) break
                    if (e.changes.size > 1) {
                        multi = true; fitted = true; sim.pinned = -1
                        val z = e.calculateZoom(); val c = e.calculateCentroid(useCurrent = true)
                        val newScale = (scale * z).coerceIn(0.15f, 5f)
                        val focus = c - Offset(size.width / 2, size.height / 2)
                        pan = focus - (focus - pan) * (newScale / scale) + e.calculatePan()
                        scale = newScale
                        e.changes.forEach { it.consume() }
                        continue
                    }
                    val ch = e.changes.first()
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (moved && !multi) {
                        if (grabbed >= 0) {
                            val p = toWorld(ch.position); sim.move(grabbed, p.x, p.y); sim.reheat(0.3f)
                        } else { pan += ch.positionChange(); fitted = true }
                        ch.consume()
                    }
                }
                sim.pinned = -1
                if (!moved && !multi) {
                    selected = if (grabbed >= 0 && selected != grabbed) grabbed else if (grabbed >= 0) grabbed else -1
                }
            }
        }) {
            size = this.size
            @Suppress("UNUSED_EXPRESSION") tick  // redraw every frame of the simulation
            val cx = size.width / 2 + pan.x; val cy = size.height / 2 + pan.y
            fun sx(i: Int) = cx + sim.x[i] * scale
            fun sy(i: Int) = cy + sim.y[i] * scale
            fun pop(i: Int): Float {
                val t = ((elapsed - revealAt[i]) / POP_MS).coerceIn(0f, 1f)
                if (t <= 0f) return 0f
                // Overshooting ease-out (back): the dot pops a little past full size, then settles.
                val s = 1.70158f; val u = t - 1
                return 1 + u * u * ((s + 1) * u + s)
            }
            val focus = selected
            val near = if (focus >= 0) graph.neighbors[focus] else emptySet()
            // Links fade in once both ends are out.
            for (e in graph.edges) {
                val a = min(pop(e.a), pop(e.b)).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val lit = focus >= 0 && (e.a == focus || e.b == focus)
                val dim = focus >= 0 && !lit
                drawLine(if (lit) accent else lineColor, Offset(sx(e.a), sy(e.a)), Offset(sx(e.b), sy(e.b)),
                    strokeWidth = (if (lit) 1.6f else 0.9f) * density.density,
                    alpha = a * when { lit -> 0.9f; dim -> 0.06f; else -> 0.28f })
            }
            for (i in graph.nodes.indices) {
                val p = pop(i)
                if (p <= 0f) continue
                val node = graph.nodes[i]
                val leaf = isLeaf(node)
                val base = (if (leaf) 3.2f else 4.5f) + sqrt(graph.degree[i].toFloat()) * 1.4f
                val r = base * sizeOf(node) * density.density * p * scale.coerceIn(0.6f, 1.6f)
                val color = colorOf(node)
                val dim = focus >= 0 && i != focus && i !in near
                val alpha = (if (dim) 0.15f else 1f) * p.coerceAtMost(1f)
                val c = Offset(sx(i), sy(i))
                if (i == focus) drawCircle(accent, r + 5 * density.density, c, alpha = 0.25f)
                drawCircle(color, r, c, alpha = alpha)
                if (i == focus) drawCircle(accent, r + 2 * density.density, c, style = Stroke(1.5f * density.density))
                // Labels: hubs always, chats once zoomed in, and the lit neighbourhood.
                val showLabel = !dim && p >= 1f && (!leaf && scale > 0.45f || scale > 1.25f || i == focus || i in near)
                if (showLabel) {
                    val text = measurer.measure(node.label, TextStyle(color = labelColor, fontSize = if (leaf) 10.sp else 11.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (160 * density.density).toInt()))
                    drawText(text, topLeft = Offset(c.x - text.size.width / 2, c.y + r + 3 * density.density),
                        alpha = if (focus >= 0 || !leaf) 0.95f else 0.7f)
                }
            }
        }
        // The selected dot: what it is, and Open for a chat.
        AnimatedVisibility(selected >= 0, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val i = selected.coerceIn(0, max(graph.nodes.size - 1, 0))
            val node = graph.nodes.getOrNull(i)
            if (node != null) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(node.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(kindLabel(node) + " · ${graph.degree[i]} links", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    openLabel(node)?.let { l -> TextButton(onClick = { latestOpen(node) }) { Text(l) } }
                    TextButton(onClick = { selected = -1 }) { Text("Close") }
                }
            }
        }
        if (fitted) TextButton(onClick = { fitted = false }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) { Text("Fit") }
    }
}
