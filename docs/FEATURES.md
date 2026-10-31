# Features

Every feature the app has, how it behaves, and where it stops. Grouped by what the user is
trying to do. For how any of this is implemented, see [ARCHITECTURE.md](ARCHITECTURE.md); for the
security rules behind the connection features, see [SECURITY.md](SECURITY.md).

## Chat

**One Hermes, two windows.** The phone attaches to the same Hermes backend the desktop app uses.
A chat started on either screen shows up on the other immediately, and a turn running on the
desktop streams on the phone token by token, and the other way round. There is nothing to sync.

**Streaming replies.** Text is revealed at a steady pace as it arrives, with a typing indicator
before the first word and the model's reasoning in a collapsible block. Tool calls render as cards
with their arguments, result and duration; a running card shimmers, a failed one is marked red.

**Subagents.** When the agent hands work to subagents (`delegate_task`), a card under the chat
lists them: each one's task, the tool it is using, its tool count, model and running time. Ones
that finish turn green (tap for their answer) or red. *Stop* ends one subagent and leaves the chat
running. *Output* opens a running subagent's live transcript, refreshed every couple of seconds
while it is open. *Steer* sends it an instruction it reads after its current step (hidden when
Hermes says it no longer takes any). Opening a chat while subagents run shows them straight away.

**Steering and stopping.** While a turn is in flight the composer becomes "Steer the running
task…", and the send button turns into a stop button. You can add instructions mid-turn or cancel.

**Drafts.** An unsent message survives switching tabs, rotating the screen and restarting the app.

**Share to Hermes.** Hermes Remote is in Android's share menu. Text and links (Chrome passes the
page title and URL) go into the composer, pictures and files are attached as if picked in the app.
A sheet asks whether it goes to a *New chat* or the chat on screen; nothing is sent until you tap
Send. Shared files are copied into the app's cache straight away (the sending app's permission is
short-lived) and deleted once sent or removed; leftovers are cleared on the next launch. Same
limits as picking: 6 pictures and 6 files of up to 10 MB each, the rest is left out with a note.
Sharing before the phone is paired only shows a hint.

**Sessions.** Swipe in the drawer from the left edge to search, pin, rename and delete chats.
Long-press a session for those actions; deleting asks for confirmation. A session that is working
right now — on any screen — shimmers in the list, like the desktop sidebar.

