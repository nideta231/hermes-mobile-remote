# Roadmap

Where the project is going, and what is deliberately not on the list. Ordered roughly by how
likely it is to happen.

Nothing here is a promise. If you want something that is not listed, open an issue — see
[CONTRIBUTING.md](../CONTRIBUTING.md).

## Shipped

Roughly, in the order the pieces landed:

- Android app with chat, sessions, pins, rename, delete and search.
- The bridge: pairing, per-device tokens, LAN over pinned HTTPS, Tailscale, trusted networks.
- Approvals in the app, plus switching Hermes' `approvals.mode`.
- Model picker and reasoning effort.
- In-app updates from tagged GitHub Releases.
- 1.0: the phone shares the desktop app's Hermes backend over one WebSocket. Live streaming in both
  directions, approvals and clarify answerable on either screen, every slash command (including
  `/retry`, `/undo`, `/btw`), background notifications for finished turns and questions.
- Windows support (experimental): a host layer, the Hermes Remote desktop app and a Windows CI
  job. See [PLATFORMS.md](PLATFORMS.md).
- A demo stack with a synthetic backend, so the app can be tried and recorded without a real
  Hermes.
- 1.1: several paired PCs, switched from the drawer, each with its own pairing, drafts and last
  chat. Pairing from a QR picture as well as the camera.
- 1.2–1.5: Markdown tables; drawer grouping, sorting and filtering (date, project, status) with
  collapsible groups; the changelog and "What's new" inside the app.
- 1.7–1.9: new chats in a project, and new projects created from the phone; Hermes' default model
  and the theme (mode and accent) in Settings.
- Tray apps on both platforms: the Windows tray runs the bridge, the Linux tray controls the
  systemd service. On Linux, `install.sh` adds it to the app menu and starts it at login.
- Bridge metrics: a Prometheus endpoint (`GET /metrics`, token required) and
  `hermes-remote-bridge metrics` for request counts, Hermes latency and approval wait time.
- An end-to-end test in CI: the app's real socket and reducer code against the real bridge and the
  demo backend (`demo/e2e.sh`, job `e2e-demo`). `LiveBridgeTest` still covers a real Hermes on a
  maintainer machine.
- 1.39: approve from the lock screen (Deny / Allow buttons on the approval notification).

## Next

**Windows from experimental to supported.** Needs someone to run it on real Wi-Fi and with
Tailscale and report back, because CI has neither. The checklist of what is unproven is in
[PLATFORMS.md](PLATFORMS.md#what-is-verified-on-windows).

**macOS.** The seam exists (`host/macos.py`); the local-network path, a launchd agent and an
installer are missing. Loopback and Tailscale would already work. Needs a Mac to develop on.

## Later

**A desktop build.** The UI is Compose and not obviously phone-only. A resizable desktop client
would reuse the same bridge.

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
