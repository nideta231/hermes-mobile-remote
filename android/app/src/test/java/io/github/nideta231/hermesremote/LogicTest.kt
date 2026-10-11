package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.Endpoint
import io.github.nideta231.hermesremote.data.EndpointResolver
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.LanDiscovery
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.PairingParser
import io.github.nideta231.hermesremote.data.parseCatalog
import io.github.nideta231.hermesremote.data.parseSession
import io.github.nideta231.hermesremote.data.Tailnet
import io.github.nideta231.hermesremote.data.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun ev(items: List<ChatItem>, type: String, data: String = "{}", key: String = type) =
        LiveReducer.apply(items, type, JSONObject(data), key)

    @Test fun reducerStreamsToolsAndCompletion() {
        var items: List<ChatItem> = listOf(ChatItem.User("u", "run uname"))
        items = ev(items, "tool.start", """{"tool_id":"c1","name":"terminal","context":"uname -r"}""", "1")
        assertEquals(ToolStatus.RUNNING, (items[1] as ChatItem.Tool).status)
        items = ev(items, "tool.complete", """{"tool_id":"c1","name":"terminal","duration_s":0.4,"result":{"output":"7.1.8","exit_code":0}}""", "2")
        val tool = items[1] as ChatItem.Tool
        assertEquals(ToolStatus.OK, tool.status)
        assertEquals(0.4, tool.durationSec!!, 0.001)
        items = ev(items, "message.delta", """{"text":"\n\n7.1"}""", "3")
        items = ev(items, "message.delta", """{"text":".8"}""", "4")
        assertEquals("7.1.8", (items.last() as ChatItem.Assistant).text)
        assertTrue((items.last() as ChatItem.Assistant).streaming)
        items = ev(items, "message.complete", """{"text":"7.1.8","status":"complete"}""", "5")
        val last = items.last() as ChatItem.Assistant
        assertEquals("7.1.8", last.text)
        assertFalse(last.streaming)
        assertEquals(3, items.size) // the final text is not duplicated
    }

    @Test fun failedToolResultIsMarkedFailed() {
        var items: List<ChatItem> = emptyList()
        items = ev(items, "tool.start", """{"tool_id":"c1","name":"terminal","context":"false"}""", "1")
        items = ev(items, "tool.complete", """{"tool_id":"c1","name":"terminal","result":{"output":"","exit_code":1}}""", "2")
        assertEquals(ToolStatus.FAILED, (items[0] as ChatItem.Tool).status)
    }

    @Test fun interruptedTurnMarksRunningToolsFailed() {
        var items: List<ChatItem> = emptyList()
        items = ev(items, "tool.start", """{"tool_id":"c1","name":"terminal","context":"sleep 60"}""", "1")
        items = ev(items, "message.complete", """{"status":"interrupted"}""", "2")
        assertEquals(ToolStatus.FAILED, (items[0] as ChatItem.Tool).status)
        assertEquals("Stopped.", (items.last() as ChatItem.Notice).text)
    }

    @Test fun replyWithoutDeltasStillShows() {
        val items = ev(listOf(ChatItem.User("u", "hi")), "message.complete", """{"text":"Hello!","status":"complete"}""")
        assertEquals("Hello!", (items.last() as ChatItem.Assistant).text)
    }

    @Test fun reasoningStreamsIntoItsOwnBlockBeforeTheReply() {
        var items: List<ChatItem> = listOf(ChatItem.User("u", "why"))
        items = ev(items, "reasoning.delta", """{"text":"Let me think"}""", "1")
        items = ev(items, "message.delta", """{"text":"Because"}""", "2")
        assertEquals("Let me think", (items[1] as ChatItem.Thinking).text)
        assertFalse((items[1] as ChatItem.Thinking).streaming)
        assertEquals("Because", (items[2] as ChatItem.Assistant).text)
    }

    @Test fun spinnerStatusIsNotShownAsReasoning() {
        val items = ev(listOf(ChatItem.User("u", "hi")), "thinking.delta", """{"text":"( ˘⌣˘)♡ reasoning..."}""")
        assertEquals(1, items.size)
    }

    @Test fun approvalLifecycleAcrossScreens() {
        val req = io.github.nideta231.hermesremote.data.parseApproval("srq-1",
            JSONObject("""{"session_id":"s1","command":"rm -rf x","description":"recursive delete","choices":["once","session","deny"]}"""))
        var items: List<ChatItem> = LiveReducer.withApproval(emptyList(), req)
        items = LiveReducer.withApproval(items, req) // replayed on resume: still one
        assertEquals(1, items.size)
        assertEquals("rm -rf x", LiveReducer.openApproval(items)!!.request.command)
        // Answered on the desktop: the backend cancels the phone's copy.
        items = LiveReducer.resolve(items, "srq-1", "answered on another screen")
        assertNull(LiveReducer.openApproval(items))
        assertEquals("answered on another screen", (items[0] as ChatItem.Approval).decided)
    }

    @Test fun clarifyBatchParses() {
        val r = io.github.nideta231.hermesremote.data.parseClarify("srq-2", JSONObject(
            """{"session_id":"s1","questions":[{"qid":"a","question":"Color?","choices":["red","blue"]},{"qid":"b","question":"Size?"}]}"""))
        assertTrue(r.batch)
        assertEquals(listOf("red", "blue"), r.questions[0].choices)
        val single = io.github.nideta231.hermesremote.data.parseClarify("srq-3", JSONObject("""{"question":"Proceed?","choices":["yes","no"]}"""))
        assertFalse(single.batch)
    }

    @Test fun historyMapsDisplayTranscript() {
        val msgs = JSONArray("""[
            {"role":"user","text":"go","row_id":1},
            {"role":"assistant","text":"","reasoning":"plan it","row_id":2},
            {"role":"tool","name":"terminal","context":"ls","tool_call_id":"c1","row_id":3},
            {"role":"assistant","text":"done","row_id":4},
            {"role":"user","text":"secret","display_kind":"hidden","row_id":5}
        ]""")
        val items = HistoryMapper.map(msgs)
        assertEquals(4, items.size)
        assertEquals("go", (items[0] as ChatItem.User).text)
        assertEquals("plan it", (items[1] as ChatItem.Thinking).text)
        assertEquals("ls", (items[2] as ChatItem.Tool).args)
        assertEquals("done", (items[3] as ChatItem.Assistant).text)
    }

    @Test fun hermesNoticesStoredAsUserRowsAreNotShownAsUserMessages() {
        val msgs = JSONArray("""[
            {"role":"user","text":"real ask","row_id":1},
            {"role":"user","text":"[IMPORTANT: Background process proc_1 completed normally (exit code 0).\nOutput: x","row_id":2},
            {"role":"user","text":"[IMPORTANT: 2 background processes completed. Treat these","display_kind":"process_complete",
             "display_metadata":"{\"display_text\": \"Background Process Finished: make\"}","row_id":3},
            {"role":"user","text":"[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns...","row_id":4},
            {"role":"user","text":"[STILL IN PROGRESS — this is the active request, restated]\nreal ask","row_id":5},
            {"role":"user","text":"[System: The active model changed","display_kind":"model_switch","row_id":6},
            {"role":"user","text":"[ASYNC DELEGATION BATCH COMPLETE — d1]","display_kind":"async_delegation_complete","row_id":7}
        ]""")
        val items = HistoryMapper.map(msgs)
        assertEquals(listOf("real ask"), items.filterIsInstance<ChatItem.User>().map { it.text })
        assertEquals(listOf("Background process finished", "Background Process Finished: make", "Model changed", "Background agent work finished"),
            items.filterIsInstance<ChatItem.Notice>().map { it.text })
        // An attach mid-turn whose trigger was a process notice shows the notice, not a user bubble.
        val attached = io.github.nideta231.hermesremote.data.withInflight(emptyList(),
            JSONObject("""{"user":"[IMPORTANT: Background process proc_9 completed","assistant":"","streaming":true}"""))
        assertTrue(attached.single() is ChatItem.Notice)
    }

    @Test fun inflightTurnIsAppendedWhenAttachingMidRun() {
        val history = listOf<ChatItem>(ChatItem.User("h1", "earlier"), ChatItem.Assistant("h2", "ok"))
        val items = io.github.nideta231.hermesremote.data.withInflight(history,
            JSONObject("""{"user":"now this","assistant":"Working on","streaming":true}"""))
        assertEquals("now this", (items[2] as ChatItem.User).text)
        assertTrue((items[3] as ChatItem.Assistant).streaming)
    }

    @Test fun desktopTurnShowsItsUserMessageLive() {
        val live: (List<ChatItem>, String?, JSONObject?) -> List<ChatItem> = { i, a, f -> io.github.nideta231.hermesremote.data.withLiveUser(i, a, f) }
        val history = listOf<ChatItem>(ChatItem.User("h1", "earlier"), ChatItem.Assistant("h2", "ok"))
        // Typed on the desktop: the reply already started streaming before the fetch came back.
        val streaming = history + ChatItem.Assistant("7", "Sure", streaming = true)
        val items = live(streaming, "h2", JSONObject("""{"user":"from pc","assistant":"","streaming":true}"""))
        assertEquals(listOf("h1", "h2", "live-u-h2", "7"), items.map { it.key })
        assertEquals("from pc", (items[2] as ChatItem.User).text)
        // A second delivery of the same turn adds nothing.
        assertEquals(items, live(items, "h2", JSONObject("""{"user":"from pc"}""")))
        // The phone's own send is already shown (newest user row, no reply yet).
        val own = history + ChatItem.User("u-1", "from phone")
        assertEquals(own, live(own, "u-1", JSONObject("""{"user":"from phone"}""")))
        // ...and still after its reply started streaming before the fetch returned.
        val ownReplying = own + ChatItem.Assistant("8", "On it", streaming = true)
        assertEquals(ownReplying, live(ownReplying, "u-1", JSONObject("""{"user":"from phone"}""")))
        // List rebuilt meanwhile (anchor gone): untouched.
        assertEquals(history, live(history, "gone", JSONObject("""{"user":"x"}""")))
        // First turn of an empty chat goes on top.
        assertEquals("live-u-start", live(emptyList(), null, JSONObject("""{"user":"hi"}""")).single().key)
        // No in-flight turn (already over): untouched.
        assertEquals(history, live(history, "h2", null))
    }

    @Test fun catalogParsesAndDropsUnavailableProviders() {
        // model.options as hermes serve returns it.
        val raw = JSONObject("""
          {"model": "claude-opus-5-5", "provider": "anthropic",
           "providers": [
             {"slug": "anthropic", "name": "Anthropic", "is_current": true,
              "models": ["claude-sonnet-5", "claude-opus-5-5"], "featured_models": ["claude-opus-5-5"]},
             {"slug": "openai", "name": "OpenAI", "authenticated": false, "models": ["gpt-6"]},
             {"slug": "moa", "name": "Mixture of Agents", "models": ["default", "gone"], "unavailable_models": ["gone"]}]}""")
        val cat = parseCatalog(raw)
        assertEquals("claude-opus-5-5", cat.currentModel)
        // featured first inside a provider; unauthenticated providers and unavailable models dropped
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5", "default"), cat.options.map { it.id })
        assertEquals("Mixture of Agents", cat.options.last().providerName)
        assertEquals("claude-opus-5-5", cat.selected?.id)
    }

    @Test fun sessionSummaryCarriesPinnedFlag() {
        val s = parseSession(JSONObject("""{"id":"a","title":"t","message_count":3,"pinned":true,"model":"m"}"""))
        assertTrue(s.pinned)
        assertEquals("m", s.model)
        assertFalse(parseSession(JSONObject("""{"id":"b"}""")).pinned)
    }

    @Test fun tailnetGuard() {
        assertTrue(Tailnet.isAllowedHost("100.64.0.10"))
        assertTrue(Tailnet.isAllowedHost("mypc.tail0000.ts.net"))
        assertTrue(Tailnet.isAllowedHost("[fd7a:115c:a1e0::10]"))
        // RFC1918 is a valid bridge host too; the LAN is an accepted transport.
        assertTrue(Tailnet.isAllowedHost("192.168.1.10"))
        assertFalse(Tailnet.isAllowedHost("100.128.0.1"))
        assertFalse(Tailnet.isAllowedHost("evil.com"))
        assertFalse(Tailnet.isAllowedHost("ts.net.evil.com"))
    }

    @Test fun pairingUri() {
        val p = PairingParser.parseUri("hermesremote://pair?v=1&url=http%3A%2F%2F100.64.0.10%3A8650&device=phone&token=hrb_abcdefghijklmnopqrstuvwxyz")
        assertEquals("http://100.64.0.10:8650", p.url)
        assertEquals("phone", p.device)
        // A LAN address only with HTTPS + the certificate pin (v2); never plain HTTP.
        val pin = "xmgVkYVEEPnn5I36kWsDCLEGBwMMT3_Lds2BLuH9gTg"
        val lan = PairingParser.parseUri("hermesremote://pair?v=2&url=https%3A%2F%2F192.168.1.5%3A8650&device=x&token=hrb_abcdefghijklmnopqrstuvwxyz&pin=$pin")
        assertEquals("https://192.168.1.5:8650", lan.url)
        assertEquals(pin, lan.pin)
        assertThrows(IllegalArgumentException::class.java) {  // plain-HTTP LAN: token would cross Wi-Fi in clear
            PairingParser.parseUri("hermesremote://pair?v=1&url=http%3A%2F%2F192.168.1.5%3A8650&device=x&token=hrb_abcdefghijklmnopqrstuvwxyz")
        }
        assertThrows(IllegalArgumentException::class.java) {  // v2 without a pin
            PairingParser.parseUri("hermesremote://pair?v=2&url=https%3A%2F%2F192.168.1.5%3A8650&device=x&token=hrb_abcdefghijklmnopqrstuvwxyz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingParser.parseUri("hermesremote://pair?v=1&url=http%3A%2F%2F203.0.113.9%3A8650&device=x&token=hrb_abcdefghijklmnopqrstuvwxyz")
        }
        assertThrows(IllegalArgumentException::class.java) { PairingParser.validate("http://100.64.0.10:8650", "nope") }
        assertNull(runCatching { PairingParser.parseUri("https://example.com") }.getOrNull())
    }
}

