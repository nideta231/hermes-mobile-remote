import json
import os

import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config, read_hermes_api_key
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.tailnet import is_loopback, is_tailnet_ip


class FakeTailnet:
    def __init__(self, logins):
        self.logins = logins

    async def whois(self, addr):
        login = self.logins.get(addr)
        return {"login": login, "node": f"node-{addr}", "os": "android"} if login else None

    async def status_self_ips(self):
        return ["100.1.2.3"]

    async def status(self):
        return {"BackendState": "Running", "Self": {"TailscaleIPs": ["100.1.2.3"], "HostName": "pc", "DNSName": "pc.ts.net."}}

    async def aclose(self):
        pass


class TailscaleDown:
    """What TailnetClient looks like when the daemon is down or the CLI is absent."""

    async def whois(self, addr):
        return None

    async def status_self_ips(self):
        raise RuntimeError("tailscale not running")

    async def status(self):
        raise RuntimeError("tailscale not running")

    async def aclose(self):
        pass


class FakeHermes:
    def __init__(self, responses=None):
        self.calls = []
        self.responses = responses or {}

    async def health(self):
        return {"status": "ok", "version": "1.2.3", "gateway_state": "running",
                "readiness": {"status": "ok", "checks": {}}}

    async def request(self, method, path, **kw):
        self.calls.append((method, path, kw))
        payload = self.responses.get((method, path))
        if payload is None:
            payload = self.responses.get(path)
        class R:
            def json(self_inner):
                return payload if payload is not None else {"object": "list", "data": [], "path": path}
        return R()

    def sent_json(self, method, path):
        return [kw["json"] for m, p, kw in self.calls if m == method and p == path][-1]

    async def open_run_events(self, run_id):
        raise RuntimeError("not used")

    async def run_status(self, run_id):
        return None

    async def aclose(self):
        pass


@pytest.fixture
def env(tmp_path):
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    hermes = FakeHermes()
    tailnet = FakeTailnet({"100.64.0.10": "me@example.com", "100.99.0.1": "stranger@example.com"})
    app = create_app(cfg, hermes=hermes, tailnet=tailnet, devices=store, owner_login="me@example.com")
    return cfg, store, token, hermes, app


def client(app, ip):
    return TestClient(app, client=(ip, 50000))


def test_ip_classification():
    assert is_tailnet_ip("100.64.0.10") and is_tailnet_ip("fd7a:115c:a1e0::1")
    assert not is_tailnet_ip("192.168.1.5") and not is_tailnet_ip("100.128.0.1")
    assert is_loopback("127.0.0.1") and is_loopback("::1") and not is_loopback("100.64.0.10")


def test_lan_peer_rejected_even_with_valid_token(env):
    _, _, token, _, app = env
    r = client(app, "192.168.1.50").get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_foreign_tailnet_identity_rejected(env):
    _, _, token, _, app = env
    r = client(app, "100.99.0.1").get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_peer"


def test_token_required_and_revocation_is_live(env):
    _, store, token, _, app = env
    c = client(app, "100.64.0.10")
    assert c.get("/v1/me").status_code == 401
    assert c.get("/v1/me", headers={"Authorization": "Bearer hrb_wrong"}).status_code == 401
    ok = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert ok.status_code == 200 and ok.json()["device"]["name"] == "phone"
    # revoke through a separate store instance, as the CLI does
    DeviceStore(store.path).revoke("phone")
    assert c.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_device_file_stores_only_hash(env):
    cfg, _, token, _, _ = env
    text = cfg.devices_file.read_text()
    assert token not in text
    if os.name == "posix":  # Windows has no mode bits; the user-profile ACL applies
        assert oct(os.stat(cfg.devices_file).st_mode & 0o777) == "0o600"


