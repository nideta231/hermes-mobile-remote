# Security model

What the bridge protects, from whom, and where it deliberately refuses to help. Read
[ARCHITECTURE.md](ARCHITECTURE.md) first for how the pieces fit together.

The short version: the thing worth stealing is Hermes' backend session token, and it never leaves
the PC. The thing worth attacking is the bridge, and it will only talk to devices you paired, from
networks you chose, and pass on only what the desktop app itself does.

## Assets

| Asset | Where it lives | Who can reach it |
|---|---|---|
| Hermes backend session token | Inside the running `hermes serve`; the bridge reads it from loopback at runtime and keeps it in memory | Processes of your user on the PC |
| Device tokens | Shown once at pairing; only their SHA-256 is stored | The paired phone, in its Keystore |
| Session content | Hermes' own store | Anything that can talk to Hermes |
| TLS private key | `~/.config/hermes-remote/tls/`, mode 600 | The PC user |
| Local control secret | `~/.config/hermes-remote/local-secret` (Linux) or `%LOCALAPPDATA%\hermes-remote\config\local-secret` (Windows); 32 random bytes in hex | Processes of your user on the PC |

## Guarantees

**Hermes stays on loopback.** `hermes serve` binds `127.0.0.1`. Its session token is read from
loopback, held in one module (`backend.py`), and never logged, never sent to a device, never
included in an error message.

**The phone gets the desktop's chat surface, not the backend.** The relay only forwards the
JSON-RPC methods the desktop's chat view uses; anything else (file access helpers, config writes
beyond the chat's model and reasoning, process control) is refused before it reaches Hermes.
Answers to the agent's questions are accepted only by their `srq-` request id.

**The bridge never binds a wildcard.** It refuses to start on `0.0.0.0` or `::` and lists explicit
addresses instead. What it serves depends on where you are:

| Address | Served when | Transport |
|---|---|---|
| `127.0.0.1` | always | plain HTTP |
| Tailscale addresses | Tailscale is up | plain HTTP inside WireGuard |
| Wi-Fi address | LAN enabled **and** the network is trusted | HTTPS, pinned certificate |

On a network you have not trusted, the LAN listener does not exist. There is no setting that
serves an untrusted network.

**Networks are recognised, not named.** A network counts as yours when the NetworkManager
connection profile *and* the router's MAC address match a trusted entry. A hotspot that copies
your Wi-Fi name is a different network, and stays untrusted.

**Each device is separate.** Every device gets its own token, stored on the PC only as a SHA-256
hash. Revoking one device takes effect on its next request and does not affect the others. On the
phone the token is encrypted with an Android Keystore key and excluded from backup.

**The desktop app's control API is local only.** The desktop app manages the bridge through `/v1/local/*`. A request is served only if it comes from a loopback address and carries the local secret in the `X-Hermes-Local` header. Anything else gets `403 forbidden_local`, including a tailnet or LAN peer that has the secret. The secret is 32 random bytes in hex, created on first start and created again if it is deleted; on Linux it is mode 0600. Deleting it revokes every local client. These routes are never in the relay's method allowlist, and they use no device token and no device rate limit.

**Tailscale requests need both.** A request from a tailnet address must come from your own
Tailscale identity *and* carry a valid device token. Being on the tailnet is not enough.

**LAN requests must be pinned.** The bridge serves a self-signed certificate; its SHA-256 travels
in the pairing QR code, and the app trusts only that certificate, checking it before sending the
token. A different device that took over the PC's old IP cannot present that certificate, so it
gets nothing. The app additionally refuses, in an interceptor on every request, to send
credentials to a LAN host that is not on pinned TLS.

**Bodies are bounded before authentication.** Chunked bodies are refused (`411`) and bodies over
1 MB are rejected (`413`), so an oversized upload costs nothing. Per-device limits: 240 requests
per minute; on a live connection 600 frames per minute and 16 MB per frame.

**Destructive actions are awkward on purpose.** Deleting a session needs a confirmation in the
app. Password, sudo and secret prompts are never shown on the phone: they are answered on the PC.

**The audit log records access, not content.** `~/.local/state/hermes-remote/audit.log` (mode 600)
holds timestamp, peer, device, method, path, status and duration, and for each live connection
its open and close with per-method call counts. Never tokens, never prompts, never message text.

**Updates are verified twice.** The app's only traffic outside the bridge is the GitHub update
check, and it carries no token. The APK's SHA-256 is compared with the digest GitHub publishes,
and Android itself refuses to install an update signed with a different key. A self-built APK
therefore cannot be replaced by a release build, by design.

