package io.github.nideta231.hermesremote.data

/**
 * How the app reaches the bridge, and how it chooses when more than one way works.
 *
 * Tailscale and the local network are both optional transports. The bridge advertises every
 * address it answers on, so the app can try them in order rather than being pinned to whichever
 * one was in the pairing QR code — being at home should not depend on a VPN being up.
 */
enum class Transport { LAN, TAILNET, FUNNEL;
    companion object {
        /** Whether a request carrying the device token may go to this host. */
        fun allowsToken(host: String, https: Boolean, pinned: Boolean): Boolean = when {
            Tailnet.isTailnetHost(host) -> true            // WireGuard encrypts + authenticates
            Tailnet.isLanHost(host) -> https && pinned      // only after the pinned TLS handshake
            else -> false
        }
    }
}

/** What the user asked for; `AUTO` prefers the LAN and falls back to Tailscale. */
enum class TransportMode { AUTO, LAN, TAILNET }

data class Endpoint(val transport: Transport, val host: String, val port: Int) {
    // java.net.URI.host keeps the brackets on an IPv6 literal, so they must not be doubled here.
    // LAN is HTTPS (pinned certificate), Tailscale plain HTTP inside WireGuard.
    val url: String get() = if (transport == Transport.FUNNEL) "https://$host" + (if (port == 443) "" else ":$port")
        else "${if (transport == Transport.LAN) "https" else "http"}://${if (host.contains(':')) "[$host]" else host}:$port"

    val label: String
        get() = when (transport) {
            Transport.LAN -> "Local network ($host)"
            Transport.TAILNET -> "Tailscale ($host)"
            Transport.FUNNEL -> "Internet ($host)"
        }

    companion object {
        /** The Funnel endpoint for the bridge's advertised public [url], or null when it isn't one. */
        fun funnel(url: String?): Endpoint? {
            if (url == null || !Funnel.isFunnelUrl(url)) return null
            val u = java.net.URI(url.trim())
            return Endpoint(Transport.FUNNEL, u.host, if (u.port > 0) u.port else 443)
        }
    }
}

/**
 * Builds the ordered list of addresses to try.
 *
 * Ordering is the whole point: LAN first (fast, no VPN), Tailscale second. A manual choice
 * (`mode` LAN/TAILNET, or `force`) restricts the list to that network.
 */
object EndpointResolver {

    fun resolve(
        paired: String,
        bridgeAddresses: Map<String, List<String>>? = null,
        mode: TransportMode = TransportMode.AUTO,
        force: Transport? = null,
    ): List<Endpoint> {
        val pairedHost = hostOf(paired) ?: return emptyList()
        val port = portOf(paired)
        val out = mutableListOf<Endpoint>()

        fun add(transport: Transport, hosts: List<String>) {
            hosts.filter { it.isNotBlank() }.forEach { host ->
                val ep = Endpoint(transport, host, port)
                if (out.none { it.host == ep.host }) out.add(ep)
            }
        }

        // The paired address is always a candidate, but in its transport's group, not first:
        // after a switch to Tailscale it is the tailnet address, and must not outrank the LAN.
        val lan = bridgeAddresses.orEmpty()["lan"].orEmpty()
        val tailnet = bridgeAddresses.orEmpty()["tailnet"].orEmpty()
        val pairedTransport = transportOf(pairedHost)
        fun group(t: Transport) = when (t) {
            Transport.LAN -> (if (pairedTransport == t) listOf(pairedHost) else emptyList()) + lan
            Transport.TAILNET -> (if (pairedTransport == t) listOf(pairedHost) else emptyList()) + tailnet
            Transport.FUNNEL -> emptyList()  // never resolved here: only tried last, by the app, when allowed
        }

        val only = force ?: when (mode) {
            TransportMode.LAN -> Transport.LAN
            TransportMode.TAILNET -> Transport.TAILNET
            TransportMode.AUTO -> null
        }
        if (only != null) {
            add(only, group(only))
            // An older bridge advertises nothing: the paired address beats an empty list.
            return out.ifEmpty { listOf(Endpoint(pairedTransport, pairedHost, port)) }
        }
        add(Transport.LAN, group(Transport.LAN))
        add(Transport.TAILNET, group(Transport.TAILNET))
        return out
    }

    fun transportOf(host: String): Transport =
        if (Tailnet.isLanHost(host)) Transport.LAN else Transport.TAILNET

    fun hostOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.trim('[', ']')
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun portOf(url: String): Int = runCatching {
        java.net.URI(url.trim()).port.takeIf { it > 0 } ?: 8650
    }.getOrDefault(8650)
}