/** In-memory SharedPreferences: exercises DraftStore's real logic without Robolectric. */
private class FakePrefs : android.content.SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit() = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String?, Any?>()
        private val removed = mutableSetOf<String?>()
        override fun putString(k: String?, v: String?) = also { pending[k] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = also { pending[k] = v }
        override fun putInt(k: String?, v: Int) = also { pending[k] = v }
        override fun putLong(k: String?, v: Long) = also { pending[k] = v }
        override fun putFloat(k: String?, v: Float) = also { pending[k] = v }
        override fun putBoolean(k: String?, v: Boolean) = also { pending[k] = v }
        override fun remove(k: String?) = also { removed.add(k) }
        override fun clear() = also { map.keys.toList().forEach(removed::add) }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() = flush()
        private fun flush() {
            pending.forEach { (k, v) -> if (k != null) map[k] = v }
            removed.forEach { k -> if (k != null) map.remove(k) }
            pending.clear(); removed.clear()
        }
    }
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class DraftStoreTest {
    private val store = io.github.nideta231.hermesremote.data.DraftStore(FakePrefs())

    @Test fun draftSurvivesReopeningTheSession() {
        store.put("s1", "half written")
        assertEquals("half written", store.get("s1"))
        assertEquals("", store.get("s2"))
    }

    @Test fun draftsAreSeparatePerSessionAndForNewChats() {
        store.put(null, "unsent new chat")
        store.put("s1", "unsent s1")
        assertEquals("unsent new chat", store.get(null))
        assertEquals("unsent s1", store.get("s1"))
        store.clear("s1")
        assertEquals("", store.get("s1"))
        assertEquals("unsent new chat", store.get(null))
    }

    @Test fun blankDraftIsRemovedNotStored() {
        store.put("s1", "text")
        store.put("s1", "   ")
        assertEquals("", store.get("s1"))
    }

    @Test fun pruneKeepsLiveSessionsAndCurrentDrafts() {
        store.put("s1", "a"); store.put("gone", "b"); store.put(null, "c")
        store.prune(keep = setOf("s1"), keepText = mapOf("__new__" to "c"))
        assertEquals("a", store.get("s1"))
        assertEquals("", store.get("gone"))
        assertEquals("c", store.get(null))
    }
}

