# Installing

Two parts: the bridge on your PC, the app on your phone. The bridge is the part with options.

## Requirements

The steps below are for Linux. **Windows** has its own section at the end.

| PC | Phone |
|---|---|
| Linux with systemd (developed on KDE Plasma, Arch-based), **or** Windows 10/11 (experimental) | Android 8.0+ |
| [Hermes Agent](https://hermes-agent.nousresearch.com) installed and running | |
| `git` | |
| [uv](https://docs.astral.sh/uv/) | |
| Optional: Tailscale; on Linux also NetworkManager and Avahi | Optional: Tailscale |

macOS is not supported yet. See [PLATFORMS.md](PLATFORMS.md) for what each OS does and does not
have.

Hermes' API server has to be reachable on `127.0.0.1:8642`. The installer turns it on if it is
not already.

## Install the bridge

```bash
git clone https://github.com/nideta231/hermes-mobile-remote.git
cd hermes-mobile-remote
./install.sh
```

The installer is safe to re-run. It:

1. Enables the Hermes API server on loopback with a random key, if it is not already enabled.
2. Installs the bridge into `bridge/.venv` and starts it as a systemd **user** service.
3. Asks whether the current network is yours (home or office) and should be trusted.
4. If a firewall (ufw or firewalld) blocks the bridge port, shows the exact rules and asks before
   adding them. This is the only step that needs your password.
5. Runs `doctor` to check everything over.

The installed unit file is a **copy**, not a symlink. Editing the unit in the repository does not
touch the running service until you run `./install.sh` again.

## Install the app

1. Download the APK from
   [Releases](https://github.com/nideta231/hermes-mobile-remote/releases/latest), or
   [build it yourself](DEVELOPMENT.md#build-the-app).
2. On the PC, pair the phone. This prints a QR code that is shown **once**:
   ```bash
   bridge/.venv/bin/hermes-remote-bridge pair phone
   ```
3. In the app, tap **Scan pairing QR code**.

Each QR code pairs exactly one device. To pair without a camera, the same command prints the URL
and token for manual entry.

## Connecting

The app picks the best path on its own. You only need to care when it cannot.

| Where you are | Connection | Tailscale needed? |
|---|---|---|
| A Wi-Fi you trusted | Direct, HTTPS with the PC's pinned certificate | No |
| Any other Wi-Fi | Tailscale only | Yes |
| Mobile data | Tailscale | Yes |

- **Trusting a network:** when the PC joins a network it has not seen, a desktop notification asks
  whether to trust it. You can also run `hermes-remote-bridge trust` while on it. The System tab in
  the app shows the current path and has manual switch buttons.
- **If the PC's IP changes,** the app finds it again over mDNS without re-pairing. On the PC side,
  network changes are picked up within about 5 seconds.

## Everyday commands

```bash
B=bridge/.venv/bin/hermes-remote-bridge

$B doctor                  # explains what is wrong if the phone cannot connect
$B pair phone              # pair a device (QR in the terminal; --qr-png file.png to save it)
$B devices                 # list paired devices
$B revoke phone            # takes effect immediately
$B trust                   # trust the current network
$B trust --list            # ...which networks are trusted
$B trust --remove ID       # ...stop trusting one
$B firewall                # allow the port from private networks (asks first)

journalctl --user -u hermes-remote-bridge -f    # live log
```

Revoking a device is immediate: the next request from it is `401`. Re-pairing under a name that is
already active fails, so revoke first.

## Rotating the Hermes key

Change `API_SERVER_KEY` in `~/.hermes/.env` and restart Hermes. The bridge reads the new key on its
next request; no bridge restart needed.

## Uninstall

```bash
./install.sh --uninstall
```

This stops and removes the systemd user service. It does **not** delete your data: paired devices
and settings stay in `~/.config/hermes-remote`, and the audit log in
`~/.local/state/hermes-remote`. Remove those yourself if you want a clean slate:

```bash
rm -rf ~/.config/hermes-remote ~/.local/state/hermes-remote
```

The app is removed by uninstalling it from Android. Because the release key is not published, a
release-signed app cannot be updated by a locally built one: uninstall before switching.

## Configuring

Everything is optional; the defaults are sensible. Config lives in
`~/.config/hermes-remote/config.toml`, and every key with its default is documented in
[`bridge/hermes_remote_bridge/config.py`](../bridge/hermes_remote_bridge/config.py).

```toml
port = 8650
lan = true                      # serve the local network on trusted networks
mdns = true                     # announce over mDNS so the app finds a changed IP
hermes_url = "http://127.0.0.1:8642"
allowed_logins = []             # Tailscale logins allowed; empty means this node's owner
max_body_bytes = 1_000_000
requests_per_minute = 240
runs_per_minute = 20
```

An unknown key is an error, not a silently ignored typo.

## Trying it without a Hermes install

The repository ships a demo stack with a synthetic backend — no keys, no model calls:
[`demo/README.md`](../demo/README.md).

## If something is wrong

[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Windows

Experimental: see [PLATFORMS.md](PLATFORMS.md#what-is-verified-on-windows) for exactly what is and
is not verified. Use native Windows, not WSL2.

From a normal (non-admin) Command Prompt or PowerShell window, or by double-clicking `install.cmd`
in the folder:

```powershell
git clone https://github.com/nideta231/hermes-mobile-remote.git
cd hermes-mobile-remote
install.cmd
```

Use `install.cmd`, not `install.ps1` directly. A stock Windows refuses unsigned scripts ("running
scripts is disabled on this system"), and `install.cmd` relaxes that for this one run only, so you
never touch the account-wide policy. If you downloaded a ZIP instead of cloning, the installer also
clears the "downloaded from the internet" mark on its scripts. Do not extract the ZIP and then edit
the execution policy by hand.

If `uv` (the Python package manager the bridge is installed with) is missing, the installer asks
and installs it for you, with `winget` or the official installer, no admin needed. Pass `-InstallUv`
(`install.cmd -InstallUv`) to skip the question. It then finds `uv` in the same window, so you do not have to reopen
PowerShell.

The installer does the same five things as the Linux one:

1. Checks `uv` and Hermes (`%LOCALAPPDATA%\hermes`, or `$env:HERMES_HOME`).
2. Turns on Hermes' API server with a fresh random key in Hermes' `.env`.
3. Installs the bridge into `bridge\.venv`.
4. Registers a Scheduled Task called **Hermes Mobile Remote** that starts at logon and restarts the
   bridge when the network changes.
5. Asks whether this is a network you trust, then offers the firewall rule (one UAC prompt, private
   ranges and the Private profile only).

Then pair the phone: double-click **`pair.cmd`** in the folder (the installer also offers to do it at
the end). It opens the QR code as a picture, which scans reliably; the Windows console draws block
characters badly. The `.exe` in `bridge\.venv\Scripts` needs a command after its name, so
double-clicking *that* only shows a help screen.

Check on it any time with `.\bridge\.venv\Scripts\hermes-remote-bridge.exe doctor`. To remove
it: `install.cmd -Uninstall` (keeps your paired devices and settings in
`%LOCALAPPDATA%\hermes-remote`).
