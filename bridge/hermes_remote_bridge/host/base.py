"""The operating-system seam: defaults that work anywhere, overridden per platform."""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Protocol


class Handle(Protocol):
    """Something started in the background that can be stopped (an mDNS announcement)."""

    def terminate(self) -> None: ...


class Host:
    #: "linux", "windows" or "macos"; used in messages and to pick a firewall implementation.
    name = "generic"
    #: Directory of the virtualenv's executables.
    venv_bin = "bin"
    exe_suffix = ""

    # ------------------------------------------------------------------ locations

    def config_dir(self) -> Path:
        return Path.home() / ".config" / "hermes-remote"

    def state_dir(self) -> Path:
        return Path.home() / ".local" / "state" / "hermes-remote"

    def default_hermes_home(self) -> Path:
        return Path.home() / ".hermes"

    def hermes_home(self) -> Path:
        """``$HERMES_HOME`` when set (Hermes honours it on every platform), else the default."""
        override = os.environ.get("HERMES_HOME")
        return Path(override).expanduser() if override else self.default_hermes_home()

    def hermes_root(self) -> Path:
        return self.hermes_home() / "hermes-agent"

    def hermes_python(self) -> Path:
        return self.hermes_root() / "venv" / self.venv_bin / f"python{self.exe_suffix}"

    def hermes_bin(self) -> Path:
        """The Hermes CLI: the venv's entry point, else whatever ``hermes`` is on PATH."""
        own = self.hermes_root() / "venv" / self.venv_bin / f"hermes{self.exe_suffix}"
        if own.exists():
            return own
        found = shutil.which("hermes")
        return Path(found) if found else own

    # ------------------------------------------------------------------ networking

    def interface_ips(self) -> dict[str, list[str]]:
        """Every interface's IPv4 addresses, keyed by interface name."""
        try:
            import socket

            import psutil
        except ImportError:
            return {}
        out: dict[str, list[str]] = {}
        for name, addrs in psutil.net_if_addrs().items():
            for a in addrs:
                if a.family == socket.AF_INET and a.address not in out.setdefault(name, []):
                    out[name].append(a.address)
        return {k: v for k, v in out.items() if v}

    def default_route(self) -> tuple[str, str] | None:
        """(interface, gateway IPv4) of the default route with the lowest metric."""
        return None

    def default_route_interfaces(self) -> set[str]:
        """Names of every interface that carries a default route."""
        route = self.default_route()
        return {route[0]} if route else set()

    def gateway_mac(self, gateway: str) -> str | None:
        """The router's MAC address (lowercase, colon separated), once the PC has talked to it."""
        return None

    def network_profile(self, interface: str) -> tuple[str, str] | None:
        """(stable profile id, display name) the OS uses for this network, e.g. the Wi-Fi SSID."""
        return None

    # ------------------------------------------------------------------ tailscale

    def tailscale_socket(self) -> str | None:
        """Path of the tailscaled LocalAPI unix socket; None where only the CLI is reachable."""
        return None

    def tailscale_cli(self) -> str | None:
        return shutil.which("tailscale")

    # ------------------------------------------------------------------ integration

    def service_state(self, name: str) -> str:
        """"active" when the OS's service manager reports ``name`` running; "unknown" otherwise."""
        return "unknown"

    def advertise_mdns(self, port: int, pin: str | None, lan_ips: list[str]) -> Handle | None:
        """Announce the bridge on the local network over mDNS (python-zeroconf); None when it is
        not installed. Linux overrides this with Avahi."""
        try:
            import socket

            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            return None
        try:
            zc = Zeroconf()
            info = ServiceInfo(
                "_hermesremote._tcp.local.", f"Hermes Remote ({socket.gethostname()})._hermesremote._tcp.local.",
                addresses=[socket.inet_aton(ip) for ip in lan_ips], port=port,
                properties={"pin": (pin or "")[:12], "ip": ",".join(lan_ips), "v": "2"})
            zc.register_service(info)
        except Exception:  # noqa: BLE001 - discovery is a convenience, never fatal
            return None

        class _Announcement:
            def terminate(self) -> None:
                zc.unregister_service(info)
                zc.close()

        return _Announcement()

    def prompt_trust(self, network_name: str) -> str | None:
        """Ask the user (blocking) whether to trust a network: "trust", "no" or None (no answer)."""
        return None

    # ------------------------------------------------------------------ interactive use

    def launched_by_double_click(self) -> bool:
        """True when this process owns its console window, i.e. it will vanish on exit."""
        return False

    def qr_as_image(self) -> bool:
        """True where a QR drawn with block characters is unreliable (the Windows console font
        and code page), so the pairing code should open as a picture instead."""
        return False

    def open_file(self, path: Path) -> bool:
        """Open ``path`` with the OS's default viewer; False when that isn't possible."""
        return False

    # ------------------------------------------------------------------ helpers

    @staticmethod
    def run(cmd: list[str], timeout: float = 5.0) -> subprocess.CompletedProcess:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