class EndpointResolverTest {
    private val addrs = mapOf("lan" to listOf("192.168.1.10"), "tailnet" to listOf("100.64.0.10", "fd7a:115c:a1e0::1"))

    @Test fun lanAndTailnetHostsAreBothAccepted() {
        assertTrue(Tailnet.isAllowedHost("192.168.1.10"))
        assertTrue(Tailnet.isAllowedHost("10.0.0.5"))
        assertTrue(Tailnet.isAllowedHost("172.16.4.4"))
        assertTrue(Tailnet.isAllowedHost("100.64.0.10"))
        assertTrue(Tailnet.isAllowedHost("mypc.tail0000.ts.net"))
        // 172.15/172.32 are outside RFC1918; a public address is never a bridge.
        assertFalse(Tailnet.isAllowedHost("172.32.0.1"))
        assertFalse(Tailnet.isAllowedHost("203.0.113.9"))
        assertFalse(Tailnet.isAllowedHost("example.com"))
        assertFalse(Tailnet.isAllowedHost("8.8.8.8"))
    }

    @Test fun lanAndTailnetAreNotConfused() {
        assertEquals(Transport.LAN, EndpointResolver.transportOf("192.168.1.10"))
        assertEquals(Transport.TAILNET, EndpointResolver.transportOf("100.64.0.10"))
        assertTrue(Tailnet.isTailnetHost("100.64.0.10"))
        assertFalse(Tailnet.isLanHost("100.64.0.10"))
    }

