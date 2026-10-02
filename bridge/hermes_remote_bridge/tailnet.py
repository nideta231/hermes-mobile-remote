"""Tailscale access: the LocalAPI unix socket where there is one, the ``tailscale`` CLI elsewhere.

Both return the same JSON (``tailscale status --json`` is the LocalAPI status document), so the
rest of the bridge does not care which one answered. Neither needs root or operator rights.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import socket
import subprocess
import time

import httpx

from .host import host

# None on Windows and macOS, where tailscaled has no unix socket and the CLI is the way in.
SOCKET = host().tailscale_socket()
_CGNAT = ipaddress.ip_network("100.64.0.0/10")
_TS_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def is_tailnet_ip(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip in (_CGNAT if ip.version == 4 else _TS_V6)


def is_loopback(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback


# RFC1918 / link-local / unique-local. A tailnet address is CGNAT 100.64/10, which is NOT
# private, so is_private_lan_ip keeps the two networks strictly disjoint.
_LAN_NETS = (ipaddress.ip_network("10.0.0.0/8"),
             ipaddress.ip_network("172.16.0.0/12"),
             ipaddress.ip_network("192.168.0.0/16"),
             ipaddress.ip_network("169.254.0.0/16"),
             ipaddress.ip_network("fc00::/7"))


def is_private_lan_ip(addr: str) -> bool:
    """True for a routable-on-this-LAN address (never loopback, never tailnet)."""
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    if ip.is_loopback or is_tailnet_ip(addr):
        return False
    return any(ip in net for net in _LAN_NETS if net.version == ip.version)


def local_lan_ips() -> list[str]:
    """Private addresses on interfaces that actually carry a default route.

    Docker and other virtual bridges also hold RFC1918 addresses; serving on them is pointless
    (nothing but other containers can route there) and they leak into the address list the app
    offers, so keep only the addresses of default-route interfaces.
    """
    routable = _default_route_ips()
    found = [ip for ips in _interface_ips().values() for ip in ips]
    if routable and found:
        found = [ip for ip in found if ip in routable]
    if not found:
        # Fallback: the address the kernel would pick to reach the internet, which is the LAN
        # address on a normally routed host. getaddrinfo(gethostname()) is not enough — it
        # returns loopback whenever the hostname resolves through /etc/hosts.
        try:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            probe.connect(("192.0.2.1", 9))  # TEST-NET-1; UDP connect sends no packet
            found = [probe.getsockname()[0]]
            probe.close()
        except OSError:
            return []
    return [ip for ip in found if is_private_lan_ip(ip)]


def _default_route_ips() -> set[str]:
    """Addresses of interfaces that carry a default route (empty when the OS can't say)."""
    carrying = host().default_route_interfaces()
    return {ip for name, ips in _interface_ips().items() if name in carrying for ip in ips}


def _interface_ips() -> dict[str, list[str]]:
    """Every interface's IPv4 addresses, keyed by name."""
    return host().interface_ips()


def _cli_json(*args: str) -> dict:
    """Run ``tailscale <args>`` and parse its JSON output; raises when Tailscale isn't reachable."""
    exe = host().tailscale_cli()
    if not exe:
        raise RuntimeError("tailscale CLI not found")
    result = subprocess.run([exe, *args], capture_output=True, text=True, timeout=8,
                            creationflags=0x08000000 if host().name == "windows" else 0)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"tailscale exited {result.returncode}")
    return json.loads(result.stdout)


def _self_ips_and_owner(status: dict) -> tuple[list[str], str | None]:
    ips = list(status["Self"]["TailscaleIPs"])
    owner = (status.get("User") or {}).get(str(status["Self"]["UserID"]), {}).get("LoginName")
    return ips, owner


def _whois_result(data: dict) -> dict:
    return {
        "login": data["UserProfile"]["LoginName"],
        "node": data["Node"].get("ComputedName") or data["Node"]["Name"],
        "os": (data["Node"].get("Hostinfo") or {}).get("OS"),
    }


class TailnetClient:
    def __init__(self, socket_path: str | None = SOCKET):
        self._client = (httpx.AsyncClient(transport=httpx.AsyncHTTPTransport(uds=socket_path),
                                          base_url="http://local-tailscaled.sock", timeout=5.0)
                        if socket_path else None)
        self._whois_cache: dict[str, tuple[float, dict | None]] = {}

    async def aclose(self) -> None:
        if self._client:
            await self._client.aclose()

    async def status(self) -> dict:
        if self._client is None:
            return await asyncio.to_thread(_cli_json, "status", "--json")
        resp = await self._client.get("/localapi/v0/status")
        resp.raise_for_status()
        return resp.json()

    async def status_self_ips(self) -> list[str]:
        return list((await self.status())["Self"].get("TailscaleIPs") or [])

    async def whois(self, addr: str) -> dict | None:
        """Return {"login", "node", "os"} for a tailnet peer, cached for 60s."""
        now = time.monotonic()
        cached = self._whois_cache.get(addr)
        if cached and now - cached[0] < 60:
            return cached[1]
        result = None
        try:
            if self._client is None:
                result = _whois_result(await asyncio.to_thread(_cli_json, "whois", "--json", addr))
            else:
                host_part = f"[{addr}]" if ":" in addr else addr
                resp = await self._client.get("/localapi/v0/whois", params={"addr": f"{host_part}:1"})
                if resp.status_code == 200:
                    result = _whois_result(resp.json())
        except (httpx.HTTPError, KeyError, ValueError, RuntimeError, OSError, subprocess.SubprocessError):
            result = None
        self._whois_cache[addr] = (now, result)
        return result


def sync_tailscale_ips(socket_path: str | None = SOCKET) -> tuple[list[str], str | None]:
    """Startup helper: this node's Tailscale IPs and the owner's login name."""
    if not socket_path:
        return _self_ips_and_owner(_cli_json("status", "--json"))
    with httpx.Client(transport=httpx.HTTPTransport(uds=socket_path),
                      base_url="http://local-tailscaled.sock", timeout=5.0) as client:
        return _self_ips_and_owner(client.get("/localapi/v0/status").raise_for_status().json())
