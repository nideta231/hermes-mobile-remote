# Troubleshooting

Start with `bridge/.venv/bin/hermes-remote-bridge doctor`. It checks the things that are actually
worth checking and explains what is wrong. Everything below is a symptom it may not name.

## The phone cannot connect at all

On **Windows**, the same checks apply with different commands: `Get-ScheduledTask -TaskName
'Hermes Mobile Remote'` instead of `systemctl`, `%LOCALAPPDATA%\hermes-remote\state\bridge.log`
instead of `journalctl`, and `hermes-remote-bridge firewall` opens Windows Defender Firewall
(one UAC prompt). There is no "trust this network?" popup: run `hermes-remote-bridge trust`.
WSL2 cannot work; see [PLATFORMS.md](PLATFORMS.md).

**Windows: "running scripts is disabled on this system".** Run `install.cmd` instead of
`install.ps1`. It relaxes the policy for that one run only; do not change the policy yourself.

**Windows: a window flashed "the following arguments are required: command".** That is the bridge
`.exe` double-clicked with nothing after its name. Use `pair.cmd` to pair, or open a prompt and run
`hermes-remote-bridge doctor`. Current versions show a help screen instead of that error.

**Windows: "uv is required".** Older copies of the installer stopped here. Pull the latest, or run
`winget install --id=astral-sh.uv`, open a *new* PowerShell window (the old one does not see the
new PATH), and run the installer again.

**`forbidden_network` / "The PC doesn't serve this network."**

The bridge only serves loopback, Tailscale, and a Wi-Fi network you marked trusted. On the PC:

```bash
bridge/.venv/bin/hermes-remote-bridge trust          # trust the network you are on now
bridge/.venv/bin/hermes-remote-bridge trust --list   # what is trusted
```

You are trusting the network you *control*. On someone else's Wi-Fi, use Tailscale instead — the
LAN listener is not supposed to exist there.

**It worked yesterday on the same Wi-Fi.**

The PC moved to a different network, or the router's MAC changed, or Tailscale went down. Check
`journalctl --user -u hermes-remote-bridge -f`; the bridge logs every listen-set change. It rebinds
within about 5 seconds of a change, so a phone that cannot connect afterwards is usually an IP
change the app has not found yet — leave the app open for a moment, or check that Avahi is running
if you rely on mDNS.

**Connection timeouts on the LAN.**

Check the firewall:

```bash
bridge/.venv/bin/hermes-remote-bridge firewall
```

It prints the exact rules for ufw or firewalld and asks before applying them. Only private ranges
are opened.

**`forbidden_peer` / "Bridge refused this Tailscale identity."**

The request came from a tailnet address that is not your login. Either someone else on your tailnet
is trying to connect, or `allowed_logins` in `config.toml` is too narrow. Empty means "the owner of
this node".

## The app is paired but shows nothing

**"This device was revoked or the token is wrong. Pair again."**

The device was revoked, or `~/.config/hermes-remote/devices.json` was edited or replaced. Re-pair.

**The connection flips between LAN and Tailscale.**

The System tab shows the current path with manual switches. Automatic re-selection happens on a
network change, every 20 s while the app is open, and when you return to the app. If both paths are
reachable it prefers the LAN.

**It was working and stopped after a Hermes restart.**

Check that Hermes' API server is actually up: `hermes config get api_server.enabled`, and that
`API_SERVER_KEY` is still set in `~/.hermes/.env`. The bridge reports this as
`Hermes returned HTTP 401` in the app.

## Runs misbehave

**"This session already has an active run."**

By design: one run per session. Open a new chat, or stop the running one.

**The reply shows up in bursts instead of streaming.**

Only runs started *in the app* stream token by token. If the session was started on the desktop,
CLI or a messaging platform, the app follows it by polling, so it appears as Hermes persists each
step.

**A run stopped mid-way and the app says it was interrupted.**

Hermes' event stream is single-consumer with no replay; the bridge owns that subscription and
reconnects for you. When even that cannot settle the run, it reports `run.interrupted` rather than
spinning forever. Check the bridge log for the run id.

**"Too many requests" / "Too many runs started".**

The per-device limits are 240 requests/min and 20 runs/min. Usually a reconnect loop: a phone on a
network that drops every few seconds re-requests the session list. Fix the network, not the limit.

## Slash commands

**A command is not in the list.**

Only commands that act on stored state are offered, plus your skills. `/retry`, `/undo`, `/btw`,
`/goal` and `/usage` need a live agent in the gateway process, which the bridge does not keep
loaded, so they are hidden rather than shown broken.

**"/xyz isn't available in the app"**

The command exists in Hermes but is not one the app offers. That is the same rule as above.

**No commands at all.**

The bridge could not start the gateway: it looks for Hermes' Python at `hermes_python` in
`config.toml`. Check that path, and that `hermes_root` still contains `tui_gateway`.

## Notifications

**No notification when a run finishes.**

Only runs started in the app raise one. Also check that the app is allowed to run in the
background: Xiaomi, Huawei and some other vendors kill background apps aggressively, and the
setting is per-vendor and easy to miss.

## Updates

**The in-app update never appears.**

The app checks GitHub Releases at startup. Behind a captive network or with no internet, the check
fails silently by design — it is the only traffic outside the bridge and it carries no token.

**Android refuses to install the update.**

A self-built APK is signed with a different key than the releases, and Android only replaces an app
signed with the same key. Uninstall, then install the release.

## The bridge itself

**The service will not start.**

```bash
systemctl --user status hermes-remote-bridge
journalctl --user -u hermes-remote-bridge -n 50
```

The two most common causes: the Hermes API key is missing from `~/.hermes/.env`, or a saved network
went away and the bridge is trying to rebind an address it no longer has. It exits with status 75
when the listen set changed, and systemd restarts it.

**Editing the unit file changed nothing.**

The installed unit is a copy, not a symlink. Re-run `./install.sh`.

**I want to see everything the bridge is doing.**

`hermes-remote-bridge doctor`, then the audit log:

```bash
tail -f ~/.local/state/hermes-remote/audit.log
```

It records device, path, status and duration. It never contains tokens or prompts.

## Reporting a bug

Include `doctor` output, the relevant bridge log lines, the app's Android version, and how to
reproduce it. Redact anything from a screenshot that you would not post publicly.
