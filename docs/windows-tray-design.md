# Windows tray app - design and outcome

Status: **built and verified on this machine (Windows 11).** Sections marked *deferred* were
consciously left out. Where this document and the code disagree, the code is right and this
document is stale.

Replaced the CLI-only Windows experience with a tray app that supervises the bridge, shows
what is wrong, and handles trust, pairing and the firewall without a console.

## Management window: deferred

The design agreed a three-section window (Status | Networks | Devices). **It was not built.**
The tray menu covers all three, so the window would have been the same controls in a larger frame.
Build it when the menu is genuinely too small to use, not before.

## What was built

| Piece | Where |
|---|---|
| Tray app: icon, menu, supervisor, health polling, trust, pairing, firewall, start-at-logon | `bridge/windows/tray.ps1` |
| The supervisor policy, shared | `bridge/windows/supervise.ps1` |
| Headless entry point (RDP sessions, no desktop) | `bridge/windows/run-bridge.ps1` |
| Launcher when nothing is running | `tray.cmd` |
| Machine-readable CLI for the tray | `doctor --json`, `devices --json`, `trust --list --json` in `cli.py` |
| Cached network identity (fixes a multi-second `/v1/me`) | `network.py:current_network` |
| Port-conflict guard | `cli.py:_port_in_use` / `_bind` |
| Installer: task starts the tray, ordered uninstall, `-Purge`, no pairing | `install.ps1` |
| `doctor` reports the bridge, not the task, on Windows | `doctor.py:_check_service` |

## The Windows latency problem, and what actually fixed it

The tray was fine; the *bridge* was too slow to answer the phone. The app polls `/v1/me` and
reads a slow answer as **"connecting"**, not as "slow" - so the symptom looked like a connection
failure and sent the investigation in the wrong direction.

Measured on the author's machine: `/v1/me` took **4.5-4.9 s**. Every answer spawned PowerShell
three times, because each `Net*` cmdlet needs its own `powershell.exe`:

| Call | Cost |
|---|---|
| `interface_ips()` (psutil) | ~12 ms |
| `Get-NetRoute` | ~820 ms |
| `Get-NetNeighbor` | ~1070 ms |
| `Get-NetConnectionProfile` | ~780 ms |

`/v1/me` asked for the network twice (once directly, once through `serving_lan_ips()`), and
`serving_lan_ips()` then asked for the default route a *third* time to filter by it.

Two fixes, in this order, and the first one was wrong:

1. **Cache `current_network()`, keyed on the default route.** Useless: the key is itself a
   PowerShell call, so the cache cost as much as the value it avoided (1666 ms, barely moved).
2. **Cache keyed on `interface_ips()` instead** (psutil, 12 ms), and **stop `serving_lan_ips()`
   re-deriving the default route** - `net.lan_ips` is already filtered to that interface.

Result: **4500 ms -> 1-3 ms**. No app change was needed; the phone simply stopped being told it
was disconnected.

The safety rule that came out of it: a cache on hardware identity must offer `fresh=True`,
because the reason a router MAC is part of a network's identity is that a hotspot copying an
SSID does not inherit trust. `test_host.py` and `test_app.py` pin that.

## Verified

- `uv run pytest -q`: 94 passed (76 before this work; 18 new, in `tests/test_cli_json.py` and
  `tests/test_network_cache.py`).
- All four PowerShell scripts parse under Windows PowerShell 5.1 and contain zero non-ASCII bytes.
- The tray starts, the bridge binds `http://127.0.0.1:8650` and the LAN address within 5 s,
  `/v1/me` answers 401, and the tray is **still alive 90 s later** with no PowerShell crash events
  (this is the check that catches the GetContextFromTLS death; CI now does the same at 45 s).
- Killing the tray takes the bridge down, port released, no orphans.
- `install.cmd` run end to end, then re-checked well after it exited: still up.
- `install.ps1 -Uninstall` against a detached tray: tray killed, task unregistered, bridge gone,
  config kept. `-Purge` deletes `%LOCALAPPDATA%\hermes-remote`.
- Full cycle: uninstall (clean: no tray, bridge, task or firewall rule, config kept) → install →
  uninstall → install, with `Tab S8` surviving throughout.
- Re-running `install.cmd` over a **live** tray stops the old tray and supervisor and starts fresh
  ("Stopping the previous Hermes Remote (pid …)"), so the update path is the ordinary path.
