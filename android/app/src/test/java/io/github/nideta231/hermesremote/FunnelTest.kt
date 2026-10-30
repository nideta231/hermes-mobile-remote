package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Endpoint
import io.github.nideta231.hermesremote.data.EndpointResolver
import io.github.nideta231.hermesremote.data.Funnel
import io.github.nideta231.hermesremote.data.FunnelTicket
import io.github.nideta231.hermesremote.data.Transport
import io.github.nideta231.hermesremote.data.TransportMode
import io.github.nideta231.hermesremote.data.parseFunnel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelTest {
    @Test fun onlyHttpsTsNetIsAFunnelAddress() {
        assertTrue(Funnel.isFunnelUrl("https://mypc.tail0000.ts.net"))
        assertTrue(Funnel.isFunnelUrl("https://mypc.tail0000.ts.net:8443"))
        assertFalse(Funnel.isFunnelUrl("http://mypc.tail0000.ts.net"))  // never plain http on the internet
        assertFalse(Funnel.isFunnelUrl("https://evil.example.com"))
        assertFalse(Funnel.isFunnelUrl("https://192.168.1.10:8651"))
    }

    @Test fun funnelEndpointKeepsItsPortAndIsNeverResolvedAsADirectRoute() {
        val ep = Endpoint.funnel("https://mypc.tail0000.ts.net")!!
        assertEquals(Transport.FUNNEL, ep.transport)
        assertEquals("https://mypc.tail0000.ts.net", ep.url)
        assertEquals("https://mypc.tail0000.ts.net:10000", Endpoint.funnel("https://mypc.tail0000.ts.net:10000")!!.url)
        assertNull(Endpoint.funnel("http://mypc.tail0000.ts.net"))
        assertNull(Endpoint.funnel(null))
        val all = EndpointResolver.resolve("https://192.168.1.10:8651",
            mapOf("lan" to listOf("192.168.1.10"), "tailnet" to listOf("100.64.0.5")), TransportMode.AUTO)
        assertTrue(all.none { it.transport == Transport.FUNNEL })  // the app adds it last, only when allowed
        assertTrue(Transport.allowsToken("mypc.tail0000.ts.net", https = true, pinned = false))
    }

    @Test fun unlockTicketExpiresBeforeTheBridgeDoes() {
        FunnelTicket.set("hrt_x", 300, now = 0)
        assertEquals("hrt_x", FunnelTicket.current(now = 289_000))
        assertNull(FunnelTicket.current(now = 290_000))  // 10 s early, so a request never races the expiry
        FunnelTicket.clear()
        assertFalse(FunnelTicket.valid())
    }

    @Test fun unlockMessageMatchesTheBridge() {
        assertEquals("hermes-remote-funnel-unlock:n1:d1", String(Funnel.unlockMessage("n1", "d1")))
    }

    @Test fun adminFunnelParses() {
        val f = parseFunnel(JSONObject("""{"available": false, "reason": "turn on HTTPS", "on": false, "url": null,
            "ports": [443, 8443, 10000], "devices": [{"id": "a", "name": "Tablet", "since": 1.0, "this": true}]}"""))
        assertFalse(f.available); assertEquals("turn on HTTPS", f.reason); assertNull(f.url)
        assertEquals(listOf(443, 8443, 10000), f.ports)
        assertTrue(f.devices.single().isThis)
        assertNull(f.enableUrl)
        assertEquals("https://login.tailscale.com/f/funnel?node=n1",
            parseFunnel(JSONObject("""{"enable_url": "https://login.tailscale.com/f/funnel?node=n1"}""")).enableUrl)
        assertNull(parseFunnel(JSONObject("""{"enable_url": "https://evil.example.com/x"}""")).enableUrl)
    }
}
