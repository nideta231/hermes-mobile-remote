# Changelog

Releases are git tags; the version in the app comes from the tag. `git log v0.9.0..vX.Y.Z` is the
authoritative history — this file only summarises what is worth knowing before upgrading.

## Unreleased

- Switching between paired PCs no longer loses the profile dropdown. A reply from the PC you just
  left (its profile list, chats, scheduled jobs, settings or vault) could land on the one you
  switched to and replace its data; every reply is now checked against the PC and profile it was
  asked for.

## v1.13.1

- Composer: Shift+Enter on a hardware keyboard inserts a newline at the cursor (replacing any
  selection). Enter still inserts a newline; Ctrl+Enter still sends.

## v1.13.0

- Chat stays in sync. Opening (or coming back to) a chat while Hermes is still replying no longer
  shows the prompt a second time at the bottom with part of the reply under it: the running turn
  is matched to the prompt Hermes already stored, and only the not-yet-stored part of the reply
  is added.
- Leaving the app or turning the screen off mid-reply no longer leaves the reply cut off. Coming
  back pings the link and reconnects if it went stale, every finished turn re-reads the chat from
  the PC, and the final reply text replaces a partial one. No pull-to-refresh needed.
- Edit a sent message: long-press it → Edit. Like the desktop, Hermes drops that message and
  everything after it and answers the edited text. Available once the message's reply finished.
- Vault page (drawer → Vault): see the logins, cards and addresses Hermes can fill in, add new
  ones, edit (re-enter the secret; the new version replaces the old) and delete. Secrets are never
  shown on the phone. Password-manager items are listed read-only; unlocking them stays on the PC.
- Bridge: `vault.list`, `vault.sources`, `vault.add`, `vault.remove` on the relay allowlist.
  Update the PC bridge for the vault page.

## v1.12.0

- Files in chat. The paperclip next to the picture button opens the system file picker (any
  type: PDF, docs, spreadsheets, code; up to 6 per message, 10 MB each). Each file is staged on
  the PC through Hermes' `file.attach` and the message carries its `@file:` reference, as on the
  desktop. Sent files show as "📎 name" in the chat, also in history.
- Scheduled: a "New job" button. Give it a name, a schedule (`every 30m`, `every day 8am`,
  `weekdays at 9am`, a cron expression) and what Hermes should do. Jobs made from the phone run
  with the profile's tools and save their result locally; editing, deleting, scripts and other
  delivery targets stay on the PC.
- Bridge: `POST /v1/cron/jobs` (prompt jobs only, delivery forced to local) and `file.attach`
  on the relay allowlist. Update the PC bridge for both.

## v1.11.1

- Scrolling far up an old chat no longer freezes and then crashes. Pictures are decoded at screen
  size (1080 px) and kept in memory instead of at full size on every scroll, and huge
  messages, code blocks and command outputs are clipped for display (Copy still copies all
  of it). Parsed replies are reused rather than parsed again each time they scroll back into view.
- Bridge: a phone hotspot (tethering) stays trusted. Android gives a hotspot a new random MAC
  each time it starts, so the bridge saw a new network each time and asked to trust it again,
  piling up duplicates. Networks whose gateway has a random (locally administered) MAC are now
  trusted by their connection profile (SSID + password). Old duplicates collapse into one entry
  on the next connect. Routers with a real MAC still need that exact router.

## v1.11.0

- Pictures in chat. The picture button in the composer opens the photo picker (up to 6 per
  message, no storage permission). They are sent with the message, scaled to 2048 px JPEG first,
  and Hermes looks at them with its vision tool as on the desktop. A picture-only message works.
- Pictures show in the chat: yours (also ones sent from the desktop), and images Hermes hands back
  as `MEDIA:` lines. Tap one to open it full screen; pinch to zoom.