**Search inside chats.** The drawer's search box filters titles and previews as you type. Pause
for a moment after two or more characters and it also asks Hermes on the PC, which searches the
text of every message (the desktop sidebar's search). Chats found only that way appear under
*In messages* with the matching line; tap one to open it. An older bridge keeps the title filter.

**Background work strip.** When the open chat has background work (running processes, subagents
it started, an active `/loop` or `/heartbeat`), a one-line strip sits above the composer, for
example *2 running · 1 subagent*. Tap it to open the process and subagent panels with Stop and
steer; tap again to close. It hides while an approval or a question is waiting.

**Pinned sessions.** Pins are shared with the Hermes desktop app; pin on either surface.

**Hermes profiles.** If the PC has more than one Hermes profile (`hermes profile list`), the drawer
shows the one in use under the PC name. Tap it to switch: the chat list, the settings (approval
mode, default model) and every new chat then belong to that profile. Each paired PC remembers its
own choice. A notification opens its chat in the profile it belongs to. The PC can limit which
profiles the phone may use with `allowed_profiles` in the bridge's `config.toml`.

## Models and reasoning

**Model picker.** Lists every model your configured providers can actually serve. Providers that
are not authenticated, and models Hermes marks unavailable, are left out. Picking one switches the
open chat, exactly like the desktop's picker; it does not change your global default.

**Reasoning effort.** Off, Minimal, Low, Medium, High, Extra high or Max, set on the open chat.

## PC monitor, terminal and widgets

This replaces the separate PC Glance app and agent; uninstall them once this is set up.

**PC page.** The chip icon in the drawer header. Live CPU, RAM and temperature gauges, disk,
swap, network and disk I/O, per-core bars, and a CPU/RAM/temperature history (1 h to 1 year)
recorded by the bridge every 10 s. Alerts show when the CPU stayed above 90 °C or RAM above 95%
for 5 minutes. Refreshes every 3 s while open; nothing polls when it is closed.

**Bridge stats.** A *Bridge* card on the PC page: version and uptime, devices connected, requests
and failures, how fast Hermes answers (average and p95), live-link traffic, approvals asked and
how long they waited, and turns started/finished. Tap to unfold. The counters start at zero when
the bridge restarts. Needs a bridge with metrics (1.26+); the Linux tray has the same figures under
*Bridge stats...*.

**Processes.** Every process with CPU% and memory, sortable and filterable by name, command or
PID. Tap one to Stop (ask it to quit) or Force stop (kill).

**Claude usage.** Session (5 h) and weekly limits of the Claude plan Claude Code is signed into on
the PC, with when each resets and the history. Checked every 5 minutes; the refresh icon asks now.

**Terminal.** The `>_` icon in the drawer header (or the PC page). Your login shell on the PC, with
colours and a key bar for Esc, Tab, Ctrl, ^C, arrows, Home/End and font size. Type in the field
and press Send; with *Ctrl* latched, the next letter goes as Ctrl+letter.

**Widgets.** Long-press the home screen > Widgets > Hermes Remote: *PC stats* (CPU, RAM,
temperature and the last hour) and *Claude usage*. They show the PC the app is using. Tap the
time to refresh; tap anywhere else to open the PC page. Android refreshes them every 15 minutes.

- Limits: temperature is Linux only. On Windows the terminal has no PTY: line commands work,
  full-screen programs (vim, top) don't.

## Memory Library

**Hermes' own long-term memory.** *Memory Library* in the drawer's ⋮ menu (or PC bridge > Memory
Library). Setup installs a private database on the PC after showing what it downloads (about 34 MB),
the disk it needs and where it goes; then switch it on per Hermes profile. An existing memory
provider is kept unless you choose to switch (it comes back when you switch off), and MEMORY.md /
USER.md are copied in for review, never moved.

**What Hermes remembers.** Every memory has a kind (mistake, rule, preference, workflow, fact,
decision), a severity from Note to Fatal and a scope (everywhere, one project, or a client). Critical
and fatal ones are always given to Hermes in their scope; the rest only when the message matches.
Hermes saves lessons itself as you correct it, counts repeated mistakes (severity climbs) and, after a
chat ends, the memory model reviews it for lessons it missed (marked *Needs review*).

**Library page.** Filter by kind, severity, scope or review, sort by severity, repeats, recalls or
never recalled; edit, mark reviewed, archive, merge two memories or delete. *Test recall* shows exactly
what Hermes would get for a message and the score behind each match. *Setup* has the on/off switch,
the doctor with one-tap fixes and the memory model (same as the chat by default, or any model Hermes
already has).

**Knowledge map.** With the Memory Library the Graph page maps what Hermes has learned: memories
coloured by kind and sized by severity around the projects and clients they apply to. The chats they
came from are an optional layer.

## Scheduled jobs

**Scheduled jobs.** *Scheduled* in the drawer lists the cron jobs of the Hermes profile in use:
name, schedule, next and last run, and the last error if there is one. Tap a job for its recent
runs and the start of their output. *Run now*, *Pause* and *Resume* act on it straight away.

- Limit: creating, editing and deleting jobs is done on the PC. The phone sees what a job is called
  and how it is doing, not the prompt or script it runs.

## Approvals

**Approval dock.** When a turn needs permission for a command, a panel slides up above the
composer showing the exact command and four choices: allow once, allow for the session, always
allow, or deny. Answer on whichever screen is closer: the phone and the desktop see the same
question, and answering on one withdraws it from the other.

**Approve from the notification.** With the app in the background, the approval notification has
*Deny*, *Allow* and *Allow for session* buttons; it works on the lock screen too. Allowing asks for
the phone to be unlocked first (Android 12+), so a command never runs because someone else picked
the phone up. *Always allow* is only offered in the app.

**Questions from the agent.** When the agent asks you something (the clarify tool), the choices or
a text field appear in the same dock.

- Limit: password, sudo and secret prompts are answered on the PC. The phone says one is waiting.

**Approval mode.** Settings (System tab) switches the `approvals.mode` of the Hermes profile in use:
Manual, Smart or Off. This is Hermes' own setting and applies everywhere that profile runs —
desktop, messaging platforms and this app — not just to the phone.

## Slash commands and skills

Type `/` in the composer for Hermes' commands and your installed skills, suggested by Hermes'
own completer. They run against the live agent, the way the desktop runs them, so all of them
work: `/retry`, `/undo`, `/btw`, `/background`, `/title`, `/compress`, `/branch`, `/status`,
`/save`, `/plan`, skills, and the rest. `/new`, `/model`, `/reasoning` and `/stop` map onto the
app's own controls.

Not offered: commands that only make sense in a terminal or open a desktop window (`/voice`,
`/skin`, `/pet`, `/config`, …), the same set the desktop app hides.

## Connection

**Local network first.** On a Wi-Fi you marked trusted, the phone connects straight to the PC over
HTTPS with a pinned certificate. Tailscale is optional and only used when you are away.

| Where you are | Connection | Tailscale needed? |
|---|---|---|
| A Wi-Fi you trusted (home, office) | Direct, HTTPS, pinned certificate | No |
| Any other Wi-Fi (cafe, hotel) | Tailscale only; the bridge does not listen there | Yes |
| Mobile data | Tailscale | Yes |

**Automatic switching.** The app re-picks the best path when the phone's network changes, every
20 s while it is open, and when you return to it. The System tab shows the current path and
offers manual switches.

**Networks you trust.** When the PC joins a network it has not seen, a desktop notification asks
whether to trust it. Networks are recognised by the NetworkManager connection profile *and* the
router's MAC address, so a hotspot that copies your Wi-Fi name is not trusted.

**Finding the PC again.** If its IP changes, the app finds it over mDNS without re-pairing. The PC
side notices network changes within about 5 s.

## Gateway hosts

A host is either a bridge (paired with the QR code) or a gateway. To add a gateway, open Add host,
choose **Sign in to a gateway**, and type its address. The gateway's login page opens in a browser
tab; sign in there and the app returns with a host named after the address. The password is typed
on that page, never in the app.

- Works with a gateway behind its own login (`hermes serve` with auth on). `https` works for any
  address; plain `http` is accepted only for Tailscale or LAN addresses.
- Tokens are kept in the Android Keystore and renewed automatically. If the gateway no longer accepts
  the session, the app shows **Sign in again** and keeps the host. Removing a host deletes its tokens
  and drafts. Whether the gateway also revokes the session on sign-out is not confirmed.
- Hidden on gateway hosts, because they need the bridge: the project
  description (IDEA.md), chat file download, the bridge status card, and the default-model and
  approval settings.
- Chat, streaming, approvals, sessions and pins use the same socket calls as a bridge host. Chat
  pictures load from the gateway. Each reconnect gets a fresh single-use ticket.
- Limits: no notifications while the app is closed (a follow-up). A gateway host has no bridge in
  front of it, so there is no device pin, rate limit or method allowlist; the gateway's login and
  your network are the only protection. See [SECURITY.md](SECURITY.md#gateway-hosts).
- Not yet tried on a real phone against a signed-in gateway. The checklist is in
  [gateway-manual-checks.md](gateway-manual-checks.md).

## Running alongside the PC

**One session, many surfaces.** Sessions, pins and titles are Hermes' own, so a chat started on
the phone appears on the desktop and vice versa. If the desktop app is closed, the bridge starts
Hermes itself; open the desktop later and it joins the same backend.

**Work keeps running when the phone leaves.** On a bridge host, when the app closes or loses its
connection, the bridge keeps the chat attached to Hermes for as long as it still has work (a
turn, a subagent, a loop or heartbeat, a background process) and for `park_seconds` (10 min)
after that, up to `park_max_seconds` (24 h). Reopen the app and the chat catches up. The PC page
lists these *Held chats*, and Restart or Update asks first when one is still working.

**Tablet layout.** On a screen 720 dp wide or more, the session list sits next to the chat instead
of behind a drawer.

## Updates and notifications

**In-app updates.** At startup the app checks GitHub Releases and offers to install a new version
in place (System tab → App version). The APK's SHA-256 is checked against the one GitHub reports,
and Android only installs it if it is signed with the same key as the app you already have.

**Notifications.** While the app is in the background it keeps its connection to the PC and
notifies you when a chat you opened on the phone finishes, or needs an approval or an answer.
Answering on the PC clears the notification.

- Some vendors (Xiaomi, Huawei and others) kill background apps aggressively. If notifications
  stop arriving, allow the app to run in the background.

## Known limits

- A bridge host needs the PC on, with the bridge running. A gateway host needs its gateway running
  and reachable. There is no cloud relay.
- The bridge runs on Linux (supported) and Windows (experimental). macOS is not implemented. See
  [PLATFORMS.md](PLATFORMS.md).
- Password, sudo and secret prompts are answered on the PC.
- One turn at a time per session. While a turn runs, what you type steers it.
