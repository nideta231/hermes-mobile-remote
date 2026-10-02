# Features

Every feature the app has, how it behaves, and where it stops. Grouped by what the user is
trying to do. For how any of this is implemented, see [ARCHITECTURE.md](ARCHITECTURE.md); for the
security rules behind the connection features, see [SECURITY.md](SECURITY.md).

## Chat

**Streaming replies.** A reply arrives token by token over SSE, with a live cursor. Tool calls
render as cards with their arguments, result and duration; a card that fails is marked red.

**Steering and stopping.** While a run is in flight the composer becomes "Steer the running
task…", and the send button turns into a stop button. You can add instructions mid-run or cancel.

**Following a run started elsewhere.** Open a session that is already running on the desktop, the
CLI or a messaging platform and the app tails it, so you can watch a run you did not start. The
header says "Running on another device" while that happens.

- Limit: only runs the app itself started stream token by token. A run started elsewhere appears
  as Hermes persists each step, so it can lag by a few seconds.

**Drafts.** An unsent message survives switching tabs, rotating the screen and restarting the app.

**Sessions.** Swipe in the drawer from the left edge (or the Sessions tab) to search, pin, rename
and delete chats. Long-press a session for those actions. Deleting asks for confirmation and the
bridge requires the session id again, so a mistap cannot delete a conversation.

**Pinned sessions.** Pins are shared with the Hermes desktop app; pin on either surface.

## Models and reasoning

**Per-message model picker.** Lists every model your configured providers can actually serve.
Providers that are not authenticated, and models Hermes marks unavailable, are left out, so the
picker never offers something that would fail.

**Per-message reasoning effort.** Off, Low, Medium, High, Extra high or Max, or Hermes' configured
default. Sent per run; the app does not change your global setting.

## Approvals

**Approval dock.** When a run needs permission for a command, a panel slides up above the
composer showing the exact command and four choices: allow once, allow for the session, always
allow, or deny. The run is blocked until you answer.

**Approval mode.** Settings (System tab) switches Hermes' global `approvals.mode`: Manual, Smart
or Off. This is Hermes' own setting and applies everywhere — desktop, messaging platforms and
this app — not just to the phone.

## Slash commands and skills

Type `/` in the composer for Hermes' commands and your installed skills, with filtering.

- Commands that act on stored state run on the PC and print their output in the chat
  (`/status`, `/tools`, `/memory`, `/title`, `/compress`, `/approvals`, …).
- Skills and plan-style commands expand into a prompt the app sends as a normal run.
- `/new`, `/model`, `/reasoning` and `/stop` map onto the app's own controls.

Not offered: commands that need the agent loaded in memory on the PC (`/retry`, `/undo`, `/btw`,
`/goal`, `/usage`). The bridge would run them against an empty agent and report something
misleading, so they are left out rather than shown broken.

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
the phone appears on the desktop and vice versa.

**Remote desktop (optional).** The Desktop tab opens your PC's desktop in
[aFreeRDP](https://f-droid.org/packages/com.freerdp.afreerdp/) with the connection pre-filled. It
is built for KDE's KRdp, which Microsoft's own Android RDP client cannot use.

**Tablet layout.** On a screen 720 dp wide or more, the session list sits next to the chat instead
of behind a drawer.

## Updates and notifications

**In-app updates.** At startup the app checks GitHub Releases and offers to install a new version
in place (System tab → App version). The APK's SHA-256 is checked against the one GitHub reports,
and Android only installs it if it is signed with the same key as the app you already have.

**Notifications.** You are notified when a run you started finishes or needs approval while the
app is in the background.

- Limit: runs started on the PC do not raise notifications.
- Some vendors (Xiaomi, Huawei and others) kill background apps aggressively. If notifications
  stop arriving, allow the app to run in the background.

## Known limits

- Live streaming only for runs started in the app.
- Notifications only for runs started in the app.
- The bridge runs on Linux (supported) and Windows (experimental). macOS is not implemented. See
  [PLATFORMS.md](PLATFORMS.md).
- Slash commands that need a live agent on the PC are not offered.
- One active run per session, by design: the bridge refuses a second.