- Sudo, secret and password-manager prompts can be answered on the phone: unlocking a vault
  (1Password, Bitwarden), saving a site login, a 2FA code, a sudo password or a setup secret. A
  masked card slides up like the desktop's. The value goes once over the paired link and is never
  stored on the phone; the transcript only says "sent" or "skipped". A notification says when one
  waits.
- The drawer grouped by project no longer reorders while a reply streams. Projects you created keep
  their order, auto-found repos follow A-Z, Home last.
- Bridge: `GET /v1/media?path=` returns an image a chat shows. Only image files inside the
  `images/`, `screenshots/` or `cache/` folders of the profiles the phone may use; symlinks out
  and `..` are refused. Needs the updated bridge (run `./install.sh` on the PC).

## v1.10.0

- Hermes profiles. If your PC has more than one Hermes profile, the drawer shows the one in use
  under the PC name; tap it to switch. The chat list, Settings (approval mode, default model) and
  new chats then belong to that profile. Each PC remembers its choice, and a notification opens
  its chat in the right profile. Needs the updated bridge (run `./install.sh` on the PC); with an
  older bridge the app stays on the default profile as before.
- The chat header names the Hermes profile the chat runs in (next to its status), when the PC has
  more than one profile.
- Bridge: `GET /v1/profiles` lists them, and the sessions, approvals and default model endpoints
  take `?profile=` so they act on one profile. The default profile works exactly as before. A new
  `allowed_profiles` key in `config.toml` limits which profiles the phone may use (empty: all).
- Scheduled jobs. *Scheduled* in the drawer lists the cron jobs of the Hermes profile in use with
  their schedule, next and last run and last error; tap one for its recent runs. Run now, pause and
  resume work from the phone; creating and editing jobs stays on the PC.
- Bridge: `GET /v1/cron/jobs`, `GET /v1/cron/jobs/{id}/runs` and `POST /v1/cron/jobs/{id}/pause|resume|trigger`,
  all per profile. Prompts, scripts and delivery targets are not sent to the phone.
- Bridge: a request naming a profile is authenticated first, so an unpaired caller gets 401 and
  learns nothing about which profiles exist.
- Linux tray: a revoked device no longer stays in the Devices menu.

## v1.9.3

