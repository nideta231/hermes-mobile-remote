package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.GraphKind
import io.github.nideta231.hermesremote.data.KnowledgeGraph
import io.github.nideta231.hermesremote.data.LibraryProject
import io.github.nideta231.hermesremote.data.LibraryStats
import io.github.nideta231.hermesremote.data.LibraryStatus
import io.github.nideta231.hermesremote.data.LibraryText
import io.github.nideta231.hermesremote.data.Memory
import io.github.nideta231.hermesremote.data.RecallResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test fun statusParsesWhatTheSetupNeeds() {
        val s = LibraryStatus.parse(JSONObject("""{"supported":true,"installed":true,"running":true,"enabled":false,
            "profile":"default","provider":"honcho","custom_provider":"honcho","plugin_dir_conflict":false,
            "builtin_memory":{"MEMORY.md":{"exists":true,"entries":12},"USER.md":{"exists":true,"entries":5}},
            "plan":{"supported":true,"download_bytes":34200744,"disk_bytes":157200744,"disk_free_bytes":9000000000,
                    "enough_disk":true,"location":"/x/library","remove":"r","network":"None."},
            "job":{}, "settings":{"smart_recall":false,"session_review":true,"model":"","provider":"auto"},
            "benchmark":{"query_ms":14.7,"ok":true}}"""))
        assertEquals("honcho", s.customProvider)
        assertEquals(17, s.builtinEntries)
        assertEquals(34200744L, s.plan!!.downloadBytes)
        assertNull(s.job)  // an empty job object means no install ran
        assertFalse(s.installing)
        assertEquals(14.7, s.benchmarkMs!!, 0.01)
        assertEquals("auto", s.modelProvider)
    }

    @Test fun nullsStayNull() {
        val s = LibraryStatus.parse(JSONObject("""{"supported":false,"reason":"Linux only","custom_provider":null}"""))
        assertNull(s.customProvider)
        assertEquals("Linux only", s.reason)
        assertTrue(s.sessionReview)  // default on
    }

    @Test fun recallKeepsTheScoreParts() {
        val r = RecallResult.parse(JSONObject("""{"pinned":[{"id":1,"kind":"rule","severity":5,"scope":"global","text":"No Pak"}],
            "relevant":[{"id":7,"kind":"preference","severity":3,"scope":"project:p1","text":"WA style","score":0.512,
              "ft":0.1,"sim":0.3,"scope_w":1.5,"sev_w":1.2,"fresh":0.95}],"injected":"## Memory Library"}"""))
        assertEquals(1L, r.pinned.single().id)
        val m = r.relevant.single()
        assertEquals(1.5, m.scopeW!!, 0.001)
        assertTrue(LibraryText.scoreLine(m)!!.startsWith("score 0.51 = (words 0.10 × 2 + similar 0.30)"))
        assertNull(LibraryText.scoreLine(r.pinned.single()))
    }

    @Test fun memoryDetailParsesLinksAndHistory() {
        val m = Memory.parse(JSONObject("""{"id":3,"kind":"mistake","severity":4,"scope":"client:Acme","text":"t","source_session":null,
            "links":[{"src":3,"dst":9,"rel":"supersedes","other_text":"old","other_kind":"rule"}],
            "history":[{"at":"2026-10-31T23:00:00+07:00","by":"phone","action":"edit"}]}"""))
        assertNull(m.sourceSession)
        assertEquals("supersedes", m.links.single().rel)
        assertEquals("phone", m.history.single().by)
    }

    @Test fun scopeLabelsAndValidation() {
        val ps = listOf(LibraryProject("p_1", "Shop"))
        assertEquals("Everywhere", LibraryText.scopeLabel("global", ps))
        assertEquals("Shop", LibraryText.scopeLabel("project:p_1", ps))
        assertEquals("p_gone", LibraryText.scopeLabel("project:p_gone", ps))
        assertEquals("Acme & Co. (client)", LibraryText.scopeLabel("client:Acme & Co.", ps))
        assertTrue(LibraryText.validScope("client:Acme & Co."))
        assertTrue(LibraryText.validScope("project:p_8c74410b"))
        assertFalse(LibraryText.validScope("project:"))
        assertFalse(LibraryText.validScope("client:a,b"))
        assertFalse(LibraryText.validScope("everywhere"))
    }

    @Test fun installProgressLines() {
        assertEquals("Downloading 17 MB of 34 MB (50%)",
            LibraryText.jobLine(JSONObject("""{"state":"running","step":"download","done":17100372,"total":34200744}""")))
        assertEquals("Install failed: checksum", LibraryText.jobLine(JSONObject("""{"state":"failed","error":"checksum"}""")))
        assertNull(LibraryText.jobLine(null))
        assertEquals("Fatal", LibraryText.severityLabel(5))
    }

    @Test fun statsCountActiveKindsOnly() {
        val s = LibraryStats.parse(JSONObject("""{"by_kind":[{"kind":"rule","status":"active","n":3},{"kind":"rule","status":"superseded","n":2}],
            "most_repeated":[],"unused_30d":4,"needs_review":2,"scopes":[{"scope":"global","n":3}]}"""))
        assertEquals(mapOf("rule" to 3), s.byKind)
        assertEquals(listOf("global" to 3), s.scopes)
    }

    @Test fun knowledgeGraphFromTheBridge() {
        val g = KnowledgeGraph.build(JSONObject("""{"nodes":[
            {"id":"m1","type":"memory","memory":1,"kind":"rule","severity":5,"label":"No Pak"},
            {"id":"m2","type":"memory","memory":2,"kind":"mistake","severity":2,"label":"x"},
            {"id":"s:global","type":"global","label":"Everywhere","scope":"global","count":1},
            {"id":"s:project:p1","type":"project","label":"Shop","scope":"project:p1","count":1},
            {"id":"c:20261031_1","type":"chat","session":"20261031_1","label":"Chat"}],
          "edges":[{"from":"m1","to":"s:global","rel":"applies_to"},{"from":"m2","to":"s:project:p1","rel":"applies_to"},
            {"from":"m2","to":"m1","rel":"related"},{"from":"m2","to":"c:20261031_1","rel":"learned_in"},
            {"from":"m9","to":"m1","rel":"related"}]}"""))
        assertEquals(5, g.nodes.size)
        assertEquals(4, g.edges.size)  // the edge to a node not in the map is dropped
        val m1 = g.nodes[0]
        assertEquals(GraphKind.MEMORY, m1.kind); assertEquals("1", m1.ref); assertEquals("rule", m1.tag); assertEquals(5, m1.weight)
        assertEquals(GraphKind.GLOBAL, g.nodes[2].kind)
        assertEquals("project:p1", g.nodes[3].ref)
        assertEquals(GraphKind.CHAT, g.nodes[4].kind); assertEquals("20261031_1", g.nodes[4].ref)
        assertEquals(3, g.degree[1])
    }
}
