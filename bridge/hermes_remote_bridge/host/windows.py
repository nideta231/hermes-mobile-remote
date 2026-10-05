"""Windows 10/11: PowerShell NetTCPIP cmdlets, Scheduled Task, Windows Defender Firewall.

Written against Microsoft's documented cmdlets and unit-tested with their output replaced, and
exercised for real on a Windows 11 machine (tray start, bridge up, tray quit). See
docs/PLATFORMS.md#what-is-verified-on-windows for what that does and does not prove.
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
from pathlib import Path

from .base import Host

TASK_NAME = "Hermes Mobile Remote"
FIREWALL_RULE = "Hermes Mobile Remote"
_NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW: don't flash a console when run from the task


def _local_appdata() -> Path:
    return Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")


def powershell() -> str | None:
    return shutil.which("powershell") or shutil.which("pwsh")


def encode_script(script: str) -> str:
    """Base64 of the UTF-16LE script, the form ``powershell -EncodedCommand`` takes."""
    return base64.b64encode(script.encode("utf-16-le")).decode("ascii")


def ps(script: str, timeout: float = 15.0) -> str:
    """Run a PowerShell snippet and return stdout; "" when PowerShell is missing or it fails."""
    exe = powershell()
    if not exe:
        return ""
    try:
        r = subprocess.run([exe, "-NoProfile", "-NonInteractive", "-EncodedCommand", encode_script(script)],
                           capture_output=True, text=True, timeout=timeout,
                           creationflags=_NO_WINDOW if os.name == "nt" else 0)
    except (OSError, subprocess.SubprocessError):
        return ""
    return r.stdout if r.returncode == 0 else ""


def ps_json(script: str) -> list[dict]:
    """Run a snippet that ends in ``ConvertTo-Json`` and always return a list of objects.

    ConvertTo-Json emits a bare object for one result and an array for several; callers should not
    care which.
    """
    out = ps(script).strip()
    if not out:
        return []
    try:
        data = json.loads(out)
    except ValueError:
        return []
    return data if isinstance(data, list) else [data]


def parse_default_routes(rows: list[dict]) -> tuple[str, str] | None:
    """Pick (interface, gateway) from Get-NetRoute rows: lowest combined metric, on-link routes
    (NextHop 0.0.0.0) excluded because they have no router to identify the network by."""
    best: tuple[int, str, str] | None = None
    for row in rows:
        gw, alias = str(row.get("Gateway") or ""), str(row.get("Alias") or "")
        if not alias or gw in ("", "0.0.0.0"):
            continue
        metric = int(row.get("Metric") or 0)
        if best is None or metric < best[0]:
            best = (metric, alias, gw)
    return (best[1], best[2]) if best else None


def normalise_mac(raw: str) -> str | None:
    """"AA-BB-CC-DD-EE-FF" (Windows) to "aa:bb:cc:dd:ee:ff"; None for blanks and all-zero."""
    mac = raw.strip().lower().replace("-", ":")
    if len(mac.split(":")) != 6 or set(mac) <= {"0", ":"}:
        return None
    return mac


class WindowsHost(Host):
    name = "windows"
    venv_bin = "Scripts"
    exe_suffix = ".exe"

    def config_dir(self) -> Path:
        return _local_appdata() / "hermes-remote" / "config"

    def state_dir(self) -> Path:
        return _local_appdata() / "hermes-remote" / "state"

    def default_hermes_home(self) -> Path:
        return _local_appdata() / "hermes"

    def default_route(self) -> tuple[str, str] | None:
        rows = ps_json(
            "Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 | ForEach-Object { "
            "$i = Get-NetIPInterface -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4; "
            "[pscustomobject]@{Alias=$_.InterfaceAlias; Gateway=$_.NextHop; "
            "Metric=([int]$_.RouteMetric + [int]$i.InterfaceMetric)} } | ConvertTo-Json -Compress")
        return parse_default_routes(rows)

    def gateway_mac(self, gateway: str) -> str | None:
        if not all(c.isdigit() or c == "." for c in gateway):
            return None  # the value is interpolated into a script; only ever an IPv4 literal
        out = ps(f"(Get-NetNeighbor -IPAddress '{gateway}' -AddressFamily IPv4 -ErrorAction SilentlyContinue | "
                 "Where-Object { $_.State -notin 'Unreachable','Incomplete' } | "
                 "Select-Object -First 1).LinkLayerAddress")
        return normalise_mac(out)

    def network_profile(self, interface: str) -> tuple[str, str] | None:
        """(connection profile GUID, name) from Get-NetConnectionProfile; the name is the SSID on Wi-Fi."""
        safe = interface.replace("'", "''")
        rows = ps_json(f"Get-NetConnectionProfile -InterfaceAlias '{safe}' -ErrorAction SilentlyContinue | "
                       "Select-Object Name, InstanceID | ConvertTo-Json -Compress")
        if not rows or not rows[0].get("InstanceID"):
            return None
        return str(rows[0]["InstanceID"]), str(rows[0].get("Name") or interface)

    def tailscale_cli(self) -> str | None:
        found = shutil.which("tailscale")
        if found:
            return found
        default = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale" / "tailscale.exe"
        return str(default) if default.exists() else None

    def service_state(self, name: str) -> str:
        """State of the Scheduled Task that runs the bridge ("active" when running). Other service
        names are not tracked on Windows: callers fall back to probing the port."""
        if name != "hermes-remote-bridge":
            return "unknown"
        state = ps(f"(Get-ScheduledTask -TaskName '{TASK_NAME}' -ErrorAction SilentlyContinue).State").strip()
        return {"Running": "active", "": "unknown"}.get(state, state.lower())

    # ------------------------------------------------------------------ interactive use

    def launched_by_double_click(self) -> bool:
        """A console shared with its launcher (cmd, PowerShell) has 2+ processes attached; a window
        Explorer opened just for us has exactly one."""
        try:
            import ctypes
            buf = (ctypes.c_uint * 4)()
            return ctypes.windll.kernel32.GetConsoleProcessList(buf, 4) <= 1
        except (AttributeError, OSError):
            return False

    def qr_as_image(self) -> bool:
        return True

    def open_file(self, path: Path) -> bool:
        try:
            os.startfile(str(path))  # noqa: S606 - the user's own file, default viewer
            return True
        except (AttributeError, OSError):
            return False

    # ------------------------------------------------------------------ firewall

    def firewall_active(self) -> bool:
        return ps("(Get-NetFirewallProfile | Where-Object { $_.Enabled -eq 'True' }).Count").strip() not in ("", "0")

    def firewall_rule_present(self, port: int) -> bool:
        out = ps(f"Get-NetFirewallRule -DisplayName '{FIREWALL_RULE}' -ErrorAction SilentlyContinue | "
                 "Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' } | "
                 "Get-NetFirewallPortFilter | Select-Object -ExpandProperty LocalPort")
        return str(port) in out.split()

    def run_elevated(self, script: str) -> bool:
        """Run ``script`` in an elevated PowerShell (one UAC prompt); True when it exits 0."""
        exe = powershell()
        if not exe:
            return False
        wrapper = ("$p = Start-Process -FilePath '%s' -Verb RunAs -Wait -PassThru -ArgumentList "
                   "'-NoProfile','-NonInteractive','-EncodedCommand','%s'; exit $p.ExitCode"
                   % (exe.replace("'", "''"), encode_script(script)))
        try:
            return subprocess.run([exe, "-NoProfile", "-EncodedCommand", encode_script(wrapper)],
                                  timeout=180).returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False
