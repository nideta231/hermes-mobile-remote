# Platforms

Which operating systems the bridge runs on, how far each one is supported, and how a new one is
added. The phone side is Android everywhere; this page is about the PC.

| PC | Status | Service | Wi-Fi trust | Verified by |
|---|---|---|---|---|
| **Linux** (developed on Arch-based, KDE Plasma) | Supported | systemd user service | NetworkManager + router MAC | Daily use, CI on Ubuntu |
| **Windows 10 / 11** | Experimental | Scheduled Task at logon | Connection profile + router MAC | CI on `windows-latest`; see below |
| **macOS** | Not yet | n/a | n/a | n/a |

"Experimental" has a specific meaning here: it is written and tested, but the author does not run
it daily and has no Windows machine. Read [What is verified on Windows](#what-is-verified-on-windows)
before relying on it.

## What is the same everywhere

Everything that is not the operating system: the API, pairing, device tokens, the trust model
(a network is identified by its profile *and* the router's MAC), the pinned HTTPS on the LAN,
Tailscale identity checks, rate limits, the audit log, approvals, runs. One code path, one test
suite. See [ARCHITECTURE.md](ARCHITECTURE.md).

## What differs

All of it lives in `bridge/hermes_remote_bridge/host/`, one module per OS behind one interface
(`host/base.py`). The rest of the bridge never checks which OS it is on.

| Concern | Linux | Windows |
|---|---|---|
| Config and state | `~/.config/hermes-remote`, `~/.local/state/hermes-remote` (XDG) | `%LOCALAPPDATA%\hermes-remote\config`, `...\state` |
| Hermes location | `~/.hermes` | `%LOCALAPPDATA%\hermes` (Hermes' own default); `HERMES_HOME` overrides both |
| Background service | systemd user unit, restarts on exit 75 | Scheduled Task at logon starting the **tray app** (`bridge/windows/tray.ps1`), which runs the bridge and restarts it on exit 75 (backing off on a crash). The task is the logon trigger and nothing else; the tray checkbox turns it off |
| Headless (no desktop) | the same unit | `bridge/windows/run-bridge.ps1` - the same supervisor without the tray, for an RDP session |
| Default route, router MAC | `/proc/net/route`, `/proc/net/arp` | `Get-NetRoute`, `Get-NetNeighbor` |
| Network name | NetworkManager (`nmcli`) | `Get-NetConnectionProfile` (the SSID on Wi-Fi) |
| Tailscale | LocalAPI unix socket | `tailscale status --json` / `whois --json` (no socket exists) |
| Firewall | `ufw` or `firewalld`, via `pkexec` | Windows Defender Firewall rule, Private profile only, via one UAC prompt |
| mDNS | Avahi | `zeroconf` (Python) |
| "Trust this network?" prompt | `notify-send` | the tray asks once per new network |
| TLS certificate | `cryptography` | `cryptography` (no `openssl` needed) |

The supervisor policy (exit 75, backoff) lives once, in `bridge/windows/supervise.ps1`; the tray
and the headless script both call it.

## Windows

Install: see [INSTALL.md](INSTALL.md#windows). Things that are specific to it:

- **No admin needed**, except for the firewall rule, which is optional and asks first.
- **WSL2 is not supported.** WSL2 sits behind a NAT with its own address, so the phone cannot reach
  a bridge running inside it, and it cannot see the Windows network profile. Install Hermes and the
  bridge natively; Hermes itself runs natively on Windows.
- **The tray app** (`bridge/windows/tray.ps1`) runs the bridge and shows its state in the
  notification area: green when the bridge is up and every check passes, amber while starting,
  red when a check fails. Its menu holds Start/Stop/Restart, the six `doctor` checks, trusting the
  current network, listing trusted networks, pairing a phone (the QR opens in a window of its own,
  not the Photos app), revoking a device, and **Start at logon** - which unregisters the Scheduled
  Task, because a logon task does not appear in Task Manager's Startup tab. Quitting the tray stops
  the bridge; that is the trade for having no service machinery.
- **Trusting a network** is asked once per new network, the way the Linux notification does. The
  command is still there when you want it: `hermes-remote-bridge trust`.
- **Desktop hand-off (RDP).** The app's "Desktop" tab expects an RDP server on port 3389. Windows
  Pro has one built in (Settings, System, Remote Desktop); Home does not. The bridge reports the
  desktop as up when the port answers.
- **Logs** are in `%LOCALAPPDATA%\hermes-remote\state\bridge.log` (and `audit.log`, `tray.log`
  beside it).

### What is verified on Windows

CI runs on a real `windows-latest` machine on every change. That proves:

- the whole test suite passes on Windows;
- `Get-NetRoute`, `Get-NetNeighbor`, `Get-NetConnectionProfile` and the firewall cmdlets return
  data the parser understands, and the bridge computes a network identity from them;
- `install.ps1` registers the Scheduled Task, the task starts the tray, the tray brings the bridge
  up on 8650, killing the tray takes the bridge with it, and `install.ps1 -Uninstall` removes both;
- the tray, the headless runner and the shared supervisor parse under Windows PowerShell 5.1 and
  contain no non-ASCII bytes;
- `doctor`, `devices` and `trust --list` run without crashing, and answer `--json` for the tray;
- the supervisor policy exists in exactly one file (a CI check fails if it is copied into another).

It has also been run by hand on a Windows 11 machine, where the following were confirmed by
running them, not by reading the code: install, uninstall (`-Uninstall` and `-Purge`), re-running
the installer over a live install, the tray starting the bridge on loopback and the LAN address,
the tray surviving 90 s without a PowerShell crash, killing the tray taking the bridge with it
and releasing the port, revoking a device from the tray menu, and `/v1/me` answering in 1-3 ms
(was 4.5-4.9 s before the network-identity cache; see
[windows-tray-design.md](windows-tray-design.md)).

**Still not verified:** no human has clicked the tray menu. Its contents were checked by dumping
the real `Build-Menu` output, and the revoke handler by driving `PerformClick()`, but the icon
colours, the trust prompt, the pairing window and the firewall dialog have not been used by a
person - and a GitHub runner has no notification area, so CI cannot cover it either. The firewall
dialog also needs a UAC click, which no automated run can perform.

CI does **not** prove, because a GitHub runner has no Wi-Fi, no phone and no Tailscale:

- a phone can connect over Windows Wi-Fi or Tailscale;
- the firewall rule is accepted and then lets the phone through (the runner's router MAC is also
  synthetic, `12:34:56:78:9a:bc`);
- Tailscale's CLI output on Windows matches what the parser expects;
- the Scheduled Task survives a real logoff, sleep and wake;
- mDNS announcements reach the phone.

If you run it on Windows, **please report what worked and what did not**: open an issue with the
output of `hermes-remote-bridge doctor`. That is the fastest way to move this from experimental to
supported.

## macOS

Not implemented, and the seam is already there: `host/macos.py` exists and returns the right
config directory and finds the Tailscale CLI. What is missing is the local-network path, so on a
Mac today the bridge would serve loopback and Tailscale only:

| Needed | Likely approach |
|---|---|
| Default route and gateway | `route -n get default` |
| Router MAC | `arp -n <gateway>` |
| Network name | `networksetup -getairportnetwork <interface>` (changed in recent macOS versions) |
| Service | a launchd agent in `~/Library/LaunchAgents` |
| Firewall | the application firewall prompts per app; probably nothing to automate |
| mDNS | `zeroconf`, already shared |

It is listed in [ROADMAP.md](ROADMAP.md). If you want to take it, the work is one module and a
`install-macos.sh`; see [Adding a platform](#adding-a-platform).

## Adding a platform

1. Add `host/<os>.py` with a class extending `Host`. Override only what differs; the base class
   has safe defaults, and anything you leave out simply turns that feature off.
2. Add one branch to `_load()` in `host/__init__.py`.
3. Write an installer that registers the bridge with the OS's service manager and restarts it
   when it exits with status 75.
4. Add tests in `bridge/tests/test_host.py` with the OS commands' output replaced, so they run on
   every platform.
5. Add a CI job on that OS that runs the real commands, as `bridge-windows` does. Do not claim
   support until that job exists.
6. Update the table at the top of this page.