def test_body_limit_and_validation(env):
    _, _, token, _, app = env
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    big = c.post("/v1/sessions", headers={**h, "content-type": "application/json"},
                 content=b'{"title":"' + b"x" * 1_100_000 + b'"}')
    assert big.status_code == 413
    bad = c.post("/v1/runs", headers=h, json={"session_id": "s1", "input": ""})
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "invalid_request"
    assert c.get("/v1/sessions/..%2Fetc", headers=h).status_code in (400, 404)


def test_session_delete_requires_confirmation(env):
    _, _, token, hermes, app = env
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    r = c.delete("/v1/sessions/api_1", headers=h)
    assert r.status_code == 428
    assert not any(m == "DELETE" for m, _, _ in hermes.calls)
    assert c.delete("/v1/sessions/api_1?confirm=api_1", headers=h).status_code == 200


def test_audit_log_has_no_tokens(env):
    cfg, _, token, _, app = env
    client(app, "100.64.0.10").get("/v1/sessions", headers={"Authorization": f"Bearer {token}"})
    log = cfg.audit_log.read_text()
    assert token not in log and '"device": "phone"' in log


def test_read_hermes_api_key_takes_last(tmp_path):
    p = tmp_path / ".env"
    p.write_text("API_SERVER_KEY=old\nOTHER=1\nexport API_SERVER_KEY='new'\n")
    assert read_hermes_api_key(p) == "new"


CATALOG = {
    # Real shape: `model` and `provider` are top-level alongside `providers`.
    "model": "m/one",
    "provider": "nous",
    "providers": [
        {"slug": "nous", "name": "Nous", "is_current": True, "authenticated": True, "source": "hermes",
         "models": ["m/one", "m/two"], "unavailable_models": ["m/two"], "featured_models": ["m/one"]},
        {"slug": "anthropic", "name": "Anthropic", "is_current": False, "authenticated": True, "source": "built-in",
         "models": ["claude-x", "claude-y"], "unavailable_models": [], "featured_models": []},
        {"slug": "openai-api", "name": "OpenAI", "is_current": False, "authenticated": False, "source": "canonical",
         "models": ["gpt-x"], "unavailable_models": [], "featured_models": []},
        {"slug": "empty", "name": "Empty", "is_current": False, "authenticated": True, "source": "built-in",
         "models": [], "unavailable_models": [], "featured_models": []},
    ],
}


def test_current_provider_survives_every_model_being_unavailable(tmp_path):
    """Hermes' own provider can report all its models unavailable while a private alias runs.

    The app must still be able to see (and keep using) the provider it is actually on.
    """
    raw = {"model": "stealth/space-bunny-alpha", "provider": "nous",
           "providers": [{"slug": "nous", "name": "Nous", "is_current": True, "authenticated": True,
                          "source": "hermes", "models": ["a/b", "c/d"],
                          "unavailable_models": ["a/b", "c/d"], "featured_models": ["a/b"]}]}
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, hermes=FakeHermes({("GET", "/api/model/options"): raw}),
                     tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}), devices=store,
                     owner_login="me@example.com")
    body = client(app, "100.64.0.10").get("/v1/models",
                                          headers={"Authorization": f"Bearer {token}"}).json()
    nous = [p for p in body["providers"] if p["slug"] == "nous"][0]
    assert body["current"] == {"model": "stealth/space-bunny-alpha", "provider": "nous"}
    assert nous["current"] and nous["models"], "the provider in use must still be offered"
    assert nous["featured"][0] == "stealth/space-bunny-alpha"


