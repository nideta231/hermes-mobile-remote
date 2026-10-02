# Architecture

Three pieces, with one rule: the phone never holds a credential that can reach Hermes directly.

```
┌──────────────┐   HTTPS (pinned cert)   ┌──────────────┐   HTTP + bearer key   ┌──────────────┐
│ Android app  │ ──────────────────────► │    bridge    │ ────────────────────► │ Hermes API   │
│              │   HTTP inside WireGuard │  (your PC)   │      127.0.0.1        │  127.0.0.1   │
│ 1 token      │ ◄────────────────────── │ 1 API key    │ ◄──────────────────── │ :8642        │
└──────────────┘        SSE              └──────────────┘                       └──────────────┘
```

| Piece | Lives in | Responsibility |
|---|---|---|
| App | `android/` | UI, one device token, TLS pinning, reconnect and replay |
| Bridge | `bridge/` | Device auth, network policy, run bookkeeping, event replay |
| Hermes | your install | The agent, the tools, the model calls, the session store |

## Why a bridge at all

Hermes' API server speaks bearer-key auth and assumes a trusted network: anything that can reach
it holds a key that can drive an agent with your tools. A phone on cafe Wi-Fi cannot be given that
key.

So the key stays on the PC, behind a process that can say no. The bridge holds the key, exposes a
narrower API on the network, and authenticates each device separately. Compromise of the phone
means a revoked token, not a stolen API key.

## The bridge

A single FastAPI application (`bridge/hermes_remote_bridge/app.py`). One codebase, two listeners:

- **Plain HTTP** on loopback and the node's Tailscale addresses. Inside WireGuard the transport is
  already encrypted and authenticated, so there is nothing to add.
- **HTTPS** on the Wi-Fi address, but only on a network you marked trusted, with a self-signed
  certificate the app pins. Plain HTTP from the LAN is refused.

It refuses to bind `0.0.0.0` or `::`, and watches the network: when the set of addresses it
should serve changes (new Wi-Fi, new DHCP lease, Tailscale up or down, trust granted or revoked) it
restarts its listeners. Otherwise a network change would silently leave the phone unable to reach
it.

### Request pipeline

Every request passes three gates, in this order:

1. **Where from.** The peer address must be loopback, a tailnet address whose `whois` login is
   allowed, or — with LAN enabled — a private address on the HTTPS listener. Anything else is
   `403 forbidden_network`.
2. **How big.** Chunked bodies are refused; bodies over 1 MB are `413`. This runs before
   authentication so an oversized upload is rejected without doing work for the caller.
3. **Who.** `Authorization: Bearer <device token>`, matched against the SHA-256 of the token.
   Unknown or revoked is `401`. Per-device rate limits apply after this (240 requests/min,
   20 runs/min).

Successful requests are appended to an audit log with device, path, status and duration — never
tokens or prompts.

### Run bookkeeping, and why it exists

Hermes' `/v1/runs/{id}/events` is a single-consumer stream with no replay. If the phone's
connection drops, the transport is gone and the missed events are gone with it; if a second client
attached, they would steal events from the first.

So the bridge owns that subscription for the whole life of the run (`bridge/hermes_remote_bridge/runs.py`).
It pumps every event out of Hermes once, buffers it with a monotonic sequence number, and lets any
number of clients attach and re-attach with `Last-Event-ID`. A phone that walks out of Wi-Fi
range and back re-reads from where it stopped.

If the upstream stream dies without a terminal event, the bridge reconciles by polling the run's
status, and gives up with `run.interrupted` rather than hanging. That case is visible in the app
instead of silently stalling.

### Slash commands

Slash commands are not in the HTTP API. Hermes runs them in its TUI gateway
(`python -m tui_gateway.entry`, newline-delimited JSON-RPC over stdio). The bridge keeps one such
child process, resumes the target session in it, runs the command, and closes it again
(`bridge/hermes_remote_bridge/slash.py`).

Only commands that act on persisted state are offered. A command needing a live agent in that
process (`/retry`, `/undo`, `/btw`) would run against an empty agent and report something
misleading, so it is left out rather than shown broken. Skills are always offered: they expand
into a prompt the app sends as a normal run.

## The app

Kotlin and Jetpack Compose, one activity, four pages (`CHAT`, `SETTINGS`, `DESKTOP`, and the
sessions pane) swapped with `AnimatedContent`. On a phone the session list is a modal drawer; at
720 dp and up it is a fixed 320 dp pane beside the chat.

| Concern | Where |
|---|---|
| Transport, pinning, error mapping | `data/BridgeClient.kt`, `data/CertPin.kt` |
| Which path to use, LAN vs Tailscale | `data/EndpointResolver.kt` |
| Folding run events into chat items | `data/Chat.kt` (`LiveReducer`) |
| Persisted history into chat items | `data/Chat.kt` (`HistoryMapper`) |
| Token storage | `data/CredentialStore.kt` (Android Keystore) |
| State, and following other surfaces | `AppViewModel.kt` |
| UI | `ui/ChatScreen.kt`, `ui/Screens.kt`, `ui/Components.kt` |

Two rules shape this code. The device token is only ever sent to a tailnet host, or to a LAN host
over a pinned TLS handshake — an interceptor re-checks that on every request, so a misconfigured
endpoint cannot quietly downgrade to cleartext. And a run started elsewhere is *followed*, not
owned: the app polls `/v1/sessions/{id}/sync` with a cursor (a message count) so it never
re-renders or skips history.

## Data on the phone

The token is encrypted with an Android Keystore key and excluded from backups. Drafts are stored
per session. Session history is not cached in full: the app asks for the tail of a session and
keeps what it has rendered.

## One bridge, one OS layer

The bridge started on Linux and every OS-specific thing it needed (service manager, network
identity, Tailscale socket, firewall, mDNS, the trust prompt) was written against Linux. Those now
sit behind one interface in `bridge/hermes_remote_bridge/host/`, with a module per operating
system. The rest of the code asks the host a question ("which interface has the default route?")
and never checks the platform, so the security model above is the same code on every OS.

Which operating systems exist, how far each is verified, and how to add one:
[PLATFORMS.md](PLATFORMS.md).
