package io.github.nideta231.hermesremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.nideta231.hermesremote.data.Blueprint
import io.github.nideta231.hermesremote.ui.RichEdit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RichEditTest {
    @Test fun boldWrapsTheSelectionAndUnwrapsAgain() {
        val v = RichEdit.wrap(TextFieldValue("fix login", TextRange(4, 9)), "**")
        assertEquals("fix **login**", v.text); assertEquals(TextRange(6, 11), v.selection)
        assertEquals("fix login", RichEdit.wrap(v, "**").text)
    }

    @Test fun emptySelectionPutsTheCursorBetweenMarks() {
        val v = RichEdit.wrap(TextFieldValue("a ", TextRange(2)), "`")
        assertEquals("a ``", v.text); assertEquals(TextRange(3), v.selection)
    }

    @Test fun listTogglesEverySelectedLineAndReplacesOtherMarks() {
        val v = RichEdit.prefixLines(TextFieldValue("one\n# two\nthree", TextRange(0, 9)), "- ")
        assertEquals("- one\n- two\nthree", v.text)
        assertEquals("one\ntwo\nthree", RichEdit.prefixLines(v, "- ").text)
        assertEquals("x\n## y", RichEdit.prefixLines(TextFieldValue("x\ny", TextRange(3)), "## ").text)
    }

    @Test fun linkLeavesTheCursorInTheAddress() {
        val v = RichEdit.link(TextFieldValue("see docs", TextRange(4, 8)))
        assertEquals("see [docs]()", v.text); assertEquals(TextRange(11), v.selection)
    }

    @Test fun cardFilesParse() {
        val bp = Blueprint.parse(JSONObject().put("board", JSONObject().put("cards", JSONArray()
            .put(JSONObject().put("id", "c1").put("files", JSONArray().put("/pc/a.png"))))))
        assertEquals(listOf("/pc/a.png"), bp.cards[0].files)
    }
}
