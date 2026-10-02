"""Linux: /proc, NetworkManager, systemd, Avahi, Tailscale's unix socket."""
from __future__ import annotations

import os
import shutil
import socket
import subprocess
from pathlib import Path

from .base import Handle, Host

TAILSCALE_SOCKET = "/run/tailscale/tailscaled.sock"


def _xdg(var: str, fallback: str) -> Path:
    return Path(os.environ.get(var) or Path.home() / fallback)


class LinuxHost(Host):
    name = "linux"

    def config_dir(self) -> Path:
        return _xdg("XDG_CONFIG_HOME", ".config") / "hermes-remote"

    def state_dir(self) -> Path:
        return _xdg("XDG_STATE_HOME", ".local/state") / "hermes-remote"

    def interface_ips(self) -> dict[str, list[str]]:
        """Via SIOCGIFCONF, so the bridge needs no extra dependency on Linux."""
        import ctypes
        import struct

        try:
            import fcntl
        except ImportError:
            return super().interface_ips()
        size = 8192
        names = bytearray(size)
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                request = struct.pack("iL", size, ctypes.addressof(ctypes.c_char.from_buffer(names)))
                response = fcntl.ioctl(sock.fileno(), 0x8912, request)  # SIOCGIFCONF
            finally:
                sock.close()
        except (OSError, AttributeError, ValueError):
            return {}
        out: dict[str, list[str]] = {}
        step = 40  # struct ifreq on 64-bit Linux: 16-byte name + 24-byte sockaddr
        for off in range(0, max(0, struct.unpack("iL", response)[0]) - step + 1, step):
            entry = names[off:off + step]
            name = bytes(entry[:16]).split(b"\x00")[0].decode(errors="replace")
            try:
                packed = socket.inet_ntoa(bytes(entry[20:24]))
            except OSError:
                continue
            if name and packed not in out.setdefault(name, []):
                out[name].append(packed)
        return out

    def default_route(self) -> tuple[str, str] | None:
        best: tuple[int, str, str] | None = None
        try:
            for line in Path("/proc/net/route").read_text().splitlines()[1:]:
                f = line.split()
                if len(f) < 7 or f[1] != "00000000":
                    continue
                gw = ".".join(str(b) for b in bytes.fromhex(f[2])[::-1])
                metric = int(f[6])
                if best is None or metric < best[0]:
                    best = (metric, f[0], gw)
        except (OSError, ValueError):
            return None
        return (best[1], best[2]) if best else None

    def default_route_interfaces(self) -> set[str]:
        out: set[str] = set()
        try:
            for line in Path("/proc/net/route").read_text().splitlines()[1:]:
                fields = line.split()
                if len(fields) > 2 and fields[1] == "00000000":  # destination == default
                    out.add(fields[0])
        except OSError:
            return set()
        return out

    def gateway_mac(self, gateway: str) -> str | None:
        try:
            for line in Path("/proc/net/arp").read_text().splitlines()[1:]:
                f = line.split()
                if len(f) >= 4 and f[0] == gateway and f[3] != "00:00:00:00:00:00":
                    return f[3].lower()
        except OSError:
            pass
        return None

    def network_profile(self, interface: str) -> tuple[str, str] | None:
        """(profile UUID, profile name) NetworkManager uses on this interface."""
        if not shutil.which("nmcli"):
            return None
        try:
            out = self.run(["nmcli", "-t", "-f", "GENERAL.CONNECTION,GENERAL.CON-UUID",
                            "device", "show", interface]).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        fields = dict(line.split(":", 1) for line in out.splitlines() if ":" in line)
        uuid, name = fields.get("GENERAL.CON-UUID", ""), fields.get("GENERAL.CONNECTION", "")
        return (uuid, name) if uuid else None

    def tailscale_socket(self) -> str | None:
        return TAILSCALE_SOCKET

    def service_state(self, name: str) -> str:
        if not shutil.which("systemctl"):
            return "unknown"
        try:
            return self.run(["systemctl", "--user", "is-active", name]).stdout.strip() or "unknown"
        except (OSError, subprocess.SubprocessError):
            return "unknown"

    def advertise_mdns(self, port: int, pin: str | None, lan_ips: list[str]) -> Handle | None:
        """Avahi announces on every interface, Docker bridges included, so ``ip=`` lists the
        addresses we actually serve and the app does not follow an unreachable 172.17.x one."""
        if not shutil.which("avahi-publish-service"):
            return None
        return subprocess.Popen(
            ["avahi-publish-service", f"Hermes Remote ({socket.gethostname()})", "_hermesremote._tcp",
             str(port), f"pin={(pin or '')[:12]}", f"ip={','.join(lan_ips)}", "v=2"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def prompt_trust(self, network_name: str) -> str | None:
        if not shutil.which("notify-send"):
            return None
        try:
            out = subprocess.run(
                ["notify-send", "--app-name=Hermes Remote", "--icon=network-wireless", "--wait",
                 "--action=trust=Trust this network", "--action=no=Not now",
                 f"New network: {network_name}",
                 "Let your phone connect to Hermes over this Wi-Fi without Tailscale? "
                 "Only for networks you control, like home or office."],
                capture_output=True, text=True, timeout=600).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None
        return out or None


__all__ = ["LinuxHost", "TAILSCALE_SOCKET"]
