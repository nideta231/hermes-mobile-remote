"""Which local network the PC is on, and whether the user trusts it.

The bridge only serves the local network on networks the user marked as trusted (home, office).
Anywhere else (a cafe, a hotel) it stays on loopback + Tailscale, because a shared Wi-Fi is
exactly where an unknown device may sit on the address the phone remembers.

A network is identified by the OS's connection profile *and* the gateway's MAC
address. The profile alone is an SSID + password; the gateway MAC ties trust to the physical
router, so a hotspot that copies the SSID does not inherit it.
"""
from __future__ import annotations

import hashlib
import json
import os
import time
from dataclasses import asdict, dataclass
from pathlib import Path

from .host import host
from .tailnet import _default_route_ips, _interface_ips, is_private_lan_ip


@dataclass(frozen=True)
class Network:
    id: str
    name: str
    interface: str
    lan_ips: tuple[str, ...]


def _default_route() -> tuple[str, str] | None:
    """(interface, gateway IP) of the IPv4 default route with the lowest metric."""
    return host().default_route()


def _gateway_mac(gateway: str) -> str | None:
    return host().gateway_mac(gateway)


def _network_profile(interface: str) -> tuple[str, str] | None:
    """(profile id, display name) the OS uses for this network: the NetworkManager connection on
    Linux, the connection profile (the SSID on Wi-Fi) on Windows."""
    return host().network_profile(interface)


def current_network() -> Network | None:
    """The private network the default route goes through, or None (offline / not private)."""
    route = _default_route()
    if route is None:
        return None
    interface, gateway = route
    lan_ips = tuple(ip for ip in _interface_ips().get(interface, []) if is_private_lan_ip(ip))
    if not lan_ips or not is_private_lan_ip(gateway):
        return None  # e.g. a public IP straight on the interface: never a "local network"
    mac = _gateway_mac(gateway)
    if mac is None:
        return None  # identity unknown yet; the watcher retries
    found = _network_profile(interface)
    profile, name = found if found else (interface, interface)
    net_id = hashlib.sha256(f"{profile}|{mac}".encode()).hexdigest()[:16]
    return Network(net_id, name or interface, interface, lan_ips)


class TrustStore:
    """Trusted network ids, persisted as JSON (mode 600)."""

    def __init__(self, path: Path):
        self.path = path

    def _read(self) -> dict:
        try:
            return json.loads(self.path.read_text())
        except (OSError, ValueError):
            return {"trusted": {}}

    def _write(self, data: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump(data, fh, indent=2)
        tmp.replace(self.path)

    def is_trusted(self, net: Network | None) -> bool:
        return net is not None and net.id in self._read().get("trusted", {})

    def trust(self, net: Network) -> None:
        data = self._read()
        data.setdefault("trusted", {})[net.id] = {"name": net.name, "since": round(time.time())}
        data.get("declined", {}).pop(net.id, None)
        self._write(data)

    def untrust(self, net_id: str) -> bool:
        data = self._read()
        removed = data.get("trusted", {}).pop(net_id, None) is not None
        if removed:
            self._write(data)
        return removed

    def list(self) -> dict[str, dict]:
        return dict(self._read().get("trusted", {}))

    def declined(self) -> set[str]:
        return set(self._read().get("declined", {}))

    def decline(self, net: Network) -> None:
        data = self._read()
        data.setdefault("declined", {})[net.id] = {"name": net.name, "since": round(time.time())}
        self._write(data)


def serving_lan_ips(enabled: bool, trust: TrustStore) -> list[str]:
    """LAN addresses to serve right now: only on a trusted network, only on its interface."""
    if not enabled:
        return []
    net = current_network()
    if not trust.is_trusted(net):
        return []
    routable = _default_route_ips()
    return [ip for ip in net.lan_ips if not routable or ip in routable]


def network_info(trust: TrustStore) -> dict | None:
    net = current_network()
    if net is None:
        return None
    return {**{k: v for k, v in asdict(net).items() if k != "lan_ips"}, "trusted": trust.is_trusted(net)}
