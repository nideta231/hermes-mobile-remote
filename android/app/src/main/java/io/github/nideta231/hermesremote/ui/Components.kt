package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Line icons drawn from SVG paths (24×24), so the app needs no icon dependency beyond the core set. */
object Glyphs {
    private fun line(vararg paths: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            paths.forEach { p ->
                addPath(PathParser().parsePathString(p).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    val Menu = line("M4 7h16", "M4 12h10", "M4 17h16")
    val Compose = line("M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Desktop = line("M3 4h18v12H3z", "M8 20h8", "M12 16v4")
    val Settings = line("M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val Wifi = line("M5 12.5a10 10 0 0 1 14 0", "M8.5 16a5 5 0 0 1 7 0", "M2 9a15 15 0 0 1 20 0", "M12 19.5h.01")
    val Globe = line("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M3 12h18", "M12 3a14 14 0 0 1 0 18", "M12 3a14 14 0 0 0 0 18")
    val Offline = line("M2 2l20 20", "M8.5 16a5 5 0 0 1 7 0", "M5 12.5a10 10 0 0 1 4.7-2.6", "M12 19.5h.01", "M16.7 10.7A10 10 0 0 1 19 12.5")
    val Copy = line("M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Terminal = line("M4 17l6-6-6-6", "M12 19h8")
    val Spark = line("M12 3l1.9 5.6L19.5 10l-5.6 1.9L12 17.5l-1.9-5.6L4.5 10l5.6-1.4z", "M19 17l.8 2.2L22 20l-2.2.8L19 23l-.8-2.2L16 20l2.2-.8z")
    val Brain = line("M9 4a3 3 0 0 0-3 3 3 3 0 0 0-2 5 3 3 0 0 0 2 5 3 3 0 0 0 6 1V5a2 2 0 0 0-3-1z",
        "M15 4a3 3 0 0 1 3 3 3 3 0 0 1 2 5 3 3 0 0 1-2 5 3 3 0 0 1-6 1")
    val Chip = line("M7 7h10v10H7z", "M10 2v3", "M14 2v3", "M10 19v3", "M14 19v3", "M2 10h3", "M2 14h3", "M19 10h3", "M19 14h3")
    val Shield = line("M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4")
    val Down = line("M12 5v14", "M6 13l6 6 6-6")
    val Chevron = line("M6 9l6 6 6-6")
    val Search = line("M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M21 21l-5-5")
    val Filter = line("M4 6h16", "M7 12h10", "M10 18h4")
    val Pin = line("M12 17v5", "M9 3h6l-1 6 4 4v2H6v-2l4-4z")
    val Stop = line("M7 7h10v10H7z")
    val Send = line("M12 19V5", "M5 12l7-7 7 7")
    val Steer = line("M9 10l-5 5 5 5", "M20 4v7a4 4 0 0 1-4 4H4")
    val Download = line("M12 3v12", "M7 10l5 5 5-5", "M5 21h14")
    val Qr = line("M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h3v3h-3z", "M20 14v.01", "M14 20h.01", "M20 20h.01", "M17 17h3")
    val Image = line("M3 5h18v14H3z", "M8.5 10.5a1.5 1.5 0 1 0 0-.01", "M21 15l-5-5L5 19")
    val Plus = line("M12 5v14", "M5 12h14")
    val Close = line("M6 6l12 12", "M18 6L6 18")
    val Back = line("M19 12H5", "M12 5l-7 7 7 7")
    val More = line("M12 6h.01", "M12 12h.01", "M12 18h.01")
    val Edit = line("M4 20h4L19 9l-4-4L4 16z")
    val Trash = line("M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Refresh = line("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Bolt = line("M13 2L4 14h7l-1 8 9-12h-7z")
    val Check = line("M5 12l5 5 9-10")
}

/** A status dot that breathes while [pulse] is on (live run, reconnecting). */
@Composable
fun StatusDot(color: Color, pulse: Boolean = false, size: Int = 8) {
    if (!pulse) {
        Box(Modifier.size(size.dp).background(color, CircleShape))
        return
    }
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val s by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(size.dp).scale(s).alpha(a).background(color, CircleShape))
}

/** Three dots bouncing in turn: the agent is thinking or working. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).alpha(a).background(color, CircleShape))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
}

/** The rounded container every settings group sits in. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** One tappable line in a [Panel]: leading icon, title, optional subtitle, trailing slot. */
@Composable
fun PanelRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/**
 * A soft light band sweeping left to right across the content: the desktop sidebar's "working"
 * row. Drawn over the content, so text stays readable and nothing re-lays out.
 */
fun Modifier.shimmer(active: Boolean, color: Color = Gold): Modifier = if (!active) this else composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "x")
    drawWithContent {
        drawContent()
        val band = size.width * 0.45f
        val center = size.width * x
        drawRect(Brush.horizontalGradient(
            listOf(Color.Transparent, color.copy(alpha = 0.16f), Color.Transparent),
            startX = center - band, endX = center + band))
    }
}

/**
 * How many characters of a streaming reply to show after [elapsedMs] more milliseconds.
 * Text arrives in bursts; revealing it at a steady pace that speeds up with the backlog reads
 * like typing instead of jumps, and even a 600-char burst is caught up in under a second.
 */
fun nextRevealLength(shown: Int, target: Int, elapsedMs: Long): Int {
    if (shown >= target) return target
    val backlog = target - shown
    // Close ~1/120 of the gap per ms (exponential catch-up), never slower than ~120 chars/s.
    val perMs = maxOf(0.12f, backlog / 120f)
    val step = maxOf(1, (perMs * elapsedMs).toInt())
    return minOf(target, shown + step)
}

/** [text] revealed smoothly while [streaming]; shown at once otherwise (history, finished replies). */
@Composable
fun rememberSmoothText(text: String, streaming: Boolean): String {
    var shown by remember { mutableIntStateOf(if (streaming) 0 else text.length) }
    if (!streaming && shown != text.length) shown = text.length
    if (shown > text.length) shown = text.length
    // Latest target for the reveal loop without restarting it on every delta.
    val latest by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val target = latest.length
            if (shown < target) shown = nextRevealLength(shown, target, now - last)
            last = now
        }
    }
    return text.substring(0, shown.coerceIn(0, text.length))
}

/** Small rounded pill: model / reasoning selectors, connection badge. */
@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(color = container, shape = CircleShape, modifier = modifier.then(
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