- Revoking through the tray menu: clicking a device's revoke entry revokes **that** device
  (checked by driving the real handler and reading `devices.json`, not by reading the code).
- `/v1/me` latency, live: **1-3 ms** (was 4.5-4.9 s). A phone polling it now gets a fast answer.

**Not verified - please read before calling this supported:**

- **Nobody has clicked the tray menu.** Its contents were verified by dumping the real
  `Build-Menu` output with live data, and the revoke handler by driving `PerformClick()`, but the
  icon colours, the trust prompt, the pairing window and the firewall dialog have not been used by
  a human. A GitHub runner has no notification area, so CI cannot cover this either.
- **The firewall dialog needs a UAC prompt**, which no automated run can click. The rule is
  verified to be *absent* by default and the *command* to be correct; the end-to-end click is not.
- The firewall rule was removed by an uninstall test and `-Unattended` does not restore it, so a
  machine that has run the uninstall test will show `windows blocks port 8650` in `doctor` until
  someone applies the rule.

**Known rough edge, deliberately not fixed:** `pair` from the console (i.e. `pair.cmd`) still
writes a QR PNG and hands it to the default image handler, and never deletes it - so a live token
sits in `%LOCALAPPDATA%\hermes-remote\state\`. The tray path (`--qr-png` + its own window) does
not have this problem, and the installer no longer offers the console path. Changing the CLI's
behaviour would affect Linux too, so it is left as a separate decision.

## Running it

| Command | What it does |
|---|---|
| `install.cmd` | Install or update, register the logon task, start the tray |
| `tray.cmd` | Start the tray only. Says so and exits if it is already running |
| `pair.cmd` | Pair a phone (runs `doctor` first, then shows the QR) |
| `install.cmd -Uninstall` | Stop the tray, the task and the firewall rule; keeps config |
| `install.cmd -Uninstall -Purge` | Also delete `%LOCALAPPDATA%\hermes-remote` |

## Bugs found and fixed on the way

- **`SO_REUSEADDR` let a second bridge bind the same port on Windows** (measured). `bind()` was
  never a port guard; two instances silently shared 8650. Fixed with a pre-flight connect in
  `cli.py`, all platforms. The supervisor also stops instead of retrying forever when the bridge
  reports that conflict, since it cannot resolve itself.
- **The tray died about 15 s after starting, silently** - no stdout, no stderr, no exit code,
  orphaned supervisor left holding the port. Windows Error Reporting named it:
  `Management.Automation.ScriptBlock.GetContextFromTLS`, raised when a PowerShell script block
  runs on a `System.Threading.Timer` thread-pool thread that has no session state. Fixed by polling
  on a WinForms timer and doing the three bridge CLI calls in a real background runspace
  (`[PowerShell]::Create()`), collecting the result on the next tick.
- **The new-network prompt was shown before `Application.Run()`**, so the modal dialog had no
  message pump behind it. Moved to a WinForms timer that fires once the first poll has real data.
- **A modal dialog shown before the message loop** is a silent-death trap; the same reasoning is
  why pairing (`ShowDialog`) is only ever reached from a menu click.
- **PowerShell constructor traps in the tray**, each found by running it rather than parsing it:
  `New-Object Type($a, $b)` converts its arguments instead of passing them (`-ArgumentList` is
  required); `SystemInformation.SmallIconSize` is a `Size`, not a number; and only the
  one-argument `System.Threading.Timer` constructor binds from 5.1, so the period is set with
  `Change()`. A parse check passes all three.
- **`Timer.Change()` returns a bool** that leaked to stdout, so a healthy tray printed `True`.
- **A process probe that matched its own command line** - three separate times, in three places
  (`tray.cmd`'s "is it running?" check, the uninstaller, and the installer's stale-tray cleanup).
  A `-like '*tray.ps1*'` test matches the probe's own `powershell -Command` line, so the installer
  **killed itself** on every run, and the uninstaller died half-done because `taskkill` writing to
  stderr met `$ErrorActionPreference = 'Stop'`. Two rounds of "fixes" made it worse before the
  real shape was measured: quoting the path misses the Scheduled Task, which passes it unquoted.
  The pattern that works, verified against all four real command-line shapes, is
  `-File\s+\S*[\\/](tray|run-bridge)\.ps1` - `\S+` absorbs the optional quote. A character class is
  a trap here: `[^\"]` is an **invalid .NET regex** (a backslash cannot escape `"` inside a set) and
  fails at run time with `Unterminated [] set`. **Print the real command lines and test against
  them; do not derive the regex by eye.**
- **Menu handlers built in a loop shared one variable.** A bare `{ $name }` inside a `foreach`
  closes over the *last* value, so every "revoke" item acted on the last device in the list:
  clicking "phone - revoke" revoked something else and the phone stayed paired. Measured on 5.1:
  bare closures return `gamma, gamma, gamma`; `.GetNewClosure()` returns `alpha, beta, gamma`.
  Every loop-built handler now ends in `.GetNewClosure()`, and CI asserts it.
- **The menu was a snapshot built once at startup**, before any data existed, so it said
  "No network (offline)" forever and never listed a device. It is now rebuilt whenever the
  underlying state changes and again on right-click. "No answer yet" now reads
  "Checking network...", so offline is only reported when the bridge really says so.
- **The installer never verified the Scheduled Task launched.** An `AtLogOn` task that fails leaves
  `State=Ready` and `LastTaskResult=2`, indistinguishable from "not run yet", and the installer
  printed success anyway. It now starts the tray directly and reports the task result on failure.
- **`doctor` reported the task, not the bridge, on Windows.** Once the tray supervises, a task in
  `Ready` is the normal state, so `doctor` cried failure while everything worked. It now asks
  whether the port answers.

## What exists today (before this change)

| Piece | Where | What it does |
|---|---|---|
| Scheduled Task "Hermes Mobile Remote" | `install.ps1:140-153` | At logon, `RunLevel Limited`, `Interactive` logon type, runs `run-bridge.ps1` |
| `run-bridge.ps1` | `bridge/windows/run-bridge.ps1` | The supervisor: restarts on exit 75 (network changed), backs off on crash |
| `install.cmd` / `pair.cmd` | repo root | Launchers that bypass `ExecutionPolicy` |
| QR via Photos | `cli.py:260-266` + `host/windows.py:156` | `os.startfile` on a PNG - opens the default image handler, i.e. the Photos app |

The Photos-app QR exists because `host().qr_as_image()` returns `True` on Windows
(`host/windows.py:153`): a block-character QR in the Windows console is unscannable.

Two things the design must not break: the log contract
(`%LOCALAPPDATA%\hermes-remote\state\bridge.log`, UTF-8, 2 MB rotation) that `doctor` and the
docs describe, and the exit-75 restart contract that makes a Wi-Fi change take effect in ~2 s.

## Findings from reading the code

**`SO_REUSEADDR` makes the port not a guard on Windows.** `cli.py:54` sets it unconditionally.
Measured on this machine: a second `bind()` on the same address/port with `SO_REUSEADDR` set
**succeeds**. A pre-flight TCP connect correctly reports "something is listening". So the
decision "the bridge refuses to start if the port is taken" requires a real check; it does not
happen today. On Linux the flag is harmless (TIME_WAIT only), so the check belongs in the
shared `_bind` and helps both platforms.

**A process cannot watch for its own death.** Self-restart was requested and is impossible as
stated. The working version is a supervision chain: Task supervises the tray, tray supervises
the bridge. Different granularity is preserved where it matters - a network change restarts the
bridge in ~2 s, losing the tray waits out Task Scheduler's 1-minute granularity.

**A Scheduled Task at logon does not appear in Task Manager -> Startup.** The requested
"disable it at startup" toggle therefore has to live in the tray menu and unregister the task.
There is no Startup-folder entry to remove.

**`docs/WINDOWS.md` does not exist.** `host/windows.py:4` points at it for the verification
checklist; the checklist is actually `docs/PLATFORMS.md#what-is-verified-on-windows`.

**`run-bridge.ps1` cannot be dot-sourced.** It is `param()` followed by a top-level
`while ($true)`, so importing it would start an infinite loop. Sharing the supervisor logic
requires extracting it into a function, not including the file.

## Shape

```
  Task Scheduler (at logon)
        |
        v
  bridge/windows/tray.ps1          <- tray icon + supervisor + management window + QR window
        |  supervises, restarts on exit 75, backoff on crash
        v
  bridge/windows/supervise.ps1    <- the loop, as a function; both entry points call it
        |
        v
  .venv\Scripts\hermes-remote-bridge.exe serve
        |
        v
  bridge.log  (same path, same UTF-8, same rotation)
```

`run-bridge.ps1` remains as the headless entry point for sessions with no desktop (RDP, services
where the tray cannot draw), and is reduced to a thin wrapper over `supervise.ps1`.

### Tray

Single instance via a named mutex. Icon drawn in GDI+ from the app's own mark, rendered at
`SystemInformation.SmallIconSize` so it is sharp at the DPI it is shown at; **not**
`SystemIcons.Application`, which reads as unfinished. Tooltip truncated to 63 characters.

Menu:

- Status: the six `doctor` checks as coloured lines, refreshed on `ContextMenuStrip.Opening`
  so it is never stale
- Start / Stop / Restart bridge
- Management window (Status | Networks | Devices)
- Trust this network / Untrust, listing currently trusted networks
- Devices -> Pair new..., then each paired device with Revoke
- Copy last 50 log lines
- Open log folder
- **Start at logon** (checkbox; registers/unregisters the Scheduled Task)
- Quit tray (stops the bridge - a tray is not a service, and that trade is the point)

A balloon appears once per new network offering to trust it, which is the affordance Windows
lost by having no `notify-send` equivalent.

### Management window

Three sections, as chosen:

- **Status** - the six `doctor` checks, colour-coded, "Run now", bridge version
- **Networks** - current network, trust toggle, list of trusted networks with remove
- **Devices** - paired phones/tablets, Pair new, Revoke per device

No log tab and no firewall tab in this round.

### Pairing

The QR opens in a small always-on-top WinForms window that draws the PNG the bridge already
writes, with a **Copy** button for URL + token. No external viewer, so the Photos app stops
being involved. Pairing is reachable from the tray (a phone that needs re-pairing is exactly
when you are looking at the tray) *and* from `pair.cmd`, which opens the same window.

## Machine-readable CLI

The CLI is a human interface; the tray needs a machine one. Add a `--json` flag to the
existing subcommands - `trust --list`, `devices`, `doctor` - so the tray parses structured
data. **Human output is unchanged**, so the docs, CI assertions and the Linux experience all
still hold.

The tray does not read `networks.json` / `devices.json` directly: those are internal to the
bridge and going around the CLI would make the file layout load-bearing.

## The installer

`install.cmd` -> `install.ps1` does: check requirements, enable the Hermes API server,
`uv sync`, register the Scheduled Task, start the tray. The "is this your network?" question
moves out of the installer and into the tray balloon, where it can also be answered later.

`-Unattended` still must never create the firewall rule (CI asserts this).

### Uninstaller

Ordered, because the tray is now the supervisor and can be running detached:

1. stop the tray (a second launch must not be able to restart the bridge mid-teardown)
2. wait for it to exit
3. stop and unregister the Scheduled Task
4. kill any surviving `hermes-remote-bridge` process
5. remove the firewall rule (needs admin, one UAC prompt, as today)
6. print where config/devices/trusted networks live and how to delete them

`-Purge` additionally deletes `%LOCALAPPDATA%\hermes-remote`. Without it, a plain uninstall
keeps paired devices, config and trusted networks, exactly as today.

`-Uninstall` does **not** touch `API_SERVER_*` in Hermes' `.env`. It only enabled the API
server; something else may be using it. It prints where the key is and how to remove it.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Tray implementation | PowerShell WinForms, `tray.ps1` | No new Python deps, no build step, matches the repo's zero-compile ethos, covered by the existing CI parse checks |
| Supervisor ownership | Tray supervises the bridge; the task supervises the tray | One place restarts the bridge; the chain still survives the tray being killed |
| Auto-start | Task at logon, tray checkbox to unregister | A task is invisible in Task Manager -> Startup, so the toggle must be in the tray |
| Health data | `doctor --json` on a timer, rendered as a status list | The human detail the user wants, with structured output to parse |
| QR presentation | In-app always-on-top window + Copy button | Ends the Photos-app dependency |
| Machine interface | `--json` flags on existing subcommands | CLI stays the boundary; internal JSON files do not become load-bearing |
| Port conflict | Pre-flight connect in the shared `_bind` | `SO_REUSEADDR` means bind() does not guard the port on Windows |
| File layout | `tray.ps1` (one file) + `supervise.ps1` (shared function) | The tray stays one readable file; the supervisor policy exists once |
| Verification | Real install here, CI as floor | This machine can actually run it |

## Known traps to implement against

From `windows-tray-launcher` and the code itself:

- **Keep the `.ps1` files pure ASCII.** PowerShell 5.1 reads a BOM-less file as the ANSI code
  page; a stray em dash corrupts it and fails with a misleading `Unexpected token`. CI already
  asserts this for the existing scripts - extend it to the new ones.
- **Do not read the child's pipes from PowerShell.** `cmd /c "... >> log 2>&1"` with
  `cmd` doing the redirection. `Register-ObjectEvent -Action` handlers never fire once
  `[Application]::Run()` blocks the runspace, so `OutputDataReceived` silently writes nothing.
- **Events need `add_Click(...)`, not `.Click = {...}`** - and `ToolStripItemCollection.Add(string)`
  returns an item with no `Click` at all. Build `ToolStripMenuItem` explicitly.
- **The timer event name differs per timer type**: `System.Windows.Forms.Timer` raises `Tick`;
  `System.Timers.Timer` raises `Elapsed` and has no `Tick` member. Confirm the member exists
  before wiring it.
- **A polling state machine must capture the previous state before overwriting it**, or the
  "recovered" notification repeats every tick. Check `HasExited` *before* polling health, so a
  crash is distinguishable from a slow boot.
- **Function definition order is load-bearing** - top-level code cannot call a helper defined
  later in the file. A single-instance check at the top will hit this.
- **Kill the process tree** (`taskkill /PID <id> /T /F`), not the PID.
- **`doctor` takes seconds and must not block the UI thread** - `Application.Run()` does not
  yield to PowerShell event handlers, so a synchronous call freezes the window and its own
  status light.
- **Mutating a state machine is not a build signal.** `trust` is idempotent;
  `untrust <id>` and `revoke <name>` are not.
- `NotifyIcon.Text` over 63 characters throws, it does not truncate.
- A hidden launcher window that dies silently is the worst failure mode a tray has - every
  startup error must surface as a dialog or a log line.

## Deferred

| Deferred | Revisit when |
|---|---|
| Log tail / firewall / RDP / restart-Hermes controls in the window | The three chosen sections feel insufficient in real use |
| Themed dialogs | The user asks the tray to match the Hermes desktop theme. `MessageBox` cannot be restyled at all; this means rebuilding dialogs as plain `Form`s |
| macOS parity | `host/macos.py` is a stub with no local-network path; the same tray shape would apply, but there is no service manager story yet |
| Bundle / installer for non-technical users | The current audience runs `git clone` + `install.cmd` |

## Open questions

1. **A chicken-and-egg in the installer.** The installer currently confirms the bridge answers
   (`install.ps1:159-172`) before printing success. If the tray supervises the bridge, does the
   installer still start the bridge directly to verify it, or does it start the tray and check
   the tray? My inclination: keep the direct check (it is a stronger signal and it is what CI
   asserts on), then start the tray.
2. **Balloon on first run of an untrusted network, before the user has ever opened the tray.**
   A `notify-send` equivalent does not exist on Windows, and the tray is the thing that would
   show it - so the *first* trust prompt cannot be a balloon. Options: let the installer ask once
   (today's behaviour), or accept that the first network must be trusted from the tray window.
3. **Whether the window is worth its weight at all.** The tray menu alone covers Trust, Devices
   and the status list. The window's argument is discoverability and room to grow. If it turns
   out to be three checkboxes in a window, the menu was enough.
4. **Colour-coded checks in the window**, given `windows-tray-launcher`'s warning that WinForms
   theming is largely impossible. Colouring three status labels is achievable; a themed window
   is a different project.
5. **What the trust balloon should look like when declined.** Linux records a decline and does
   not ask again. The tray should match that behaviour, or every Wi-Fi change will re-prompt.

## Also to fix while in here

- `host/windows.py:4` references a non-existent `docs/WINDOWS.md`; point it at
  `docs/PLATFORMS.md#what-is-verified-on-windows`.
- `docs/PLATFORMS.md` says the Scheduled Task is the service; it will need rewriting to
  describe the tray, with `run-bridge.ps1` as the headless alternative.
- `docs/INSTALL.md`'s Windows section tells the reader to run `hermes-remote-bridge trust` by
  hand; the tray replaces that.
- The `SO_REUSEADDR` finding above is a real bug independent of this work and deserves its own
  commit and test, before the tray relies on it.