    @Test fun autoPrefersLanThenFallsBackToTailscale() {
        // Even when the saved address is the tailnet one (e.g. after a manual switch), AUTO
        // must try the LAN first; that is what "Tailscale is optional" means.
        val order = EndpointResolver.resolve("http://100.64.0.10:8650", addrs, TransportMode.AUTO).map { it.host }
        assertEquals(listOf("192.168.1.10", "100.64.0.10", "fd7a:115c:a1e0::1"), order)
    }

    @Test fun manualModeRestrictsToThatNetwork() {
        val ts = EndpointResolver.resolve("http://192.168.1.10:8650", addrs, TransportMode.TAILNET).map { it.host }
        assertEquals(listOf("100.64.0.10", "fd7a:115c:a1e0::1"), ts)
        val lan = EndpointResolver.resolve("http://100.64.0.10:8650", addrs, TransportMode.LAN).map { it.host }
        assertEquals(listOf("192.168.1.10"), lan)
    }

    @Test fun pairedLanAddressIsTriedFirst() {
        val order = EndpointResolver.resolve("http://192.168.1.10:8650", addrs, TransportMode.AUTO).map { it.host }
        assertEquals("192.168.1.10", order.first())
    }

    @Test fun forcedTransportOnlyOffersThatNetwork() {
        val lan = EndpointResolver.resolve("http://100.64.0.10:8650", addrs, force = Transport.LAN)
        assertEquals(listOf("192.168.1.10"), lan.map { it.host })
        val ts = EndpointResolver.resolve("http://192.168.1.10:8650", addrs, force = Transport.TAILNET)
        assertEquals(listOf("100.64.0.10", "fd7a:115c:a1e0::1"), ts.map { it.host })
    }

