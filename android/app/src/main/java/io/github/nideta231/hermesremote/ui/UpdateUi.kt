package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.UpdateState

/** One line under the chat's top bar when an update is out: tap for what's new, ✕ to hide it. */
@Composable
fun UpdateStrip(u: UpdateState, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(u.banner, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = Gold.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Download, null, tint = Gold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(updateLine(u), style = MaterialTheme.typography.labelLarge, color = Gold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (u.progress == null) IconButton(onClick = onDismiss) { Icon(Glyphs.Close, "Hide", tint = Gold, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

/** The drawer's update row, under the PC header. Stays until the update is installed. */
@Composable
fun DrawerUpdateRow(u: UpdateState, onOpen: () -> Unit) {
    AnimatedVisibility(u.available != null || u.progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = Gold.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Download, null, tint = Gold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(updateLine(u), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Gold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (u.progress != null) "Installing when done" else "Tap to see what's new",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun updateLine(u: UpdateState): String {
    val v = u.available?.version ?: "update"
    val p = u.progress
    return if (p != null) "Downloading $v… ${(p * 100).toInt()}%" else "Version $v is available"
}

/**
 * A bottom sheet of release notes: the update on offer (with Update now), what changed after an
 * update, or the whole changelog. [action] sits under the notes; null for none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSheet(title: String, subtitle: String?, markdown: String, onDismiss: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.padding(top = 12.dp).heightIn(max = maxNotes).verticalScroll(rememberScrollState())) {
                if (markdown.isBlank()) Text("No notes for this version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else MarkdownText(markdown)
            }
            action?.let { Spacer(Modifier.size(16.dp)); it() }
        }
    }
}

/** The update sheet: notes for every version between this build and the new one, then Update now. */
@Composable
fun UpdateSheet(u: UpdateState, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val available = u.available ?: return
    NotesSheet(
        title = "Update to ${available.version}",
        subtitle = "You have ${u.installed.ifBlank { "?" }}. Changes since then:",
        markdown = available.notes,
        onDismiss = onDismiss,
    ) {
        val p = u.progress
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
        else Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
    }
}