The desktop app downloads two things. The first-run setup fetches `uv` 0.13.6 from GitHub Releases only when it is not already on the PATH, and checks its SHA-256 before using it. Desktop updates use the Tauri updater for the AppImage and the Windows installer; the `.deb` and dev builds only show a link to the release page. The updater checks signatures against the public key in `tauri.conf.json`, and the signing key has to be in place before the first desktop release.

**Firewall changes are narrow and confirmed.** Only private address ranges are opened, only on
request, and never automatically. On Windows the rule covers the Private network profile only, and each change asks for elevation (one UAC prompt). Cancelling the prompt changes nothing.

## Gateway hosts

A gateway host has no bridge, so the bridge guarantees above do not apply to it. There is no device
token, no device pin, no rate limit and no method allowlist. The app is the only filter on what it
sends, and the gateway's own login and the network it is reached over are the boundary.

- The password is typed on the gateway's login page in a browser, never in the app.
- The sign-in redirect goes to `127.0.0.1` on a random port. It carries a single-use code, and the
  app checks the `state` before it uses the code.
- Gateway tokens (access and refresh) are in Keystore-backed storage and excluded from backup.
  Refresh rotates them. Signing out clears them on the phone; whether the gateway revokes the
  session is not confirmed.
- Plain `http` is accepted only for Tailscale or LAN addresses. Any other address must use `https`.
- Not checked on a device yet: the browser's loopback redirect, and token expiry in practice.

## What this does not protect against

Stated plainly, because a security document that only lists wins is not useful.

- **A compromised PC.** The bridge runs as your user. Anything that runs as your user can talk to
  Hermes on loopback. Use full-disk encryption.
- **A rooted phone.** Keystore-backed storage raises the cost of token theft; it does not make it
  impossible.
- **Your Hermes agent's own judgement.** Approvals are a speed bump for a *runaway* command, not a
  sandbox. An agent that is talked into running something harmful will still be talked into it.
  `approvals.mode = manual` and a sensible prompt are your real controls.
- **Traffic analysis.** The bridge refuses untrusted networks, but it cannot tell you that someone
  on a network you *did* trust is watching. Use Tailscale when that matters.
- **A stolen PC while it is unlocked and on.** The token store and the key are readable by your
  user account.
- **Another program running as your user.** It can read the local secret and call `/v1/local/*`: pair a new device, revoke one, or trust a network. A device it pairs gets the same access as any other paired phone. On Windows the 0600 mode does not apply; the file gets your user profile's permissions (unchecked: whether that is tight enough).
- **A paired phone is a shell on your PC.** The Terminal page runs your shell as your user (`$SHELL` on Linux; on Windows `pwsh`, `powershell` or `cmd`, or `terminal_shell`), and the
  process list can stop your processes. That is no more than Hermes itself can do, but it is
  direct. Revoke a lost phone at once, or set `terminal = false` (and `monitor = false`) in
  config.toml if you don't want either.
- **Hermes' own backend.** The bridge is a gate in front of it, not a sandbox around it. Anything
  that can reach Hermes on loopback with its session token has more access than the bridge
  passes on.
- **A gateway host's login or network.** A gateway host has no bridge in front of it. Anyone who can
  sign in to the gateway, or reach it over the network, has what the phone has.

## Reporting a vulnerability

See [SECURITY.md](../SECURITY.md).

## Tailscale Funnel (internet access)

Off until the user turns it on from the phone (PC bridge page) and allows each phone separately.

- Funnel and Serve proxy to the bridge's loopback listener. The guard never treats them as local:
  a request with `Tailscale-Funnel-Request: ?1` (set by tailscaled, which strips client copies) is
  internet traffic; one with `Tailscale-User-Login` / `X-Forwarded-Host` is Serve and needs an
  allowed tailnet login.
- Over Funnel a request needs the device token AND that phone's Funnel key (`X-Funnel-Key`, a second
  secret issued only from the LAN/tailnet, stored hashed in `funnel.json`).
- Open over Funnel: identity, status, chat list, media, blueprints, the live socket. Everything else
  (terminal, admin, monitor, notes, cron, settings, folders...) needs an unlock ticket
  (`X-Funnel-Unlock`, 5 minutes) bought by signing a one-use challenge with an EC P-256 Keystore key
  that only signs after fingerprint/screen lock. `/v1/local/*`, enrolling and `/metrics` are refused.
- On the live socket over Funnel the vault methods and `config.set yolo` are refused.
- 10 bad tokens/keys from one internet address in 10 minutes lock it out for 15; every refusal is audited.
