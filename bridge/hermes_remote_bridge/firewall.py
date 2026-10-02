"""Open the bridge port on the host firewall, after asking.

The bridge binds the Wi-Fi address only on trusted networks, so allowing the port from private
ranges does not expose it elsewhere. Changes go through pkexec on Linux (one polkit password
prompt) or an elevated PowerShell on Windows (one UAC prompt), and are never made silently.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .host import host

PRIVATE_RANGES = ("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
COMMENT = "Hermes Mobile Remote"


@dataclass
class FirewallState:
    kind: str  # "ufw", "firewalld", "windows", "none" or "unknown"
    active: bool
    port_open: bool | None  # None: can't tell without root

    @property
    def needs_opening(self) -> bool:
        return self.active and self.port_open is False


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=15)


def _ufw_state(port: int) -> FirewallState:
    conf = Path("/etc/ufw/ufw.conf")
    active = conf.exists() and re.search(r"^ENABLED=yes", conf.read_text(), re.M) is not None
    rules = Path("/etc/ufw/user.rules")
    try:
        text = rules.read_text()
    except OSError:
        return FirewallState("ufw", active, None)
    allowed = {m for m in re.findall(rf"--dport {port} -s (\S+) -j ACCEPT", text)}
    open_all = re.search(rf"-p tcp --dport {port} -j ACCEPT", text) is not None
    return FirewallState("ufw", active, open_all or all(r in allowed for r in PRIVATE_RANGES))


def _firewalld_state(port: int) -> FirewallState:
    if _run(["firewall-cmd", "--state"]).returncode != 0:
        return FirewallState("firewalld", False, None)
    ports = _run(["firewall-cmd", "--list-ports"]).stdout.split()
    rich = _run(["firewall-cmd", "--list-rich-rules"]).stdout
    open_ = f"{port}/tcp" in ports or all(
        f'source address="{r}"' in rich and f'port="{port}"' in rich for r in PRIVATE_RANGES)
    return FirewallState("firewalld", True, open_)


def _windows_state(port: int) -> FirewallState:
    h = host()
    if not h.firewall_active():
        return FirewallState("windows", False, None)
    return FirewallState("windows", True, h.firewall_rule_present(port))


def state(port: int) -> FirewallState:
    if host().name == "windows":
        return _windows_state(port)
    if shutil.which("ufw") and Path("/etc/ufw/ufw.conf").exists():
        s = _ufw_state(port)
        if s.active:
            return s
    if shutil.which("firewall-cmd"):
        s = _firewalld_state(port)
        if s.active:
            return s
    if shutil.which("ufw") or shutil.which("firewall-cmd"):
        return FirewallState("none", False, True)
    if shutil.which("nft") or shutil.which("iptables"):
        return FirewallState("unknown", False, None)
    return FirewallState("none", False, True)


def open_commands(kind: str, port: int) -> list[list[str]]:
    """The exact commands that allow ``port`` from private ranges (shown to the user first)."""
    if kind == "ufw":
        return [["ufw", "allow", "proto", "tcp", "from", r, "to", "any", "port", str(port),
                 "comment", COMMENT] for r in PRIVATE_RANGES]
    if kind == "firewalld":
        cmds = [["firewall-cmd", "--permanent", "--add-rich-rule",
                 f'rule family="ipv4" source address="{r}" port port="{port}" protocol="tcp" accept']
                for r in PRIVATE_RANGES]
        return cmds + [["firewall-cmd", "--reload"]]
    if kind == "windows":
        return [["New-NetFirewallRule", "-DisplayName", f"'{COMMENT}'", "-Direction", "Inbound", "-Action", "Allow",
                 "-Protocol", "TCP", "-LocalPort", str(port), "-Profile", "Private",
                 "-RemoteAddress", ",".join(PRIVATE_RANGES)]]
    return []


def open_port(kind: str, port: int) -> bool:
    """Run :func:`open_commands` with elevated rights (pkexec / UAC). Returns True on success."""
    cmds = open_commands(kind, port)
    if kind == "windows":
        return bool(cmds) and host().run_elevated("\n".join(" ".join(c) for c in cmds))
    if not cmds or not shutil.which("pkexec"):
        return False
    script = " && ".join(" ".join(_quote(a) for a in c) for c in cmds)
    return subprocess.run(["pkexec", "/bin/sh", "-c", script]).returncode == 0


def _quote(arg: str) -> str:
    return arg if re.fullmatch(r"[\w./:=-]+", arg) else "'" + arg.replace("'", "'\\''") + "'"