def test_model_catalog_filters_unavailable_and_unauthenticated(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    hermes = FakeHermes({("GET", "/api/model/options"): CATALOG})
    app = create_app(cfg, hermes=hermes, tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    r = client(app, "100.64.0.10").get("/v1/models", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    body = r.json()
    assert body["current"] == {"model": "m/one", "provider": "nous"}
    slugs = [p["slug"] for p in body["providers"]]
    assert slugs == ["nous", "anthropic"]  # unauthenticated and model-less providers dropped
    assert body["providers"][0]["models"] == ["m/one"]  # unavailable model removed
    assert body["providers"][0]["current"] is True


def test_run_forwards_model_override_only_when_given(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    hermes = FakeHermes({("POST", "/v1/runs"): {"run_id": "run_1", "status": "started"}})
    app = create_app(cfg, hermes=hermes, tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    c.post("/v1/runs", headers=h, json={"session_id": "s1", "input": "hi", "client_request_id": "req000001"})
    assert hermes.sent_json("POST", "/v1/runs") == {"input": "hi", "session_id": "s1"}
    c.post("/v1/runs", headers=h, json={"session_id": "s2", "input": "hi", "client_request_id": "req000002",
                                        "model": "claude-x", "provider": "anthropic"})
    assert hermes.sent_json("POST", "/v1/runs") == {"input": "hi", "session_id": "s2",
                                                    "model": "claude-x", "provider": "anthropic"}


def test_patch_session_passes_pin_and_rejects_empty(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    hermes = FakeHermes({("PATCH", "/api/sessions/s1"): {"session": {"id": "s1", "pinned": True}}})
    app = create_app(cfg, hermes=hermes, tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    r = c.patch("/v1/sessions/s1", headers=h, json={"pinned": False})
    assert r.status_code == 200 and hermes.sent_json("PATCH", "/api/sessions/s1") == {"pinned": False}
    assert c.patch("/v1/sessions/s1", headers=h, json={}).status_code == 400


def test_sync_returns_only_messages_after_the_cursor(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    rows = [{"id": str(i), "role": "user", "content": f"m{i}"} for i in range(5)]
    hermes = FakeHermes({
        ("GET", "/api/sessions/s1"): {"session": {"id": "s1", "message_count": 5, "pinned": True, "model": "m"}},
        ("GET", "/api/sessions/s1/messages"): {"data": rows[3:]},
    })
    app = create_app(cfg, hermes=hermes, tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    r = c.get("/v1/sessions/s1/sync?since=3", headers=h)
    assert r.status_code == 200
    body = r.json()
    assert [m["id"] for m in body["messages"]] == ["3", "4"]
    assert body["cursor"] == 5 and body["changed"] is True and body["pinned"] is True
    # at the head: nothing new, and no redundant message fetch
    before = len(hermes.calls)
    again = c.get("/v1/sessions/s1/sync?since=5", headers=h).json()
    assert again["messages"] == [] and again["changed"] is False
    assert len(hermes.calls) == before + 1  # only the session head, no messages call
    # first sync of a long session hands back a bounded tail
    c.get("/v1/sessions/s1/sync?limit=2", headers=h)
    tail_call = next(kw for m, p, kw in hermes.calls if p.endswith("/messages"))
    assert tail_call["params"]["limit"] == 2


def test_approval_mode_get_set_and_validation(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    state = {"mode": "smart"}
    argv_seen = []

    async def fake_cli(*argv):
        argv_seen.append(argv)
        if argv[:2] == ("config", "get"):
            return state["mode"] + "\n"
        if argv[:2] == ("config", "set"):
            state["mode"] = argv[3]
            return "ok\n"
        raise AssertionError(argv)

    app = create_app(cfg, hermes=FakeHermes(), tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com", hermes_cli=fake_cli)
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    assert c.get("/v1/settings/approvals", headers=h).json() == {"mode": "smart", "modes": ["manual", "smart", "off"]}
    r = c.put("/v1/settings/approvals", headers=h, json={"mode": "off"})
    assert r.status_code == 200 and r.json()["mode"] == "off" and state["mode"] == "off"
    assert ("config", "set", "approvals.mode", "off") in argv_seen
    # Arbitrary values never reach the CLI.
    n = len(argv_seen)
    assert c.put("/v1/settings/approvals", headers=h, json={"mode": "yolo; rm -rf"}).status_code == 400
    assert len(argv_seen) == n
    assert "approval_mode_changed" in (tmp_path / "a.log").read_text()
    # Unauthenticated callers can't touch it.
    assert c.put("/v1/settings/approvals", json={"mode": "manual"}).status_code == 401


def test_created_session_uses_a_desktop_visible_source(tmp_path):
    """The desktop keeps api_server sessions out of its Sessions sidebar (they read as
    Messaging), so a chat started on the phone must not be stamped api_server."""
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    hermes = FakeHermes({("POST", "/api/sessions"): {"session": {"id": "api_1"}}})
    app = create_app(cfg, hermes=hermes, tailnet=FakeTailnet({"100.64.0.10": "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = client(app, "100.64.0.10")
    h = {"Authorization": f"Bearer {token}"}
    assert c.post("/v1/sessions", headers=h, json={}).status_code == 201
    assert hermes.sent_json("POST", "/api/sessions") == {"source": "cli"}
    c.post("/v1/sessions", headers=h, json={"title": "from phone"})
    assert hermes.sent_json("POST", "/api/sessions") == {"source": "cli", "title": "from phone"}


def _lan_app(tmp_path, https=True, **cfg_kw):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log",
                 trust_file=tmp_path / "n.json", **cfg_kw)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, hermes=FakeHermes(), tailnet=FakeTailnet({}), devices=store, owner_login=None)
    base = "https://192.168.1.10:8650" if https else "http://192.168.1.10:8650"
    return TestClient(app, client=("192.168.1.50", 50000), base_url=base), token


def test_lan_is_refused_unless_enabled(tmp_path):
    c, token = _lan_app(tmp_path)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_lan_peer_is_accepted_on_device_token_over_tls(tmp_path):
    c, token = _lan_app(tmp_path, lan=True)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    body = r.json()
    assert body["via"] == "lan" and body["peer_node"] == "lan:192.168.1.50"
    assert c.get("/v1/me").status_code == 401
    assert c.get("/v1/me", headers={"Authorization": "Bearer hrb_wrong"}).status_code == 401


def test_lan_peer_over_plain_http_is_refused(tmp_path):
    # The token would cross the Wi-Fi in clear text; LAN is HTTPS-only.
    c, token = _lan_app(tmp_path, https=False, lan=True)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_public_address_is_refused_even_with_lan_on(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", lan=True)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, hermes=FakeHermes(), tailnet=FakeTailnet({}), devices=store, owner_login=None)
    c = client(app, "203.0.113.9")  # TEST-NET-3, globally routable
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_me_reports_both_networks(tmp_path, monkeypatch):
    from hermes_remote_bridge import app as app_mod
    monkeypatch.setattr(app_mod, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(app_mod, "network_info", lambda trust: {"id": "abc", "name": "Home", "trusted": True})
    c, token = _lan_app(tmp_path, lan=True)
    body = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).json()
    assert body["addresses"] == {"lan": ["192.168.1.10"], "tailnet": ["100.1.2.3"]}
    assert body["lan_scheme"] == "https" and body["network"]["trusted"] is True


def test_private_and_tailnet_ranges_are_disjoint():
    from hermes_remote_bridge.tailnet import is_private_lan_ip, is_tailnet_ip
    assert is_private_lan_ip("192.168.1.10") and is_private_lan_ip("10.1.2.3")
    assert not is_private_lan_ip("100.64.0.20")  # tailnet CGNAT is not "private LAN"
    assert not is_private_lan_ip("127.0.0.1") and not is_private_lan_ip("203.0.113.9")
    assert not is_private_lan_ip("garbage")
    assert is_tailnet_ip("100.64.0.20") and not is_tailnet_ip("192.168.1.10")


def test_pairing_code_puts_trusted_lan_first_and_tailscale_as_fallback(monkeypatch):
    from hermes_remote_bridge import cli
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", lambda: (["100.64.0.20", "fd7a::1"], "me@x"))
    assert cli._bridge_urls(Config(lan=True, port=8650)) == ["https://192.168.1.10:8650", "http://100.64.0.20:8650"]


def test_pairing_code_works_without_tailscale(monkeypatch):
    from hermes_remote_bridge import cli
    def down():
        raise OSError("tailscaled not running")
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", down)
    assert cli._bridge_urls(Config(lan=True, port=8650)) == ["https://192.168.1.10:8650"]


def test_untrusted_network_serves_no_lan(tmp_path, monkeypatch):
    from hermes_remote_bridge import network
    from hermes_remote_bridge.network import Network, TrustStore
    cafe = Network("cafe0001", "Cafe WiFi", "wlan0", ("10.0.0.7",))
    monkeypatch.setattr(network, "current_network", lambda: cafe)
    monkeypatch.setattr(network, "_default_route_ips", lambda: {"10.0.0.7"})
    trust = TrustStore(tmp_path / "n.json")
    assert network.serving_lan_ips(True, trust) == []
    trust.trust(cafe)
    assert network.serving_lan_ips(True, trust) == ["10.0.0.7"]
    assert network.serving_lan_ips(False, trust) == []  # lan off overrides trust
    if os.name == "posix":
        assert oct((tmp_path / "n.json").stat().st_mode & 0o777) == "0o600"
    trust.untrust("cafe0001")
    assert network.serving_lan_ips(True, trust) == []


def test_same_ssid_behind_a_different_router_is_a_different_network(monkeypatch):
    from hermes_remote_bridge import network
    monkeypatch.setattr(network, "_default_route", lambda: ("wlan0", "192.168.1.1"))
    monkeypatch.setattr(network, "_interface_ips", lambda: {"wlan0": ["192.168.1.10"]})
    monkeypatch.setattr(network, "_network_profile", lambda iface: ("uuid-home", "HomeWiFi"))
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: "aa:aa:aa:aa:aa:aa")
    # fresh=True: this is the router-swap case, where a cached identity would be a security bug.
    home = network.current_network(fresh=True)
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: "bb:bb:bb:bb:bb:bb")
    impostor = network.current_network(fresh=True)
    assert home.name == impostor.name == "HomeWiFi" and home.id != impostor.id
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: None)
    assert network.current_network(fresh=True) is None  # unknown router -> never trusted


def test_tls_identity_is_created_once_with_private_key(tmp_path):
    from hermes_remote_bridge.tls import cert_pin, ensure_identity
    cert, key = ensure_identity(tmp_path / "tls")
    pin = cert_pin(cert)
    assert len(pin) == 43
    if os.name == "posix":  # Windows has no mode bits; the profile ACL protects the key
        assert oct(key.stat().st_mode & 0o777) == "0o600"
    assert cert_pin(ensure_identity(tmp_path / "tls")[0]) == pin  # stable across restarts


def _app_with_tailnet(tmp_path, tailnet):
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, hermes=FakeHermes(), tailnet=tailnet, devices=store,
                     owner_login="me@example.com")
    return client(app, "127.0.0.1"), {"Authorization": f"Bearer {token}"}


def test_desktop_endpoint_degrades_when_tailscale_is_down(tmp_path):
    """Tailscale is optional: /v1/desktop must say what is missing, not fail as a 500."""
    c, auth = _app_with_tailnet(tmp_path, TailscaleDown())
    r = c.get("/v1/desktop", headers=auth)
    assert r.status_code == 503 and r.json()["error"]["code"] == "desktop_unavailable"
    # The rest of the bridge already treats the same condition as an unreachable component.
    assert c.get("/v1/status", headers=auth).json()["components"]["tailscale"]["status"] == "unreachable"


def test_desktop_endpoint_offers_the_tailnet_address(tmp_path):
    c, auth = _app_with_tailnet(tmp_path, FakeTailnet({}))
    body = c.get("/v1/desktop", headers=auth).json()
    assert body["protocol"] == "rdp" and body["host"] == "100.1.2.3"
    assert body["dns_name"] == "pc.ts.net" and body["port"] == 3389
    assert body["rdp_uri"].endswith(":3389")