- A failed turn (for example "The conversation is too long for this model. Run /compress, then
  /retry." with its details) now shows in red when you open the chat on the phone, like the
  desktop's error banner. Before, it only showed if the phone was watching when it failed.

## v1.9.2

- The drawer no longer jumps around while a reply streams. Live updates now re-read only the
  newest chats and merge them in, instead of re-paging the whole list while it was shifting.
- Chats with the same timestamp keep a fixed order between refreshes.

## v1.9.1

- Enter on a hardware keyboard is a new line again (back to how it was before v1.9.0). Use the
  send button to send.
- The drawer shows 3 chats per date or project by default, like Hermes desktop, then "Show more".
  Change it in Settings > Chat list (3, 5, 10 or All).
- Settings regrouped into clear sections: Connection, Hermes on your PC, Appearance, Chat list,
  About and This device. The accent grid is folded until you tap Accent.
- Markdown: code fences are parsed line by line, so 4-backtick fences, ~~~ fences, indented
  fences and inline triple backticks no longer break the rest of a message.

## v1.9.0

- 30 accent colours, grouped by hue: several blues (sky, ocean, indigo, navy...), greens, reds,
  purples, oranges and neutrals. Your current accent is kept.
- Hardware keyboards: Enter sends, Shift+Enter starts a new line.
- New project: in the project picker, "+ New project" creates a project on the PC from a name
  and a folder path (e.g. ~/Workspace/my-app) and starts the chat there. Needs the updated bridge.
- Project folders in the drawer remember being collapsed after the app restarts.

## v1.8.1

- Theme is now two separate choices: Mode (System, Light, Dark, Midnight) and Accent
  (Gold, Ocean, Forest, Violet, Rose). Every accent works in every mode, so there are no
  more duplicate same-colour themes. Your previous pick carries over.

## v1.8.0

- Theme picker in Settings.
- Default model in Settings: one model the PC uses for every new chat, kept across restarts.
  Picking a model inside a chat still only changes that chat.
- Drawer: each project has a clear folder header and its chats are indented under a guide line,
  so it is easy to see where a project's chats start and end.
- Linux tray app (bridge/linux/tray.py), like the Windows one: status, checks,
  start/stop/restart, networks, pair/revoke devices, logs, start at login. install.sh adds it to
  the app menu.

## v1.7.0

- New chat in a project: on a new chat, a "No project" pill above the model picks one of the
  PC's projects. The chat starts in that project's folder and shows under it on the desktop.
- Drawer groups now show 5 chats first, then 10 more per "Show more".
- Updates are checked every time the app opens or comes back to the foreground (and hourly while
  open). Repeat checks are conditional, so they don't eat GitHub's rate limit.
- Thinking: Opus replies now carry their thinking text once the PC's Claude subscription plugin
  is patched; the app shows it in the collapsible "Thought" block (no app change needed).

## v1.6.0

- Messages you type on the PC now appear on the phone as soon as the turn starts, not only the
  reply. Before, the phone showed the PC's message only after reopening the chat.
- Your own messages from the phone still show once (no duplicate bubble).

## v1.5.0

- Long chat groups show 15 chats, then a "Show 15 more · N left" row. Search and the drawer
  filters still cover every chat: the app loads the whole list and only draws it in pages.
  The open chat is never hidden, and a new search, filter or PC starts each group at one page.
- Copy buttons you can see, for when long-press isn't handy (mouse, PC, emulator):
  - "Copy" under every finished reply copies the whole answer as markdown.
  - Every code block shows its language and a "Copy" button that copies only the code.
- Updates are checked automatically while the app is open: at start and every 6 hours.
- A new version shows a strip under the chat's top bar (✕ hides it for that version) and a row
  at the top of the drawer.
- Tapping either opens "Update to X": what changed in every version between yours and the new
  one, then Update now.
- After an update the app shows "What's new" once, with the changes since the version you had.
- Settings → Changelog lists every version's changes, read from the app itself (works offline).

## v1.4.0

- Messages you steer into a running task now show your own words, not Hermes' wrapper text.
- Opening a chat no longer floods it with Hermes' own notes shown as your messages. Rows Hermes
  writes itself are now shown the way the desktop shows them:
  - "Background process finished" and "Background agent work finished" become one-line notices,
    using the desktop's own label when Hermes stored one.
  - Model and personality switches, and resumed turns, become notices too.
  - Context-compaction summaries, the "[STILL IN PROGRESS …]" restatement, and system notes are
    hidden.
- PC switcher redesigned like the account switchers in Instagram and Facebook:
  - The drawer header shows the PC in use: a letter avatar, its name, and how many PCs are paired.
  - Tap the header to open "Switch PC": one row per PC with its avatar and host, and a radio mark
    on the PC in use. "Add PC" sits at the bottom.
  - Long-press the header to jump straight to the next PC.
  - Rename, Forget, and Switch are in each PC's ⋮ menu, or long-press its row.
- Chat-list groups (date, project or status) collapse and expand when you tap their header. The
  header shows how many chats the group holds. Collapsed groups are remembered per grouping, and
  a search always opens every group so no match is hidden.
- Release notes in the app and on GitHub are now this full changelog, newest first, so updating
  across several versions shows everything that changed. The update card scrolls.

## v1.3.0

- Chat drawer view options (filter icon next to search), matching the desktop app: group by date,
  project or status; sort by last updated, created, status, tokens or cost; filter by status and
  by project. Projects come from the desktop's project list. Pinned chats stay on top; the choice
  is remembered.
- Bridge: allows the read-only `projects.tree` call. Update the bridge on the PC too.

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
