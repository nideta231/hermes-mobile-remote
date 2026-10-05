"""The phone polls /v1/me, and on Windows each answer used to cost ~4.5 s.

current_network() spawns three PowerShell processes (~2 s on Windows), and /v1/me asks for it
twice: once for `network`, and once through serving_lan_ips(). The app reads a slow answer as
"connecting", so this cache is a client-visible fix, not just an optimisation.

The cache key is the cheap fingerprint (default route + interface addresses), not elapsed time
alone, and fresh=True exists for the router-swap case where a reused identity would be a
security problem. test_host.py and test_app.py pin that behaviour.
"""
import time

from hermes_remote_bridge import network as network_mod
from hermes_remote_bridge.network import Network, current_network

ROUTE = ("Wi-Fi", "192.168.1.1")
IPS = {"Wi-Fi": ["192.168.1.10"]}
HOME = Network("abc", "HomeWiFi", "Wi-Fi", ("192.168.1.10",))


def _wire(monkeypatch, mac="aa:aa:aa:aa:aa:aa"):
    monkeypatch.setattr(network_mod, "_default_route", lambda: ROUTE)
    monkeypatch.setattr(network_mod, "_interface_ips", lambda: dict(IPS))
    monkeypatch.setattr(network_mod, "_network_profile", lambda i: ("{GUID}", "HomeWiFi"))
    monkeypatch.setattr(network_mod, "_gateway_mac", lambda g: mac)


def _counting(monkeypatch, value=HOME):
    """Replace the expensive computation with one that records how often it ran."""
    calls: list[int] = []

    def fake() -> Network | None:
        calls.append(1)
        return value

    monkeypatch.setattr(network_mod, "_compute_network", fake)
    network_mod._cached = ("", -1e9, None)
    return calls


def test_repeated_calls_do_not_recompute(monkeypatch):
    """The second call inside the TTL must be free: that is the whole point."""
    _wire(monkeypatch)
    calls = _counting(monkeypatch)
    first, second, third = current_network(), current_network(), current_network()
    assert first is second is third
    assert len(calls) == 1, f"expected one computation in the TTL, got {len(calls)}"


def test_fresh_bypasses_the_cache(monkeypatch):
    """The trust prompt and the router-swap tests must be able to force a new answer."""
    _wire(monkeypatch)
    calls = _counting(monkeypatch)
    current_network()
    current_network(fresh=True)
    assert len(calls) == 2


def test_the_cache_expires(monkeypatch):
    """A reused answer must not outlive the TTL, or a network change would go unnoticed."""
    _wire(monkeypatch)
    monkeypatch.setattr(network_mod, "_CACHE_TTL", 0.01)
    calls = _counting(monkeypatch)
    current_network()
    time.sleep(0.05)
    current_network()
    assert len(calls) == 2


def test_a_changed_interface_address_recomputes_at_once(monkeypatch):
    """The fingerprint is the interface addresses, so an address change must invalidate at once."""
    _wire(monkeypatch)
    calls = _counting(monkeypatch)
    current_network()
    # The PC moved to a different network: the address on the interface changed.
    monkeypatch.setattr(network_mod, "_interface_ips", lambda: {"Wi-Fi": ["10.0.0.55"]})
    monkeypatch.setattr(network_mod, "host", lambda: type("H", (), {
        "interface_ips": staticmethod(lambda: {"Wi-Fi": ["10.0.0.55"]})})())
    current_network()
    assert len(calls) == 2, "a changed interface address must invalidate the cache immediately"


def test_the_fingerprint_costs_no_subprocess(monkeypatch):
    """The earlier version keyed on the default route, which is itself a PowerShell call, so the
    cache cost as much as the value it avoided. The key must be psutil-only."""
    _wire(monkeypatch)
    route_calls = []

    def counting_route():
        route_calls.append(1)
        return ROUTE

    monkeypatch.setattr(network_mod, "_default_route", counting_route)
    network_mod._cached = ("", -1e9, None)
    for _ in range(5):
        network_mod._fingerprint()
    assert not route_calls, "the fingerprint must not call the default route"


def test_offline_is_cached_too(monkeypatch):
    """None is a real answer (no private network); recomputing it every call is the same cost."""
    _wire(monkeypatch)
    calls = _counting(monkeypatch, value=None)
    assert current_network() is None
    assert current_network() is None
    assert len(calls) == 1


def test_fresh_works_when_the_cheap_probes_report_nothing(monkeypatch):
    """fresh=True must not depend on the fingerprint, which needs the cheap probes to work."""
    _wire(monkeypatch)
    monkeypatch.setattr(network_mod, "_default_route", lambda: None)
    monkeypatch.setattr(network_mod, "_interface_ips", lambda: {})
    network_mod._cached = ("", -1e9, None)
    assert current_network(fresh=True) is None
