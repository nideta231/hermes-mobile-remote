# Changelog

Releases are git tags; the version in the app comes from the tag. `git log v0.9.0..vX.Y.Z` is the
authoritative history — this file only summarises what is worth knowing before upgrading.

## Contributors

Thank you to everyone who sends pull requests. Credits are on each changelog line, with the pull
request number.

- **@LastNyx** (Benediktus Aprianto): the Windows tray app (#2), tray *Start at logon* and *Stop
  trusting* fixes (#4), sign in to a gated gateway from the phone (#14), the Hermes Remote
  desktop app for Windows and Linux with a PowerShell-free Windows bridge (#19), and keep working in
  the background with fast reattach, bridge socket parking and linked gateway + bridge hosts (#20).
- **@Justcallmesu** (Suwanto Ardi Winata): Linux tray install (#5), Hermes profiles (#6, #9, #13),
  the Linux tray devices fix (#7), roadmap (#8), scheduled jobs (#10), app refactors (#11),
  subagents (#12), share to Hermes (#15), message search (#16), subagent output and steer (#17)
  and bridge metrics (#18).

## v1.41.0

- Blueprint boards: **Mark reviewed** / **Mark answered** on a Hermes question or review now tells
  the chat that asked (opened and steered while it runs, a new turn when idle), so Hermes carries
  on without a separate "Tell Hermes I replied".
- Answering a card's question in the chat instead of on the card: Hermes now resolves it on the
  card itself, and moving a card to the last column closes any question or review still open on it.
  Needs the updated bridge.

## v1.40.0

- **Board attachments up to 10 MB, with a clear message**: attaching a file or picture to a board
  card or comment failed for anything over about 1 MB (the bridge refused it before the upload
  could start, with a vague "request body too large"). The limit is now 10 MB per file, shown next
  to *Attach*; a bigger file is refused before it is sent, with its size and the limit
  ("clip.mp4 is 24.5 MB; board attachments can be up to 10 MB"). Needs the updated bridge.
- **Previews of board attachments**: pictures on a card or in a comment show as thumbnails (tap for
  full screen, Info and Download), text files (Markdown, CSV, logs, code) show their first lines
  under the file card.
- **One bridge version**: the PC page and status showed the bridge as 1.0 while the PC bridge page
  showed the release (e.g. 1.39.0). The bridge now reports only its release version everywhere.
  Needs the updated bridge.

## v1.39.0

- **Approve from the lock screen**: an approval notification now has *Deny*, *Allow* and *Allow for
  session* buttons, so you can answer without opening the app. Allowing asks you to unlock the phone
  first (Android 12 and newer), denying works straight from the lock screen. The notification then
  says what you chose and clears itself; if the phone has lost its link to the PC it says the answer
  was not sent and opens the chat when tapped. With *hide sensitive content* on, the lock screen
  shows only that an approval is waiting, not the command. *Always allow* stays in the app.

## v1.38.0

- **Attach files and pictures to blueprint cards**: a card has an *Attachments* section with an
  *Attach* button (any file or picture from the phone), and the comment box has a paperclip, so a
  comment can carry files too. They are stored on the PC (open, save and info work like chat files),
  the card shows 📎 with the count, and Hermes sees them in the blueprint. Needs the updated bridge.
- **Card description is a rich text editor**: it shows formatted (headings, bold, lists, checklists,
  links, code) and *Edit* opens a toolbar that formats the selected text: bold, italic, heading,
  bulleted/numbered list, checklist, quote, code and link. Stored as Markdown, so Hermes reads the
  same thing.

## v1.37.0

- **Global agent instructions**: Settings > Blueprint boards now has *Agent instructions (global)*,
  rules Hermes follows on every board that has none of its own. A board's *Agent instructions* shows
  what the global ones say, whether the board follows them, *Reset to global* (clears the board's own)
  and *Edit global*. Hermes CLI: `blueprint instructions global "<text>"`. Needs the updated bridge.
- **Slimmer search box in the chat list**: the search field is now a 40 dp pill, the same height as
  the filter and new chat buttons beside it (was a tall 56 dp field, also in compact mode), and it
  shrinks with a narrow chat list: the hint is cut short instead of pushing the buttons out.
- **Blueprint inbox**: a bell in the blueprint header counts the cards that want you (new Hermes
  comments or an open question/review). Tap it for the list, newest first, with the column, who wrote
  what and when; tap a row to open that card. *Mark all read* clears every new-comment badge at once
  (questions stay until you answer them).

## v1.36.0

- **GitHub release page: download link first**: each release now starts with a direct link to the APK
  (and its SHA-256); older versions are folded under *Earlier versions* instead of filling the page.
- **"Tell Hermes I replied" goes back to the same chat**: answering on a card now sends the note to
  the Hermes chat that was working on it (a steer while it runs, a new turn when idle) instead of
  starting a new chat. Cards from an older bridge, with no chat recorded, still start a new one.
- **New Hermes comments are marked unread**: a card shows "1 new comment" until you open it, the
  blueprint header counts them, the drawer's blueprint button gets its dot, and a notification says
  what Hermes wrote (tap opens the blueprint). Needs the updated bridge.
- **Longer card titles, never cut off**: titles can be 600 characters (was 300), node labels 400,
  column names 120. Near the limit a counter shows; over it the field turns red and Save waits until
  you shorten it, instead of the text being silently cut. Needs the updated bridge.
- **Global board settings**: Settings > *Blueprint boards* sets new card position and card aging for
  every board on the PC. A board's own Board settings now offer *Global (…)* for each choice (the
  default) or a value of its own, with a link to the global ones. Hermes follows the same settings.
  Needs the updated bridge.
- **Drag columns**: long-press a column's header and drag it left or right; a gold bar shows where it
  lands and the board scrolls near the edges. The column menu's Move left/right still works.
- **Sticky Add card** (Board settings > Add card button): pins "+ Add card" at the bottom of each column
  instead of after the last card. Per board or global, like the other board settings.
- **More instruction templates**: "work on Working first, ask which Todo card", "attach the result",
  "check open pull requests first", "comment how it was tested", "commit per card, release once" and more.
- **Choose what the PC monitor tracks**: PC page > *What to track* switches CPU, memory, temperature,
  disk, network, top processes and Claude usage on or off. Off means the PC stops reading it (less CPU
  and fewer disk writes); its gauge, row or chart disappears and its history has a gap. Needs the
  updated bridge.
- **Resizable chat list on wide screens**: drag the edge between the chat list and the chat to make the
  list 240 to 480 dp wide (double-tap the edge to reset).
- **Hide the chat list on wide screens**: the menu button at the top left of the chat hides it so the chat
  takes the full width, and brings it back. Also in Settings > Appearance.
- **Compact layout** (Settings > Appearance): everything about 12% smaller, so more fits on screen.
- **Pick your own primary and background colours** (Settings > Appearance): hue, saturation and
  brightness sliders or a hex code. For the background there are six suggestions built from your
  primary colour (neutral, accent tint, deep accent, complement, warm, cool). Text colours adjust so
  everything stays readable. *Reset* goes back to the accent and mode.

## v1.35.0

- Bridge stats on the phone: the PC page has a *Bridge* card with what `hermes-remote-bridge
  metrics` prints (devices connected, requests and failures, Hermes reply time, approvals waiting
  and how long they took, turns). It folds to one line; tap to unfold. Uses the bridge's metrics
  (1.26+) with the phone's own token, so nothing to set up. **@Justcallmesu** (#21)
- Linux tray: an *Activity* line (devices connected, turns, approvals waiting) and *Bridge
  stats...*, which shows the full summary. **@Justcallmesu** (#21)
- **Background work sits in a fixed strip above the composer**: running processes, subagents no delegate
  card shows, and an active /loop or /heartbeat collapse to one line ("2 running · 1 subagent · loop");
  tap to open the Stop and steer actions. It hides when nothing runs or a question is open. **@LastNyx** (#22)
- **A bridge installed by the desktop app can update itself from the phone (Linux)**: it checks the newest
  `vX.Y.Z` tag on GitHub, swaps in that release's `bridge/` keeping the old copy as `bridge.old`, and
  restores it, re-syncs and restarts it if any step fails after the swap. `self_update = false` still turns it off. **@LastNyx** (#22)
- **Bridge keeps a parked socket while its chats have work**: a dropped phone's Hermes socket now stays
  open while a turn, subagent, /loop, /heartbeat or background process is running, so a
  `terminal(background=true, notify=true)` watcher is no longer killed. It is released `park_seconds`
  (default now 600) after the last work ends, or after `park_max_seconds` (default 86400) regardless. **@LastNyx** (#22)
- **Bridge lists its parked sockets**: `GET /v1/admin/parked` (feature `parked` in `/v1/me`) shows, per
  device, how long a socket has been held, its session count and why (work kinds, or when it releases). **@LastNyx** (#22)
- **The PC page shows held chats and warns before a restart or update ends them**: a read-only "Held chats"
  section lists each parked socket (device, how long, why); Restart and Update ask "N chats on the PC are
  still working; restarting ends them" first (the Settings and drawer update ask too, with "updating").
  Bridges without the route behave as before. **@LastNyx** (#22)
- **Board drag scrolls and shows where the card lands**: hold a dragged card near the top or bottom of
  a column and the column scrolls (faster near the edge); near the left or right edge the board scrolls
  sideways. A gold slot opens where the card will drop, also with a tag filter on.
- **Board settings** (blueprint menu > Board settings): new cards *On top* or *At the bottom*, and
  *Card aging*, which fades cards nobody touched for 3, 7, 14 or 30 days (cards waiting for you or being
  worked on never fade). Each column can have a **card limit** (column menu > Card limit); its count
  shows `3/2` in red when over. Hermes sees the settings and limits too. Needs the updated bridge.

## v1.34.0

- **File and picture info**: an *i* button on every file card and in the full-screen picture viewer
  shows the name, the full path on the PC (tap to copy), the size, the type and when it was last
  changed, without downloading it. Pictures still being sent show their size on the phone.
  Needs the updated bridge for size, type and date; older bridges show the name and path.
- **Blueprint: who is working on what**: a card an agent is on shows "Hermes is working on this"
  with a glowing border; the header counts cards in progress. The badge clears when the card is done.
- **Blueprint: comments**: every card has a comment thread for you and Hermes. When Hermes asks a
  question or wants a review, the card gets an amber "Question for you" / "Review needed" mark, the
  board has a *Waiting for you* filter and the project's blueprint button in the drawer gets a dot.
  Answer in the thread (that resolves it), or *Mark answered*. Hermes can attach result files to a
  comment; open or save them from the card, with the *i* button for size and path.
- **Blueprint: agent instructions**: standing rules per project (for example "never touch LIVE
  unless I ask", "do every Todo card and report on Telegram"). Set them from the blueprint menu;
  *Let Hermes take control* asks for them first. Hermes reads them before every task on that project
  and they are added to every ask you start from the blueprint.
  Needs the updated bridge.

## v1.33.0

- **Blueprints**: a flow diagram and a task board for each project, on the PC, shared with Hermes.
  - Long-press a project in the drawer > *Add blueprint*. Projects with one get a small blueprint
    button on their header. *Remove blueprint* moves it to the trash (long-press > *Blueprint trash*)
    where it can be restored or deleted for good.
  - **Diagram** (like draw.io): Start, End, Feature, Flow, Decision and Note shapes with arrows on a
    dotted canvas. Pinch to zoom, drag to pan, long-press a box to move it, tap to select (Edit,
    Connect, Add next, Delete), tap an arrow to delete it, *Tidy diagram* to lay it out.
  - **Board** (like Trello): your own columns (Docs, Todo, Working, Done to start; add, rename,
    colour, reorder, delete). Long-press a card and drag it to another column or position. Cards
    have a description, checklist, tags (filter at the top), priority, due date, who's on it (you or
    Hermes) and a link to a diagram box, which then shows the card's column.
  - **Activity**: every change with who made it, you or Hermes.
  - **Hermes uses it**: the new `project-blueprint` skill makes Hermes read a project's blueprint
    before working on it and keep it current (moves cards, ticks checklists, adds found work, logs
    decisions). *Let Hermes take control* hands it the board and diagram; you mostly watch and
    adjust. *Ask Hermes to work on this* / *Ask Hermes to do this* (on a card) start a drafted chat.
  - Phone and Hermes edits merge (each change applies to the newest copy), and the open blueprint
    refreshes every few seconds.
  - Needs the updated bridge (PC bridge > Update). The update also installs the Hermes skill.

## v1.32.1

- Notes: what you write is kept when you leave the editor. A background refresh could close a fresh
  note or put back an older copy before the save landed; the note you're editing now stays on screen
  and leaving saves it at once. Only a note left completely empty is dropped.
- Notes: the Archive button says *Archive* / *Unarchive* with its own icon (it was a download arrow),
  and a message says where the note went.
- Notes: tags. They show under the title of every note with an *Add tag* chip, as `#tag` on the
  cards, and filter the list from the chips at the top (same as labels on the PC side).
- Notes: tapping anywhere in the empty part of a note starts typing, not only on the text itself.

## v1.32.0

- New **PC bridge** page (drawer ⋮ > PC bridge, or Settings > Hermes on your PC > PC bridge): everything
  that needed a terminal on the PC, from the phone.
  - **Health**: the bridge doctor's checks, each problem with a one-tap fix (Trust network, Restart
    bridge, Start Hermes).
  - **Restart bridge**: runs as its own job on the PC, checks the bridge answers afterwards, and the
    app reconnects the moment it's back. Hermes and running chats keep going.
  - **Update**: the bridge self-update, same as before.
  - **Networks**: trust the Wi-Fi the PC is on (direct connection without Tailscale), or remove one.
  - **Paired devices**: see who can connect and revoke old ones (this phone can't revoke itself).
  - **Log**: the bridge's latest log lines.
  - Turn it off with `remote_admin = false` in the bridge config. Needs this bridge version.

## v1.31.1

- Fixed: the bridge said "up to date" while still running old code. It compared the files on disk
  (already new after a `git pull`) instead of the code it had loaded, so features of the new version,
  like Notes, stayed hidden until someone restarted it. It now reports the version it is actually
  running, and when newer code is installed but not loaded, the drawer and Settings offer
  *Restart PC bridge to load vX.Y.Z* (health-checked, with the same reconnect as an update). This
  works even when the checkout has local changes. Needs this bridge version, restarted once.

## v1.31.0

- Update the PC bridge from the drawer: when a newer bridge is out, the three-dot menu gets a dot and
  *Update PC bridge to vX.Y.Z* (Settings > Hermes on your PC has the same button). The app checks on
  every connect.
- The connection comes back in every case. The PC now checks the new bridge answers after the
  restart; if it doesn't start, it rolls back to the previous version and restarts that. If the
  bridge was stopped, it is started again; an update that dies halfway is reported as failed instead
  of spinning. On the phone, the live connection reconnects the moment the bridge is back (no
  waiting on retry timers) and the chat list, settings and scheduled jobs reload. Needs the updated
  bridge (this update itself can be installed from the phone).

## v1.30.0

- Notes, like Google Keep, for braindumps and planning (drawer > three-dot menu > Notes). A grid of
  coloured cards with pinned notes on top, search, labels and an archive. Tap a card to edit; the
  three-dot menu in a note turns it into a checklist and back, and items tick right on the card.
  The *Note* button starts a text note and the check button a list. Empty notes are dropped when
  you leave them.
- Hermes can use your notes too: they are Markdown files on the PC (`~/.config/hermes-remote/notes/`),
  and the new `personal-notes` skill teaches it to list, read, add, append to and tick them
  (`hermes-remote-bridge notes ...`). Say "save this in my notes" or "what's on my plan list", or
  use *Ask Hermes about this* in a note. Changes Hermes makes show up in the app within seconds.
- Needs the updated bridge: Settings > Bridge > Update (or `hermes-remote-bridge update`). Run
  `./install.sh` once more to install the skill, or copy `bridge/skills/personal-notes` into
  `~/.hermes/skills/productivity/`. Turn notes off with `notes = false` in config.toml.

## v1.29.0

- Bridge updates from the phone: Settings > Hermes on your PC shows the bridge's version, and when a
  newer one is out, *Update* installs it on the PC and restarts the bridge (about a minute). Paired
  phones keep their pairing and reconnect on their own; Hermes and running chats keep going. On the PC
  the same is `hermes-remote-bridge update` (`--check` only looks). It works for bridges installed from
  the git checkout with `install.sh`; desktop-app installs update with the app. Needs this bridge
  version once (update it with `git pull && ./install.sh` the first time); after that it updates itself.
  Turn it off with `self_update = false` in config.toml.
- Forgetting the PC in use no longer leaves the app on the pairing screen when other PCs are saved:
  it moves to the next one. If the app does end up on the pairing screen, it lists your saved PCs so
  you can switch back without pairing.

## v1.28.1

- Drawer top bar is tidier: only Scheduled and Settings stay as buttons. PC monitor, Terminal and
  Graph moved into the three-dot menu next to them.

## v1.28.0

- Graph: a new map of your chats, like Obsidian's graph view (the dots icon in the drawer). Every
  chat is a dot, linked to its project (gold), to topics its title shares with other chats (purple)
  and, if you turn it on, to the model it used (green). The dots pop in one by one and fly apart
  into clusters as the map settles. Pinch to zoom, drag to move around, drag a dot to pull it, tap a
  dot to light up its links, then *Open* a chat. *Fit* brings the whole map back on screen.
- Links in chat can be tapped: a small box offers *Open* (in the browser) or *Copy*. Works in replies,
  tables and your own messages, for plain `https://` addresses and markdown links.
- @mentions (like **@LastNyx** in this changelog) can be tapped too: *Open* goes to their GitHub profile.
- Gateway hosts (signed in without a bridge) now have the PC page, with what Hermes itself reports:
  CPU, RAM, disk, load and uptime. History, temperature, network, processes, Claude usage and the
  Terminal still need the Hermes Remote bridge on that PC: pair it and link it with the gateway entry.

## v1.27.0

Thanks to **@LastNyx** for this release.

- Keep connected in the background (Settings, on by default): after you swipe the app away, the
  phone stays connected while Hermes works on the PC: a running turn, a subagent, a background
  process, or an active `/loop` or `/heartbeat`. It keeps going for 5 minutes after the last of
  them ends (1 or 15 minutes can be chosen). Turn it off to stop the connection as soon as the
  reply is done. By **@LastNyx** (#20)
- A dropped connection (Wi-Fi change, airplane mode, a sleeping screen) reconnects at once and
  resumes every running or waiting chat, not only the open one. By **@LastNyx** (#20)
- Bridge: when the phone drops, the bridge keeps its connection to Hermes open for the device for
  `park_seconds` (default 300; 0 turns this off), so a quick return picks the same session back
  up. Needs the updated bridge. By **@LastNyx** (#20)
- Bridge: the phone may call the read-only `session.control.read`, which shows `/loop` and
  `/heartbeat` state. Needs the updated bridge. By **@LastNyx** (#20)
- A PC that has both a bridge and a gateway sign-in can be linked into one entry tagged
  "Gateway + Bridge". The app uses the bridge and falls back to the gateway when the bridge cannot
  be reached, then returns to the bridge when it answers again. The connection row says "via
  Bridge" or "via Gateway". After a gateway sign-in the app offers to pair the PC's bridge too;
  the host menu has *Link with...* and *Unlink*. By **@LastNyx** (#20)
- Chats resumed from the phone are now labelled `desktop` in Hermes' live session, like new ones
  already were (they were labelled `tui` before). The stored chat row is not changed. By **@LastNyx** (#20)

## v1.26.0

Thanks to **@LastNyx** and **@Justcallmesu**: most of this release is theirs.

### Terminal
- The Terminal now works like Termux on the PC's shell: the screen is a character grid in Konsole's
  Breeze colours, and typing goes straight to the shell (no text box), so Tab completion, arrow keys,
  Ctrl+C, vim, htop and less behave like on the PC. A hardware keyboard works too, F1 to F12 included.
- Long press selects a word; drag to extend it, or drag either handle. *Copy*, *Paste* and *Select all*
  sit above the selection. *Paste* in the top bar pastes the clipboard; multi-line text is pasted as one
  block where the shell supports it, instead of running each line.
- Swipe to scroll back through the output (with fling), tap *↓ N lines* to jump back to the bottom.
  Pinch to zoom, or A− / A+.
- Termux's extra keys under the screen: ESC / - HOME ↑ END PGUP and TAB CTRL ALT ← ↓ → plus a
  keyboard button. CTRL and ALT latch for the next key.
- Bridge: screen frames say when the shell turned on bracketed paste. Older bridges still work
  (pastes are sent plain).

### Subagents
- Subagents look like the desktop app: each `delegate_task` call becomes a card in the chat with one
  row per subagent showing its goal, model and running time, a spinner, check or red mark, and one line
  of what it is doing now (`Read File("README.md")`, its thinking, then its summary). The line ticks
  over as it works.
- Tap a row for its last few steps, *Output* (live transcript), *Steer*, *Stop*, *Open chat* (the
  subagent's own session) and its token use. A background delegation that nothing is watching shows a
  grey dot instead of spinning forever. A timed-out subagent shows as failed, with the reason.
- Subagents with no card on screen (a child of a child, or one whose call is outside the loaded
  history) are listed under the transcript as before.

### Bridge
- A bridge restart or update no longer kills Hermes. The bridge is only a tunnel now: the Hermes it
  starts runs in its own systemd scope, outside the bridge service, so running chats, subagents and
  background jobs keep going while the bridge restarts. Needs the updated bridge.

### From contributors
- Share to Hermes: pick *Hermes Remote* in another app's share menu (Chrome, WhatsApp, Files,
  Gallery) to bring text, links, pictures or files into a chat. A sheet asks for *New chat* or the
  chat on screen; the text lands in the composer and the rest is attached, nothing is sent until you
  tap Send. Usual limits apply (6 pictures, 6 files of 10 MB); anything over them is left out with a
  note. By **@Justcallmesu** (#15)
- Drawer search now finds chats by what was said in them, not only by title. After a short pause
  (two or more characters) the app asks Hermes' own message search on the PC; chats it finds that
  the list doesn't already show appear under *In messages* with the matching line. Needs the
  updated bridge (`GET /v1/sessions/search`); an older bridge keeps the title filter. By **@Justcallmesu** (#16)
- Subagents: a running subagent has *Output*, its live transcript (refreshed every couple of
  seconds while open), and *Steer*, to send it an instruction it picks up after its current step.
  Needs the updated bridge. By **@Justcallmesu** (#17)
- Bridge metrics: `GET /metrics` serves Prometheus text with REST request counts and durations,
  live-socket connections and frames (forwarded and refused, by reason), Hermes RPC round-trip time
  by method, how long approvals and clarify questions wait for an answer, turns by outcome, and how
  many devices are connected. It needs a token even on loopback: the new
  `~/.config/hermes-remote/metrics.token` (opens the metrics only, for a scraper) or a device token.
  Labels are route templates and allowlisted method names; no ids, titles, prompts, paths or device
  names. `hermes-remote-bridge metrics [--json]` prints a summary. Off with `metrics = false`. By **@Justcallmesu** (#18)

### Desktop app (Windows and Linux), by **@LastNyx** (#19)
- New desktop app, **Hermes Remote**, for Windows and Linux (`desktop/`). It sets up the bridge on first run, shows status, devices, trusted networks and the doctor report, pairs phones with an inline QR code, and has a Settings page.
- Windows: the app starts and stops the bridge itself, so quitting the app stops the bridge. If the app crashes, nothing restarts it; start Hermes Remote from the Start menu. Start at logon is a "Hermes Remote" entry under Run (visible in Task Manager > Startup).
- Windows: the tray icon opens a flyout at the taskbar corner (left click); right click gives Open, Start/Stop bridge, Pair a device and Quit. Installer is per user, needs no admin, and is unsigned, so SmartScreen shows "More info > Run anyway".
- Linux: AppImage and .deb builds. The app opens a normal window from the launcher and adds a tray icon only where the desktop has a StatusNotifier host (KDE; GNOME needs the AppIndicator extension).
- Linux: a run mode setting. *Always on* (the default) runs the bridge as the systemd user service `hermes-remote-bridge.service`, so it keeps running after the app closes. *Only while the app is open* stops the bridge with the app and has no systemd sandboxing. *Start at login* and *Keep running after logout* (`loginctl enable-linger`) are in the same page.
- First run shows what it will change, saves that list to `INSTALLED.txt` in the config directory, and installs the bridge into the data directory (`~/.local/share/hermes-remote` on Linux, `%LOCALAPPDATA%\hermes-remote` on Windows). The config directory is unchanged and Hermes' own config and `.env` are never touched.
- Upgrading from a Windows script install: setup stops the old PowerShell tray and bridge, deletes the "Hermes Mobile Remote" Scheduled Task and turns autostart on. Paired devices and trusted networks stay. On Linux the app takes over the existing service unit of the same name.
- Removed: `install.ps1`, `install.cmd`, `tray.cmd`, `pair.cmd` and the PowerShell scripts under `bridge/windows/`. The bridge no longer starts PowerShell, and Windows network and firewall checks call the Windows APIs directly. `install.sh` stays for Linux source installs.
- Windows: `hermes-remote-bridge firewall` asks for elevation once (one UAC prompt); cancelling it changes nothing. The rule is still "Hermes Mobile Remote", for the Private network profile only.
- Windows: running `hermes-remote-bridge` with no command now says to pair from the Hermes Remote app.
- Windows: the phone's Terminal opens `pwsh`, then `powershell`, then `cmd`. Set `terminal_shell` in config.toml to use another shell.
- Windows uninstall (Settings > Apps) stops the bridge, removes autostart, the old task and the firewall rule (one UAC prompt). Config and devices are kept unless "Delete the application data" is ticked.
- Updates: AppImage and Windows installer builds update from inside the app ("Restart to update" banner). `.deb` and dev builds show "New version X available" with a link to the release page. Desktop releases are tagged `desktop-v<version>`; the Android APK keeps `v<version>`.
- Bridge: new `/v1/local/*` control API for the desktop app. It answers only a loopback peer that sends the local secret; the phone's API is unchanged.
- Bridge: `hermes-remote-bridge supervise` restarts the bridge after a network change, backs off after crashes and stops instead of retrying when the port is already in use. Its log is `bridge.log` in the state directory, rotated at 2 MB.
- Known limit: on Linux a click on the "Trust this network?" notification cannot be detected; the window opens when the notification appears.
- Bridge: when no Hermes is running, it now starts Hermes through the `hermes` launcher if Hermes' venv is not under `hermes-agent/venv` (current Hermes installers). Before, the phone got "Hermes is not installed" and the chat socket was refused.
- Windows: the phone's Terminal works (it closed itself as soon as it opened).
- Windows: the phone's PC screen loads in well under a second (it took 9-16 s, so the app gave up), and "System Idle Process" is no longer listed as the top CPU user.

## v1.25.1

- Fixed a crash (app force closing) while a chat was streaming: a new run could reuse an
  item id already on screen.
- The Scheduled (clock) icon in the chat list now shows how many scheduled jobs are active.
  Paused jobs are not counted.

## v1.25.0

- Pictures returned by tools (vision_analyze, screenshots, browser captures) now show as images
  in the tool row instead of a wall of base64 text. Tap to open full screen.
- While the agent works, a *Steer / Queue* switch sits beside Send. Steer folds your message into
  the running reply (as before); Queue sends it as the next turn, run once the current reply ends.
  The choice is remembered.

## v1.24.0

- Background processes: a chat's background jobs (dev servers, builds, watchers the agent started
  in the background) now show under the transcript, like the subagent panel: the command, how long
  it has run, exit code once it ends. Tap one to read its latest output; *Stop* kills it on the PC.
  Updates every few seconds. Needs the updated bridge.

## v1.23.1

- *Show more* adds the number set in Settings > Chats per group (3, 5 or 10) instead of a fixed
  amount; v1.23.0 wrongly forced 5.

## v1.23.0

- Drawer groups: *Show more* now adds 5 chats at a time (was 10), and once a group is expanded a
  *Show less* button folds it back to its first page.
- Bridge doctor no longer reports "no firewall" when the PC runs inside a container (e.g.
  systemd-nspawn): the host's firewall can't be seen from there, so it now warns and says which
  port to allow on the host.

## v1.22.0

- New project: a project saved without a folder used to vanish (it was created, but the app hid
  it). It now shows in the project list as *No folder yet*; tap it to add one.
- Folder picker: the folder icon in the project's path field browses the PC's folders (Up, Home,
  New folder, git repos marked), so you no longer type a path from memory. An empty name takes
  the folder's name. Needs the updated bridge.
- System Monitor: every chart can be tapped or dragged to read the exact time and values. New
  Network and Disk I/O charts.
- Internet speed test of the PC (download, upload, ping, jitter, against Cloudflare) with a
  history chart. Needs the updated bridge.
- Claude usage popup shows more: rate tier, % left with the exact reset time, warning colours,
  lock reason, extra-usage credits, when it was last checked, a 24h / 7d / 30d history chart and
  each context slice's share of the window.

## v1.21.0

- Tap the Claude usage badge to open a usage popup instead of jumping to the System Monitor:
  session (5 hours) and weekly limits with reset times, this chat's context window as a stacked
  bar by category (system prompt, tools, memory, conversation...), and *Compact now* with an
  optional focus topic. The badge now shows Claude's mark with the session % beside it. The
  System Monitor is still one tap away from the popup. Context needs the updated bridge.
- System Monitor range and sort chips use the theme accent instead of Material purple.
- Move to project works once the bridge is restarted on the PC (it was still running the old
  allowlist).

## v1.20.0

- Move to project: long-press a chat and choose *Move to project* to file it under any project
  that has a folder, like the desktop's move. Needs the updated bridge.
- The Group by / Sort by / Status / Project chips now highlight in the theme's accent colour
  instead of Material's default purple.
- PC glance shows up reliably after pairing or a cold start. The app remembers which features the
  bridge offers and retries the bridge's hello a few times, so a slow network wakeup no longer
  hides the PC page until the next restart.
- Claude usage ring beside the connection badge: the outer ring is the 5-hour session, the inner
  ring is the week, and the number is the session %. It turns amber at 70% and red at 90%. Tap it
  to open the PC page. Shown only when the bridge has the PC monitor.
- On a tablet or in landscape, Enter sends the message and Shift+Enter adds a new line. On a phone
  in portrait, Enter still adds a new line.

## v1.19.0

- Switching between paired PCs no longer loses the profile dropdown. A reply from the PC you just
  left (its profile list, chats, scheduled jobs, settings or vault) could land on the one you
  switched to and replace its data; every reply is now checked against the PC and profile it was
  asked for. By **@Justcallmesu** (#13)
- Subagents. When the agent delegates work, a card under the chat shows each subagent: its task,
  current tool, tool count, model and running time, then how it ended. *Stop* ends one subagent
  without stopping the chat. Opening a chat mid-run catches up on the ones already running. By **@Justcallmesu** (#12)
- Bridge: the phone may call `subagent.list` and `subagent.interrupt` (needs the updated bridge). By **@Justcallmesu** (#12)
- Sign in to a gateway: the phone can now connect straight to a gated Hermes gateway (`hermes serve`
  behind its own login), with no bridge and nothing installed on the PC. In Add host, choose
  "Sign in to a gateway" and type its address. The gateway's login page opens in a browser tab; you
  type the password there, never in the app. Tokens are kept in the Android Keystore and refreshed
  automatically. Plain `http` is accepted only for Tailscale or LAN addresses. Pairing a bridge is unchanged. By **@LastNyx** (#14)
- Gateway hosts hide the features that need the bridge: the project
  description (IDEA.md), chat file download, the bridge status card, and the default-model and
  approval settings. Notifications while the app is closed do not work for a gateway host yet. By **@LastNyx** (#14)
- Updates can be installed before pairing: the pairing screen shows "Version X is available" when a
  newer app is out; tap it for what's new and Update now. Before, the update was only offered in the
  drawer and Settings, which an unpaired phone never reaches.
- Chat: each message shows when it was sent, under it (the time today, the date for older ones).
- PC switcher: a gateway host no longer offers Re-pair, which only applies to a paired bridge.

## v1.18.0

- PC Glance is now part of the app. The drawer header has a PC page (live CPU/RAM/temperature,
  disk, network, per-core bars, history from 1 hour to 1 year, alerts), a process list where you
  can stop or force-stop a process, and Claude plan usage (session and weekly limits with reset
  times and history). Two home-screen widgets: *PC stats* and *Claude usage*. Update the bridge
  on the PC for these; the separate PC Glance app and agent can be uninstalled.
- Terminal: a shell on your PC from the phone (the `>_` icon), with colours and a key bar (Esc,
  Tab, Ctrl, ^C, arrows). Turn it off with `terminal = false` in the bridge's config.toml.
- Re-pair: when a pairing breaks, Settings > Re-pair this PC (or the PC's ⋮ in the PC switcher)
  takes a fresh code and keeps the PC's chats, drafts and name; no more delete and pair again.
  When the PC refuses the phone's token, the app now says so and offers Re-pair instead of
  "can't reach your PC". On the PC: tray > Devices > (phone) > Re-pair, or
  `hermes-remote-bridge pair <name> --replace`, which issues a new code and revokes the old one.
- Tray (Linux and Windows): *Run doctor...* shows every check with its verdict.
- Typing `/` and picking a skill or command from the suggestions no longer drops the slash (the
  skill ran as plain text before).
- Chat top bar: removed the New chat and Pin buttons; they are in the drawer and the chat's ⋮ menu.
- Bridge: no longer crash-loops on start when Tailscale is installed but stopped; it listens on
  the remaining addresses instead of failing with "Cannot assign requested address".
- Bridge on Windows: a project description saved from the phone keeps its line endings as written.

## v1.17.0

- Projects can be managed from the phone, like the desktop's project menu: rename, change the
  folder, edit the description, or delete. Tap the pencil next to a project in the project picker,
  or long-press a project group in the drawer (grouped by project) and choose Edit project.
  Deleting removes the saved project from Hermes only; its chats, files and folder stay on the PC.
- New project: the folder is now optional, and you can write a description. Like the desktop's
  New project, the description is saved as `IDEA.md` in the project folder (Markdown is fine).
- Bridge: allows the phone `projects.get/update/delete/add_folder/remove_folder/set_primary`, and
  adds `GET/PUT /v1/projects/{id}/idea`, which only ever reads or writes `IDEA.md` in that
  project's own folder. Update the bridge on the PC for these.

## v1.16.0

- Chat files: files Hermes hands over in a reply (`MEDIA:` reports, spreadsheets, PDFs) and files
  sent with a message now show as cards. Tap a card to open it in a phone app (Sheets, a PDF
  viewer, ...); the arrow saves it to Downloads/Hermes Remote. Before, they were plain text, or
  "📎 name" with nothing to tap.
- Messages with a file no longer show Hermes' "--- Context Warnings ---" block, and files sent
  from the desktop (``@file:`path` ``) show up correctly.
- Pictures saved outside Hermes' image folders (e.g. a chart in a project) now load in the chat.
- Bridge: new `GET /v1/sessions/{id}/file`. It serves only files that chat's own messages name.
  Update the PC bridge (`git pull`, then restart it) to use this.

## v1.15.1

- Drawer: long-press a project folder (grouped by Project) → "New chat in <project>" starts a chat
  with that project already selected ("Home" starts one with no project).
- Project picker: a search field filters projects by name or folder.

## v1.15.0

- Drawer is compact so more chats fit: Scheduled and Settings are icons in the header, New chat is a
  round button next to search, and the header and profile row are slimmer.
- Vault moved into Settings (Hermes on your PC → Vault); Back from the Vault returns to Settings.

## v1.14.1

- Chat: a message with a picture no longer draws the picture on top of its text; the picture sits
  above the message.
- Picture viewer: a Download button next to Close saves the original picture to
  Pictures/Hermes Remote (Android 10+), or to a file you pick on Android 8/9.

## v1.14.0

- Drawer: "Sort by Last updated" now also orders project groups, so the project with the newest reply
  is on top (it used to stay A-Z). Other sorts (created, tokens, cost) order groups the same way.
- Custom group order: long-press any group header (project, date or status) to move it to the
  top, up, down or to the bottom. The order is saved on the phone, per PC, profile and grouping,
  and kept after restarts. View sheet → "Group order" switches between "Follow sort" and "Custom".
- Drawer cache: chats, projects and the custom order are stored on the phone and shown at once on
  start. They sync on start, when the app comes back to the screen, while chatting, after each
  reply and every ~30 s. If a sync fails, the saved list stays on screen.
- Reconnect: projects are fetched as soon as the live link is up, so chats no longer all land in
  Home until a manual refresh.

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
  older bridge the app stays on the default profile as before. By **@Justcallmesu** (#6)
- The chat header names the Hermes profile the chat runs in (next to its status), when the PC has
  more than one profile. By **@Justcallmesu** (#9)
- Bridge: `GET /v1/profiles` lists them, and the sessions, approvals and default model endpoints
  take `?profile=` so they act on one profile. The default profile works exactly as before. A new
  `allowed_profiles` key in `config.toml` limits which profiles the phone may use (empty: all). By **@Justcallmesu** (#6)
- Scheduled jobs. *Scheduled* in the drawer lists the cron jobs of the Hermes profile in use with
  their schedule, next and last run and last error; tap one for its recent runs. Run now, pause and
  resume work from the phone; creating and editing jobs stays on the PC. By **@Justcallmesu** (#10)
- Bridge: `GET /v1/cron/jobs`, `GET /v1/cron/jobs/{id}/runs` and `POST /v1/cron/jobs/{id}/pause|resume|trigger`,
  all per profile. Prompts, scripts and delivery targets are not sent to the phone. By **@Justcallmesu** (#10)
- Bridge: a request naming a profile is authenticated first, so an unpaired caller gets 401 and
  learns nothing about which profiles exist.
- Linux tray: a revoked device no longer stays in the Devices menu. By **@Justcallmesu** (#7)
- Linux: the tray installs as an app that starts at login, and the bridge CLI goes on PATH. By **@Justcallmesu** (#5)
- Windows tray: *Start at logon* and *Stop trusting* now actually work. By **@LastNyx** (#4)

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
