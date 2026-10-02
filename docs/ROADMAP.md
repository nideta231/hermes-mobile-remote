# Roadmap

Where the project is going, and what is deliberately not on the list. Ordered roughly by how
likely it is to happen.

Nothing here is a promise. If you want something that is not listed, open an issue — see
[CONTRIBUTING.md](../CONTRIBUTING.md).

## Shipped

Roughly, in the order the pieces landed:

- Android app with chat, sessions, pins, rename, delete and search.
- The bridge: pairing, per-device tokens, LAN over pinned HTTPS, Tailscale, trusted networks.
- Approvals in the app, plus switching Hermes' global `approvals.mode`.
- Per-message model picker and reasoning effort.
- Slash commands and skills, executed on the PC through Hermes' TUI gateway.
- Following a session that another surface is driving.
- In-app updates from tagged GitHub Releases.
- Notifications for app-started runs.
- Remote desktop hand-off to aFreeRDP (KRdp).
- Windows support (experimental): a host layer, `install.ps1`, a Scheduled Task and a Windows CI
  job. See [PLATFORMS.md](PLATFORMS.md).
- A demo stack with a synthetic backend, so the app can be tried and recorded without a real
  Hermes.

## Next

**Windows from experimental to supported.** Needs someone to run it on real Wi-Fi and with
Tailscale and report back, because CI has neither. The checklist of what is unproven is in
[PLATFORMS.md](PLATFORMS.md#what-is-verified-on-windows).

**macOS.** The seam exists (`host/macos.py`); the local-network path, a launchd agent and an
installer are missing. Loopback and Tailscale would already work. Needs a Mac to develop on.

**A real end-to-end test in CI.** The live test exists
(`android/.../LiveBridgeTest.kt`, `bridge/tests/e2e_live.py`) but needs a real Hermes, so it only
runs on a maintainer machine. Wiring it to a CI job with the demo backend would let a change to
the event contract be verified automatically.

**Faster approval round-trips.** An approval currently blocks the run until you answer. Pushing
the pending approval to the phone as a notification, so you can approve from the lock screen,
would make long tool chains much less painful on mobile.

**Session search that reaches the server.** Search currently filters the sessions the bridge has
already listed. Pushing the query into the Hermes sessions API would make it scale to hundreds of
conversations.

**Tablet parity.** The wide layout shows sessions beside the chat, but sheets and the approval
dock are still phone-sized.

## Later

**Multiple PCs.** The app holds one pairing. Supporting several bridges — pick a machine per
session — is the most-requested thing and the biggest change, because pairing, drafts and the
"follow another device" logic all assume a single PC.

**A desktop build.** The UI is Compose and not obviously phone-only. A resizable desktop client
would reuse the same bridge.

**Bridge metrics.** A small local status page or Prometheus endpoint for run counts, latency and
approval wait time, for people running the bridge on a shared machine.

## Deliberately not planned

**A cloud relay.** Messages would have to leave the machine that holds the key. The whole design
is local-first: the bridge is the only thing that can talk to Hermes, and it is the thing you
control.

**Hosting the Hermes API for others.** This project is a remote control for an agent you already
run, not a hosting service.

**Anything that widens the network surface.** No UPnP, no port forwarding, no listening on
`0.0.0.0`. A convenience feature that makes the bridge reachable from a network you did not choose
is worth more to an attacker than to the user.

**Sending credentials anywhere.** Device tokens stay on the phone, in the Android Keystore, and
never travel to a third party. The only traffic outside the bridge is the GitHub update check,
which carries no token.