    @Test fun worksWithNoAdvertisedAddresses() {
        // An older bridge has no `addresses`; the paired address must still be used.
        val only = EndpointResolver.resolve("http://100.64.0.10:8650", null)
        assertEquals(1, only.size)
        assertEquals("http://100.64.0.10:8650", only.first().url)
    }

    @Test fun noDuplicateHostsAndPortIsCarried() {
        val dupes = mapOf("lan" to listOf("192.168.1.10", "192.168.1.10"), "tailnet" to listOf("192.168.1.10"))
        val eps = EndpointResolver.resolve("http://192.168.1.10:9999", dupes)
        assertEquals(eps.map { it.host }.distinct().size, eps.size)
        assertTrue(eps.all { it.port == 9999 })
    }

    @Test fun ipv6HostIsBracketedInUrl() {
        val ep = EndpointResolver.resolve("http://[fd7a:115c:a1e0::1]:8650", mapOf("tailnet" to listOf("fd7a:115c:a1e0::1")))
        assertEquals("http://[fd7a:115c:a1e0::1]:8650", ep.first().url)
    }
}

class NotifierTextTest {
    @Test fun markdownIsFlattenedForTheShade() {
        val md = "## Done\n\nI fixed **two** bugs in `app.py`, see [the PR](https://x.y/1).\n\n```kotlin\nval a = 1\n```\n\n\n\nBye"
        val out = io.github.nideta231.hermesremote.data.Notifier.plain(md)
        assertEquals("Done\n\nI fixed two bugs in app.py, see the PR.\n\n[code]\n\nBye", out)
    }
}

