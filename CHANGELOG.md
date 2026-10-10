# Changelog

Releases are git tags; the version in the app comes from the tag. `git log v0.9.0..vX.Y.Z` is the
authoritative history — this file only summarises what is worth knowing before upgrading.

## v1.2.0

- Markdown tables render as real tables: aligned columns, header row, borders, and sideways
  scrolling for wide tables instead of raw `| a | b |` text.

## v1.1.0

- Pair several PCs and switch between them from the drawer; each PC is named by its hostname.
- Pair from a QR picture (screenshot or photo) instead of the camera.
- Ctrl+Enter sends from a hardware keyboard; Enter adds a newline.

## v1.0.0

**Breaking: update the bridge and the app together.** A 1.x bridge does not serve 0.x apps, and a
1.x app cannot use a 0.x bridge. Re-run `./install.sh` (or `install.cmd`), then update the app from
its System tab.

The phone is now another window of the Hermes the desktop app runs. The bridge no longer drives
Hermes' HTTP runs API; it relays one authenticated WebSocket to the desktop's own `hermes serve`
(found through Hermes' spawn ledger, or started when the desktop is closed).

- Replies stream token by token on both screens, wherever the turn was started. No more polling
  or "running on another device".
- The session list shimmers for sessions working anywhere, like the desktop sidebar.
- Approvals and clarify questions can be answered on either screen; answering on one clears the
  other.
- Every slash command works, including `/retry`, `/undo`, `/btw` and `/background`, because it
  runs against the live agent.
- Model and reasoning are set on the chat, as the desktop does.
- Background notifications now cover turns started on the PC in chats opened on the phone.
- New animations: steady text reveal, typing indicator, shimmering rows, spring entrances.
- Removed: the Desktop (RDP) page, the `/v1/runs`, `/v1/models`, `/v1/commands`, `/sync` and
  `/v1/desktop` endpoints, and the installer step that enabled Hermes' API server.
- `config.toml` keys from 0.x are accepted and ignored.

## v0.9.0

Mobile-first UI overhaul. Navigation became a bottom bar (Agent, Sessions, Desktop, System); on
phones the session list is a modal drawer, and at 720 dp and wider it is a fixed pane beside the
chat.

## v0.8.1

Packaging fix for the release workflow: `apksigner` labels its output `Signer #1` in some
build-tools versions and `V2 Signer` in others, so the verification step now matches on the
certificate digest instead of the label.

## v0.8.0

- Reasoning-effort picker, sent per run as `model_options.reasoning_effort`.
- Slash commands and skills, executed on the PC through Hermes' TUI gateway.
- In-app updates from tagged GitHub Releases, with the APK digest and signing certificate checked.
- The application id became `io.github.nideta231.hermesremote`.

## Earlier

The first public commit: the Android app, the bridge, the installer, and the LAN-over-pinned-TLS
and Tailscale connection model described in [docs/SECURITY.md](docs/SECURITY.md).
