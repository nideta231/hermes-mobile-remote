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
running. Opening a chat while subagents run shows them straight away.

**Steering and stopping.** While a turn is in flight the composer becomes "Steer the running
task…", and the send button turns into a stop button. You can add instructions mid-turn or cancel.

**Drafts.** An unsent message survives switching tabs, rotating the screen and restarting the app.

**Sessions.** Swipe in the drawer from the left edge to search, pin, rename and delete chats.
Long-press a session for those actions; deleting asks for confirmation. A session that is working
right now — on any screen — shimmers in the list, like the desktop sidebar.

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

## Running alongside the PC

**One session, many surfaces.** Sessions, pins and titles are Hermes' own, so a chat started on
the phone appears on the desktop and vice versa. If the desktop app is closed, the bridge starts
Hermes itself; open the desktop later and it joins the same backend.

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

- The PC has to be on, with the bridge running. There is no cloud relay.
- The bridge runs on Linux (supported) and Windows (experimental). macOS is not implemented. See
  [PLATFORMS.md](PLATFORMS.md).
- Password, sudo and secret prompts are answered on the PC.
- One turn at a time per session. While a turn runs, what you type steers it.
