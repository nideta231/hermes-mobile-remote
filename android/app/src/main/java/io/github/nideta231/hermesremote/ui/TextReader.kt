package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nideta231.hermesremote.data.TextReading

/**
 * A text file from the PC read inside the app: Markdown files open rendered (Preview) with a Plain
 * switch, every text file can be copied, saved to the phone or handed to another app.
 */
@Composable
internal fun TextReader(file: java.io.File, name: String, onClose: () -> Unit, onSave: () -> Unit, onOpenWith: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text by produceState<TextReading.Loaded?>(null, file) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TextReading.load(file) }
    }
    val markdown = remember(name) { TextReading.isMarkdown(name) }
    var plain by rememberSaveable(name) { mutableStateOf(!markdown) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Glyphs.Close, "Close") }
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = {
                        val t = text?.text ?: return@IconButton
                        clipboard.setText(AnnotatedString(t))
                        android.widget.Toast.makeText(context, "Copied" + if (text?.cut == true) " (the part shown)" else "", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Glyphs.Copy, "Copy text", Modifier.size(20.dp)) }
                    IconButton(onClick = onSave) { Icon(Glyphs.Download, "Save to the phone", Modifier.size(20.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (markdown) {
                        FilterChip(!plain, { plain = false }, { Text("Preview") }, colors = themedChip())
                        FilterChip(plain, { plain = true }, { Text("Plain text") }, colors = themedChip())
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onOpenWith) { Text("Open with…") }
                }
                val t = text
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        t == null -> Text("Opening…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        t.error != null -> Text(t.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (t.cut) Text("Showing the first ${TextReading.MAX_CHARS / 1000}k characters. Save it to read the rest.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp))
                            if (plain) SelectionContainer {
                                Text(t.text, Modifier.horizontalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = false)
                            } else SelectionContainer { MarkdownText(t.text) }
                        }
                    }
                }
            }
        }
    }
}