class PairingFallbackTest {
    private val pin = "xmgVkYVEEPnn5I36kWsDCLEGBwMMT3_Lds2BLuH9gTg"

    @Test fun alternatesAreReadAndFiltered() {
        val p = PairingParser.parseUri("hermesremote://pair?v=2&url=https%3A%2F%2F192.168.1.10%3A8650&device=phone" +
            "&token=hrb_abcdefghijklmnopqrstuvwxyz&pin=$pin" +
            "&alt=http%3A%2F%2F100.64.0.10%3A8650%2Chttp%3A%2F%2F8.8.8.8%3A8650%2Chttp%3A%2F%2F10.0.0.9%3A8650")
        assertEquals("https://192.168.1.10:8650", p.url)
        // Public address dropped; plain-HTTP LAN dropped; the tailnet fallback is kept.
        assertEquals(listOf("http://100.64.0.10:8650"), p.alternates)
    }

    @Test fun oldTailscaleCodesStillWork() {
        val p = PairingParser.parseUri("hermesremote://pair?v=1&url=http%3A%2F%2F100.64.0.10%3A8650&device=x&token=hrb_abcdefghijklmnopqrstuvwxyz")
        assertTrue(p.alternates.isEmpty())
        assertNull(p.pin)
    }

    @Test fun tailscaleIpv6IsNotLan() {
        assertFalse(Tailnet.isLanHost("fd7a:115c:a1e0::10"))
        assertTrue(Tailnet.isTailnetHost("fd7a:115c:a1e0::10"))
        assertTrue(Tailnet.isLanHost("fd12:3456::1"))
    }

    @Test fun tokenOnlyGoesOverAVerifiedLink() {
        assertTrue(Transport.allowsToken("100.64.0.10", https = false, pinned = false))
        assertTrue(Transport.allowsToken("192.168.1.10", https = true, pinned = true))
        assertFalse(Transport.allowsToken("192.168.1.10", https = false, pinned = true))
        assertFalse(Transport.allowsToken("192.168.1.10", https = true, pinned = false))
        assertFalse(Transport.allowsToken("8.8.8.8", https = true, pinned = true))
    }

    @Test fun lanEndpointsAreHttps() {
        assertEquals("https://192.168.1.10:8650", Endpoint(Transport.LAN, "192.168.1.10", 8650).url)
        assertEquals("http://100.64.0.10:8650", Endpoint(Transport.TAILNET, "100.64.0.10", 8650).url)
    }
}

class LanDiscoveryHostsTest {
    @Test fun advertisedAddressesWinOverADockerResolve() {
        // Avahi announces on docker0 too; the resolver may hand back 172.17.0.1.
        assertEquals(listOf("192.168.1.20"), LanDiscovery.hostsFrom("192.168.1.20", "172.17.0.1"))
    }

    @Test fun olderBridgeFallsBackToTheResolvedHost() {
        assertEquals(listOf("192.168.1.20"), LanDiscovery.hostsFrom(null, "192.168.1.20"))
    }

    @Test fun garbageAndPublicAddressesAreIgnored() {
        assertEquals(emptyList<String>(), LanDiscovery.hostsFrom("8.8.8.8,evil.example", null))
    }
}
