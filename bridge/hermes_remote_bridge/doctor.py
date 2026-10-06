"""`hermes-remote-bridge doctor`: explain why the phone can't connect, one check at a time."""
from __future__ import annotations

import sys
import urllib.request

from . import firewall
from .config import Config, read_hermes_api_key
from .devices import DeviceStore
from .host import host
from .network import TrustStore, current_network, serving_lan_ips
from .tailnet import sync_tailscale_ips

OK, WARN, FAIL = "ok", "warn", "fail"


def _marks() -> dict[str, str]:
    """Tick and cross where the console can print them; plain ASCII on legacy Windows code pages
    (cp1252 and friends), where printing them would crash the command."""
    fancy = {OK: "\u2714", WARN: "!", FAIL: "\u2718"}
    try:
        for mark in fancy.values():
            mark.encode(getattr(sys.stdout, "encoding", None) or "ascii")
    except (UnicodeEncodeError, LookupError):
        return {OK: "ok", WARN: "!!", FAIL: "XX"}
    return fancy


def _check_hermes(cfg: Config) -> tuple[str, str]:
    try:
        key = read_hermes_api_key(cfg.hermes_env)
    except (OSError, RuntimeError):
        return FAIL, (f"API_SERVER_KEY is not set in {cfg.hermes_env}. "
                      "Enable the Hermes API server (see README, step 1).")
    req = urllib.request.Request(f"{cfg.hermes_url}/health", headers={"Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            return OK, f"Hermes API server answers at {cfg.hermes_url} (HTTP {r.status})"
    except Exception as e:  # noqa: BLE001 - any failure means "not reachable", report it
        return FAIL, (f"Hermes API server not reachable at {cfg.hermes_url} ({e}). "
                      "Is the Hermes gateway running? Try: hermes gateway status")


def _check_service(cfg: Config) -> tuple[str, str]:
    state = host().service_state("hermes-remote-bridge")
    if state == "unknown":
        return WARN, "No service manager found; run `hermes-remote-bridge serve` yourself"
    if state == "active":
        return OK, "Bridge service is running"
    # On Windows the Scheduled Task is only the logon trigger: it starts the tray, and the tray
    # supervises the bridge. A task sitting in Ready therefore says nothing about whether the
    # bridge is up, so ask the thing that actually answers - on the port this run is configured
    # for, not a compiled-in one.
    if host().name == "windows":
        if _bridge_answers(cfg.port):
            return OK, "Bridge is running (the tray app supervises it; the task is the logon trigger)"
        return FAIL, (f"Bridge is not answering on port {cfg.port}. Start the tray, or: "
                      "Start-ScheduledTask -TaskName 'Hermes Mobile Remote' "
                      "(logs: %LOCALAPPDATA%\\hermes-remote\\state\\bridge.log)")
    return FAIL, (f"Bridge service is {state}. Start it: systemctl --user enable --now hermes-remote-bridge "
                  f"(logs: journalctl --user -u hermes-remote-bridge)")


def _bridge_answers(port: int) -> bool:
    """Is something serving the bridge port right now? An unauthenticated 401 counts: it means
    the bridge is up and refusing this request, which is exactly what it should do."""
    import socket

    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            return True
    except OSError:
        return False


def _check_network(cfg: Config) -> tuple[str, str]:
    if not cfg.lan:
        return WARN, "Local network is off (lan = false in config.toml); only Tailscale is served"
    trust = TrustStore(cfg.trust_file)
    net = current_network()
    if net is None:
        return WARN, "Can't identify the current network (no private default route, or the router has not been seen yet)"
    if not trust.is_trusted(net):
        return WARN, (f"Network '{net.name}' is not trusted, so the phone can't connect over Wi-Fi here. "
                      "If this is your home or office: hermes-remote-bridge trust")
    ips = serving_lan_ips(cfg.lan, trust)
    return OK, f"Trusted network '{net.name}', serving https://{ips[0] if ips else '?'}:{cfg.port}"


def _check_firewall(cfg: Config) -> tuple[str, str]:
    s = firewall.state(cfg.port)
    if s.kind == "none" or not s.active:
        return OK, "No active firewall blocks the bridge port"
    if s.port_open:
        return OK, f"{s.kind}: port {cfg.port} is allowed from private networks"
    if s.port_open is None:
        return WARN, f"{s.kind} is active; can't read its rules without root. Run: hermes-remote-bridge firewall"
    return FAIL, f"{s.kind} blocks port {cfg.port} from the local network. Fix: hermes-remote-bridge firewall"


def _check_tailscale() -> tuple[str, str]:
    try:
        ips, owner = sync_tailscale_ips()
    except Exception:  # noqa: BLE001 - Tailscale is optional
        return WARN, "Tailscale not running (optional; needed only away from trusted Wi-Fi)"
    v4 = [ip for ip in ips if "." in ip]
    return OK, f"Tailscale up ({', '.join(v4) or 'no IPv4'}; owner {owner or 'unknown'})"


def _check_devices(cfg: Config) -> tuple[str, str]:
    active = [d for d in DeviceStore(cfg.devices_file).list() if not d.revoked_at]
    if not active:
        return WARN, "No paired devices yet. Pair one: hermes-remote-bridge pair phone"
    return OK, f"{len(active)} paired device(s): {', '.join(d.name for d in active)}"


CHECK_ORDER = ("hermes", "service", "network", "firewall", "tailscale", "devices")


def collect(cfg: Config) -> list[dict]:
    """Every check as structured data, in display order.

    The tray renders this instead of scraping the human output, so `id` is a stable handle and
    `level` is the severity; `message` is the same sentence `run` prints, kept in one place so
    the two can never disagree.
    """
    found = {"hermes": _check_hermes(cfg), "service": _check_service(cfg), "network": _check_network(cfg),
             "firewall": _check_firewall(cfg), "tailscale": _check_tailscale(), "devices": _check_devices(cfg)}
    return [{"id": name, "level": found[name][0], "message": found[name][1]} for name in CHECK_ORDER]


def run(cfg: Config) -> int:
    checks = collect(cfg)
    marks = _marks()
    for check in checks:
        print(f" {marks[check['level']]} {check['message']}")
    failed = sum(c["level"] == FAIL for c in checks)
    print("\nAll good." if not failed else f"\n{failed} problem(s) found.")
    return 1 if failed else 0
