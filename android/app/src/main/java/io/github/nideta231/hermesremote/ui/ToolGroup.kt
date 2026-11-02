package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.data.FoldedItem
import io.github.nideta231.hermesremote.data.ToolStep
import io.github.nideta231.hermesremote.data.isOpen
import io.github.nideta231.hermesremote.data.stepIsLive
import io.github.nideta231.hermesremote.data.stepsOf

/** "Working" while running, else "Worked for 12.3s", or just "Worked" when no duration is known. */
internal fun groupTitle(running: Boolean, durationSec: Double): String = when {
    running -> "Working"
    durationSec > 0.0 -> "Worked for %.1fs".format(java.util.Locale.US, durationSec)
    else -> "Worked"
}

/** "2 thoughts · 5 tool calls"; the thought part is left out when there are none. */
internal fun groupSummary(thoughts: Int, tools: Int): String = listOfNotNull(
    if (thoughts > 0) "$thoughts thought${if (thoughts == 1) "" else "s"}" else null,
    "$tools tool call${if (tools == 1) "" else "s"}",
).joinToString(" · ")

internal fun failedText(count: Int): String? = if (count > 0) "$count failed" else null

/** A stretch of thoughts and tool calls as one row: closed it is a summary, open it is a rail of steps. */
@Composable
internal fun ToolGroupRow(group: FoldedItem.Group) {
    // null = the reader has not tapped: follow the default (the last turn's group is open).
    var picked by rememberSaveable(group.key) { mutableStateOf<Boolean?>(null) }
    val open = isOpen(picked, group.lastTurn)
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "group-chev")
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = !open }
            .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Chevron, if (open) "Hide steps" else "Show steps", tint = quiet, modifier = Modifier.size(14.dp).rotate(rot))
            Spacer(Modifier.width(6.dp))
            if (group.running) {
                StatusDot(Gold, pulse = true, size = 8)
                Spacer(Modifier.width(6.dp))
            }
            Text(groupTitle(group.running, group.durationSec), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = quiet)
            Spacer(Modifier.width(8.dp))
            Text(groupSummary(group.thoughtCount, group.toolCount), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = quiet.copy(alpha = 0.7f))
            failedText(group.failedCount)?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Bad)
            }
        }
        if (open) {
            val steps = remember(group.items) { stepsOf(group.items) }
            Row(Modifier.padding(start = 12.dp, top = 2.dp).height(IntrinsicSize.Min)) {
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(quiet.copy(alpha = 0.3f)))
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    steps.forEachIndexed { i, step ->
                        ToolStepRow(group.key, step, live = stepIsLive(group.running, i, steps.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStepRow(groupKey: String, step: ToolStep, live: Boolean) {
    val stepKey = step.thought?.key ?: step.tools.first().key
    var picked by rememberSaveable(groupKey, stepKey) { mutableStateOf<Boolean?>(null) }
    val open = isOpen(picked, live)
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "step-chev")
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picked = !open }.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Chevron, null, tint = quiet, modifier = Modifier.size(12.dp).rotate(rot))
            Spacer(Modifier.width(6.dp))
            Text(if (step.thought != null) "Thought" else "Tool calls", fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = quiet)
            if (step.tools.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text("${step.tools.size} tool${if (step.tools.size == 1) "" else "s"}", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, color = quiet.copy(alpha = 0.7f))
            }
            failedText(step.failedCount)?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Bad)
            }
        }
        if (open) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The live step shows only its tools; the thought text needs the reader's own tap.
                if (step.thought != null && picked == true) ThoughtText(step.thought.text)
                step.tools.forEach { ToolRow(it) }
            }
        }
    }
}
