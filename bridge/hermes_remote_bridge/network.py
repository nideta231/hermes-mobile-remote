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
import threading
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


# Caching the computed network identity. Each answer costs three PowerShell round trips on
# Windows - Get-NetRoute (~0.8 s), Get-NetNeighbor (~1.1 s), Get-NetConnectionProfile (~0.8 s) -
# because each cmdlet needs its own powershell.exe. /v1/me asks for the current network twice per
# request (once for `network`, once through serving_lan_ips()), and the app polls that endpoint
# every few seconds, so it was queueing behind multi-second answers and the phone read that as
# "connecting". Measured: /v1/me took 4.5-4.9 s per call.
#
# Two rules make the cache safe rather than merely fast:
#
#  1. Time alone is not an acceptable key. Identity is (connection profile, router MAC) and the
#     whole point of the MAC is that a hotspot copying an SSID does not inherit trust, so a
#     swapped router must not keep the old identity. `fresh=True` exists for exactly that, and
#     test_host.py / test_app.py pin the behaviour.
#  2. The key must be cheap. An earlier version keyed on the default route, which is itself a
#     PowerShell call - the cache then cost as much as the value it avoided. interface_ips() is
#     psutil and takes ~12 ms, so it is the only input used to invalidate.
#
# The TTL is short because the bridge's own listen-set watcher re-evaluates every 5 s anyway, and
# a phone is what notices a network change first.
_CACHE_TTL = 1.0
_cached: tuple[str, float, Network | None] = ("", -1e9, None)
_cache_lock = threading.Lock()


def _fingerprint() -> str:
    """Cheap identity of the inputs: psutil only, no subprocess, safe on every call."""
    return repr(sorted((name, tuple(sorted(ips)))
                       for name, ips in host().interface_ips().items()))


def current_network(*, fresh: bool = False) -> Network | None:
    """The private network the default route goes through, or None (offline / not private).

    `fresh=True` bypasses the cache entirely, for callers that must not act on a reused answer.
    """
    global _cached
    if fresh:
        return _compute_network()
    key = _fingerprint()
    with _cache_lock:
        stamp_key, stamp, value = _cached
        if stamp_key == key and time.monotonic() - stamp < _CACHE_TTL:
            return value
    value = _compute_network()
    with _cache_lock:
        _cached = (key, time.monotonic(), value)
    return value


def _compute_network() -> Network | None:
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
    if net is None or not trust.is_trusted(net):
        return []
    # net.lan_ips is already filtered to the default-route interface by _compute_network, and to
    # private addresses, so it is exactly the set to serve. The previous version asked the OS for
    # the default-route addresses again (_default_route_ips -> Get-NetRoute, ~0.8 s on Windows)
    # and filtered by it, which was redundant work on every /v1/me and every watcher tick.
    return list(net.lan_ips)


def network_info(trust: TrustStore) -> dict | None:
    net = current_network()
    if net is None:
        return None
    return {**{k: v for k, v in asdict(net).items() if k != "lan_ips"}, "trusted": trust.is_trusted(net)}
