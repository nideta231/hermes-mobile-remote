package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One rendered block of an assistant message. */
sealed interface MdBlock {
    data class Text(val body: String) : MdBlock
    data class Code(val body: String) : MdBlock
    data class Table(val header: List<String>, val align: List<CellAlign>, val rows: List<List<String>>) : MdBlock
}

enum class CellAlign { Start, Center, End }

private val separatorCell = Regex("""^\s*:?-{1,}:?\s*$""")

/** Splits markdown into code fences, GitHub-style tables and plain text, in order. */
fun parseMarkdown(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    text.split("```").forEachIndexed { i, part ->
        if (i % 2 == 1) out += MdBlock.Code(part.substringAfter('\n', part).trimEnd('\n'))
        else splitTables(part, out)
    }
    return out
}

private fun splitTables(text: String, out: MutableList<MdBlock>) {
    val lines = text.split('\n')
    val pending = StringBuilder()
    fun flush() {
        if (pending.isNotBlank()) out += MdBlock.Text(pending.toString().trim('\n'))
        pending.clear()
    }
    var i = 0
    while (i < lines.size) {
        val header = lines[i]
        val sep = lines.getOrNull(i + 1)
        val headerCells = if (header.contains('|')) splitRow(header) else null
        val sepCells = sep?.takeIf { it.contains('-') }
            ?.let { splitRow(it) }
            ?.takeIf { cells -> cells.isNotEmpty() && cells.all { separatorCell.matches(it) } }
        if (headerCells != null && sepCells != null && headerCells.size >= 2 && headerCells.size == sepCells.size) {
            flush()
            val cols = headerCells.size
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            // GFM: the body runs until a blank line or the start of another block; pipes are optional.
            while (j < lines.size && lines[j].isNotBlank() && !startsBlock(lines[j])) {
                val cells = splitRow(lines[j])
                rows += List(cols) { cells.getOrElse(it) { "" } }
                j++
            }
            val align = sepCells.map {
                val t = it.trim()
                when {
                    t.startsWith(':') && t.endsWith(':') -> CellAlign.Center
                    t.endsWith(':') -> CellAlign.End
                    else -> CellAlign.Start
                }
            }
            out += MdBlock.Table(headerCells, align, rows)
            i = j
        } else {
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(header)
            i++
        }
    }
    flush()
}

private val blockStart = Regex("""^\s{0,3}(#{1,6}\s|>|[-*_]{3,}\s*$|```|~~~)""")

/** A line that opens a heading, quote, rule or fence, which ends a table body. */
private fun startsBlock(line: String) = blockStart.containsMatchIn(line)

/** Cells of one table row; leading/trailing pipes are optional and `\|` stays a literal pipe. */
internal fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith('|')) s = s.substring(1)
    if (s.endsWith('|') && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    var inCode = false
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && s.getOrNull(i + 1) == '|' -> { cur.append('|'); i++ }
            c == '`' -> { inCode = !inCode; cur.append(c) }
            c == '|' && !inCode -> { cells += cur.toString().trim(); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

/** Minimal markdown: fenced code, tables, headings, bullets, **bold**, *italic*, `code`. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    SelectionContainer(modifier) {
        Column {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Code -> Text(
                        block.body,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        softWrap = false,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                    is MdBlock.Table -> MarkdownTable(block)
                    is MdBlock.Text -> if (block.body.isNotBlank()) {
                        Text(inline(block.body), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp))
                    }
                }
            }
        }
    }
}

/** Column width from its longest cell, capped so a long cell wraps instead of making one giant column. */
internal fun columnWidthDp(cells: List<String>): Int {
    val longest = cells.maxOfOrNull { plainLength(it) } ?: 0
    return (longest * 7.2f + 22f).toInt().coerceIn(56, 240)
}

private fun plainLength(cell: String) = cell.replace("**", "").replace("`", "").length

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val colors = MaterialTheme.colorScheme
    val widths = remember(table) {
        table.header.indices.map { c -> columnWidthDp(listOf(table.header[c]) + table.rows.map { it[c] }).dp }
    }
    val shape = RoundedCornerShape(8.dp)
    // Wide tables scroll sideways inside the bubble instead of squeezing every column to a letter.
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            TableRow(table.header, table.align, widths, header = true)
            table.rows.forEachIndexed { r, row ->
                Box(Modifier.width(widths.fold(0.dp) { a, b -> a + b }).height(1.dp).background(colors.outline))
                TableRow(row, table.align, widths, header = false, striped = r % 2 == 1)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, align: List<CellAlign>, widths: List<androidx.compose.ui.unit.Dp>, header: Boolean, striped: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val bg = when {
        header -> colors.surfaceContainerHighest
        striped -> colors.surfaceContainer
        else -> Color.Transparent
    }
    Row(Modifier.height(IntrinsicSize.Min).background(bg)) {
        cells.forEachIndexed { c, cell ->
            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
            Text(
                inline(cell),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                fontWeight = if (header) FontWeight.SemiBold else null,
                textAlign = when (align.getOrNull(c)) {
                    CellAlign.Center -> TextAlign.Center
                    CellAlign.End -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier.width(widths[c]).padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}

private val inlineRe = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")

private fun inline(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { li, raw ->
        if (li > 0) append('\n')
        var line = raw
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) { appendInline(line.substring(heading.range.last + 1)) }
            return@forEachIndexed
        }
        Regex("^(\\s*)[-*+]\\s+").find(line)?.let { m ->
            append(m.groupValues[1] + "•  ")
            line = line.substring(m.range.last + 1)
        }
        appendInline(line)
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    var i = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(i, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF2A2D35), fontSize = 13.sp)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
