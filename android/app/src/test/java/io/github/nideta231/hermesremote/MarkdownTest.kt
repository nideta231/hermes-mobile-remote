package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.CellAlign
import io.github.nideta231.hermesremote.ui.MdBlock
import io.github.nideta231.hermesremote.ui.parseMarkdown
import io.github.nideta231.hermesremote.ui.splitRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun tableBetweenTextBecomesItsOwnBlock() {
        val md = """
            Here are the results:

            | Name | Qty | Price |
            |:-----|:---:|------:|
            | Oil filter | 2 | 45.000 |
            | Brake pad | 1 | 120.000 |

            Total is fine.
        """.trimIndent()
        val blocks = parseMarkdown(md)
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Text("Here are the results:"), blocks[0])
        val t = blocks[1] as MdBlock.Table
        assertEquals(listOf("Name", "Qty", "Price"), t.header)
        assertEquals(listOf(CellAlign.Start, CellAlign.Center, CellAlign.End), t.align)
        assertEquals(listOf("Oil filter", "2", "45.000"), t.rows[0])
        assertEquals(2, t.rows.size)
        assertEquals(MdBlock.Text("Total is fine."), blocks[2])
    }

    @Test fun pipesAreOptionalAndShortRowsArePadded() {
        val t = parseMarkdown("a | b\n--- | ---\n1\n") .single() as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "")), t.rows)
    }

    @Test fun escapedPipeAndPipeInCodeStayInsideTheCell() {
        assertEquals(listOf("a \\| b".replace("\\|", "|"), "`x|y`"), splitRow("| a \\| b | `x|y` |"))
    }

    @Test fun pipeTextWithoutSeparatorIsNotATable() {
        val blocks = parseMarkdown("use a | b to pipe\nnext line")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Text)
    }

    @Test fun tableInsideCodeFenceStaysCode() {
        val blocks = parseMarkdown("```\n| a | b |\n|---|---|\n```")
        assertEquals(listOf(MdBlock.Code("| a | b |\n|---|---|")), blocks)
    }
}
