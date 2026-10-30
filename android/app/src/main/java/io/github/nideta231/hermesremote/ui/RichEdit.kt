package io.github.nideta231.hermesremote.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Formatting for Markdown text boxes (card descriptions): pure edits on a [TextFieldValue], so the
 * toolbar works on the selection like a rich text editor while the stored text stays Markdown, which
 * Hermes reads as is and the board renders.
 */
object RichEdit {
    /** Wrap the selection in [left]/[right] (`**bold**`); with nothing selected, put the cursor between them. Again = unwrap. */
    fun wrap(v: TextFieldValue, left: String, right: String = left): TextFieldValue {
        val s = v.selection.min; val e = v.selection.max
        val t = v.text
        // Already wrapped around the selection: take the marks off.
        if (s >= left.length && e + right.length <= t.length && t.substring(s - left.length, s) == left && t.substring(e, e + right.length) == right) {
            val out = t.substring(0, s - left.length) + t.substring(s, e) + t.substring(e + right.length)
            return TextFieldValue(out, TextRange(s - left.length, e - left.length))
        }
        val out = t.substring(0, s) + left + t.substring(s, e) + right + t.substring(e)
        return TextFieldValue(out, TextRange(s + left.length, e + left.length))
    }

    /** Start every selected line (or the cursor's line) with [prefix] (`- `, `# `); if all have it, take it off. */
    fun prefixLines(v: TextFieldValue, prefix: String): TextFieldValue {
        val t = v.text
        val start = t.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(-1)).let { if (v.selection.min == 0) 0 else it + 1 }
        val endNl = t.indexOf('\n', v.selection.max).let { if (it < 0) t.length else it }
        val lines = t.substring(start, endNl).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        val changed = lines.map { if (remove) it.removePrefix(prefix) else stripListMark(it).let { l -> prefix + l } }
        val block = changed.joinToString("\n")
        val out = t.substring(0, start) + block + t.substring(endNl)
        val delta = block.length - (endNl - start)
        val cur = if (lines.size == 1) (v.selection.max + delta).coerceIn(start, start + block.length) else start + block.length
        return TextFieldValue(out, if (lines.size == 1) TextRange(cur) else TextRange(start, start + block.length))
    }

    /** A link: the selection becomes the text, with the cursor in the `()` to type the address. */
    fun link(v: TextFieldValue): TextFieldValue {
        val s = v.selection.min; val e = v.selection.max
        val label = v.text.substring(s, e).ifEmpty { "link" }
        val out = v.text.substring(0, s) + "[$label]()" + v.text.substring(e)
        return TextFieldValue(out, TextRange(s + label.length + 3))
    }

    private val listMark = Regex("""^(- \[[ xX]\] |[-*+] |\d{1,3}\. |#{1,6} )""")
    private fun stripListMark(line: String) = line.replaceFirst(listMark, "")
}
