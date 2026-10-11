package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.SessionGroup
import io.github.nideta231.hermesremote.data.SessionSummary
import io.github.nideta231.hermesremote.data.SessionView
import io.github.nideta231.hermesremote.data.arrangeSessions
import io.github.nideta231.hermesremote.data.changesBetween
import io.github.nideta231.hermesremote.data.fullChangelog
import io.github.nideta231.hermesremote.data.pageGroups
import io.github.nideta231.hermesremote.data.parseChangelog
import io.github.nideta231.hermesremote.ui.MdBlock
import io.github.nideta231.hermesremote.ui.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogAndPagingTest {
    private val md = """
        # Changelog

        Intro text.

        ## v1.5.0

        - New thing that wraps
          onto a second line.

        ## v1.4.0

        - Four.

        ## v1.3.0

        - Three.

        ## Earlier

        - Old stuff.
    """.trimIndent()

    @Test fun parsesVersionSectionsAndDropsEarlier() {
        val e = parseChangelog(md)
        assertEquals(listOf("1.5.0", "1.4.0", "1.3.0"), e.map { it.version })
        assertEquals("- New thing that wraps onto a second line.", e[0].body)
    }

    @Test fun changesBetweenSkipsTheInstalledVersionAndIncludesTheTarget() {
        val notes = changesBetween(md, from = "1.3.0", to = "1.5.0")
        assertTrue("## v1.5.0" in notes)
        assertTrue("## v1.4.0" in notes)
        assertFalse("## v1.3.0" in notes)
    }

    @Test fun unknownPreviousVersionShowsOnlyTheTarget() {
        val notes = changesBetween(md, from = null, to = "1.4.0")
        assertEquals("## v1.4.0\n- Four.", notes)
    }

    @Test fun fullChangelogListsEveryVersionNewestFirst() {
        val all = fullChangelog(md)
        assertTrue(all.indexOf("v1.5.0") < all.indexOf("v1.4.0"))
        assertFalse("Earlier" in all)
    }

    @Test fun theRealChangelogParses() {
        val real = File("../../CHANGELOG.md").takeIf { it.exists() } ?: File("../CHANGELOG.md")
        val e = parseChangelog(real.readText())
        assertTrue(e.size >= 8)
        assertTrue(e.all { it.body.isNotBlank() })
    }

    private fun s(id: String) = SessionSummary(id, id, null, "desktop", 1, 1000.0 - id.removePrefix("s").toInt(), null, false, null, 0, 0.0, false)

    @Test fun groupsShowOnePageThenGrowByShowMore() {
        val g = SessionGroup("Today", (0 until 40).map { s("s$it") })
        val first = pageGroups(listOf(g), emptyMap(), page = 15).single()
        assertEquals(15, first.items.size)
        assertEquals(25, first.remaining)
        val more = pageGroups(listOf(g), mapOf(g.key to 30), page = 15).single()
        assertEquals(30, more.items.size)
        assertEquals(10, more.remaining)
    }

    @Test fun theOpenChatIsNeverHiddenBehindShowMore() {
        val g = SessionGroup("Today", (0 until 40).map { s("s$it") })
        val p = pageGroups(listOf(g), emptyMap(), currentId = "s33", page = 15).single()
        assertTrue(p.items.any { it.id == "s33" })
    }

    @Test fun searchRunsOverEveryChatNotOnlyTheShownPage() {
        val all = (0 until 60).map { s("s$it") }
        // s55 is far past the first page; a search must still find it.
        val groups = arrangeSessions(all, SessionView(), query = "s55")
        val p = pageGroups(groups, emptyMap(), page = 15)
        assertEquals(listOf("s55"), p.flatMap { it.items }.map { it.id })
        assertEquals(0, p.sumOf { it.remaining })
    }

    @Test fun codeFenceKeepsItsLanguage() {
        assertEquals(listOf(MdBlock.Code("val x = 1", "kotlin")), parseMarkdown("```kotlin\nval x = 1\n```"))
        assertEquals(listOf(MdBlock.Code("plain", "")), parseMarkdown("```\nplain\n```"))
    }

    @Test fun wrappedParagraphsAndBulletsJoinButListsStaySeparate() {
        val e = parseChangelog("## v1.0.0\n\nA long\nparagraph.\n\n- one\n  two\n- three\n")
        assertEquals("A long paragraph.\n\n- one two\n- three", e.single().body)
    }
}
