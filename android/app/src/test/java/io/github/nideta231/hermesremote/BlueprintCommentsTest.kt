package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Blueprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintCommentsTest {
    private val doc = JSONObject("""{
      "project": {"id": "p1", "name": "Shop"}, "version": 3, "control": "hermes",
      "instructions": "Never touch LIVE unless I ask.",
      "board": {"columns": [{"id": "working", "name": "Working"}], "cards": [
        {"id": "c1", "column": "working", "title": "Deploy", "agent": "Hermes", "comments": [
          {"id": "m1", "at": 1.0, "by": "hermes", "kind": "question", "text": "Deploy now?", "resolved": false, "files": ["/w/plan.md"]},
          {"id": "m2", "at": 2.0, "by": "phone", "kind": "comment", "text": "wait"}]},
        {"id": "c2", "column": "working", "title": "Old", "comments": [
          {"id": "m3", "at": 1.0, "by": "hermes", "kind": "review", "text": "ok?", "resolved": true}]}]}
    }""")

    @Test fun parsesAgentCommentsAndInstructions() {
        val bp = Blueprint.parse(doc)
        assertEquals("Never touch LIVE unless I ask.", bp.instructions)
        val c1 = bp.cards[0]
        assertEquals("Hermes", c1.agent)
        assertEquals(listOf("/w/plan.md"), c1.comments[0].files)
        assertEquals(listOf("m1"), c1.openAsks.map { it.id })
        assertNull(bp.cards[1].agent)
        assertTrue(bp.cards[1].openAsks.isEmpty())  // a resolved review no longer waits
        assertEquals(listOf("c1"), bp.needsYou.map { it.id })
    }

    @Test fun resolvingOnThePhonePingsTheChatThatAsked() {
        val d = JSONObject("""{"project": {"id": "p1", "name": "Shop"}, "version": 1,
          "board": {"columns": [{"id": "w", "name": "Working"}], "cards": [
            {"id": "c1", "column": "w", "title": "Deploy", "session": "card-chat", "comments": [
              {"id": "m1", "at": 1.0, "by": "hermes", "kind": "review", "text": "Check the export\nmore", "session": "ask-chat"},
              {"id": "m2", "at": 2.0, "by": "hermes", "kind": "question", "text": "Live?"}]}]}}""")
        val bp = Blueprint.parse(d)
        val card = bp.cards[0]
        val (review, question) = card.comments
        assertEquals("ask-chat", review.session)
        assertEquals("ask-chat", io.github.nideta231.hermesremote.data.resolvedPingChat(card, review))
        assertEquals("card-chat", io.github.nideta231.hermesremote.data.resolvedPingChat(card, question))  // older comment: the card's chat
        assertNull(io.github.nideta231.hermesremote.data.resolvedPingChat(card.copy(session = null), question))
        val text = io.github.nideta231.hermesremote.data.resolvedPingText(bp, card, review)
        assertTrue(text, text.startsWith("I marked your review as reviewed on the card \"Deploy\" in the Shop blueprint (\"Check the export\")"))
        assertTrue(io.github.nideta231.hermesremote.data.resolvedPingText(bp, card, question).startsWith("I marked your question as answered"))
    }
}
