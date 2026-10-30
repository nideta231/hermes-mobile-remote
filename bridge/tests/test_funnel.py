"""Tailscale Funnel: internet requests are told apart from local ones and get the stricter rules."""
import base64

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from hermes_remote_bridge import funnel as fm
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

from test_app import FakeLocator, FakeTailnet, fake_http

PHONE = "100.64.0.10"
FUNNEL = {"Tailscale-Funnel-Request": "?1", "X-Forwarded-For": "203.0.113.9", "X-Forwarded-Host": "pc.tail0000.ts.net"}


def phone_key():
    priv = ec.generate_private_key(ec.SECP256R1())
    pub = base64.b64encode(priv.public_key().public_bytes(serialization.Encoding.DER,
                                                         serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    return priv, pub


def sign(priv, msg: bytes) -> str:
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


@pytest.fixture
def setup(tmp_path):
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log", funnel_file=tmp_path / "funnel.json",
                 local_secret_file=tmp_path / "local-secret", metrics_token_file=tmp_path / "metrics.token",
                 blueprints_dir=tmp_path / "bp", monitor=False, notes_dir=tmp_path / "notes")
    store = DeviceStore(cfg.devices_file)
    dev, token = store.pair("phone")
    other, other_token = store.pair("tablet")
    http = fake_http({("GET", "/api/sessions"): {"sessions": [], "total": 0, "limit": 40, "offset": 0}}, [])
    app = create_app(cfg, locator=FakeLocator(), http=http, tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    return cfg, app, dev, token, other_token


def h(token, **extra):
    return {"Authorization": f"Bearer {token}", **extra}


def test_proxied_loopback_is_not_local():
    assert fm.proxied(True, {}) is None
    assert fm.proxied(True, {"tailscale-funnel-request": "?1"}) == "funnel"
    assert fm.proxied(True, {"x-forwarded-host": "pc.ts.net"}) == "serve"
    assert fm.proxied(True, {"tailscale-user-login": "me@example.com"}) == "serve"
    assert fm.proxied(False, {"tailscale-funnel-request": "?1"}) is None  # only tailscaled on loopback sets it
    assert fm.route_class("/v1/ws") == "open" and fm.route_class("/v1/blueprints/p1/ops") == "open"
    assert fm.route_class("/v1/terminal") == "unlock" and fm.route_class("/v1/admin/doctor") == "unlock"
    assert fm.route_class("/v1/notes") == "unlock"  # deny by default
    assert fm.route_class("/v1/local/pair") == "never" and fm.route_class("/v1/funnel/enroll") == "never"


def test_funnel_needs_token_and_the_phones_funnel_key(setup):
    cfg, app, dev, token, other_token = setup
    lan = TestClient(app, client=(PHONE, 50000))
    net = TestClient(app, client=("127.0.0.1", 50000))
    # not enrolled yet: refused over the internet, fine over the tailnet
    assert net.get("/v1/me", headers=h(token, **FUNNEL)).json()["error"]["code"] == "funnel_not_allowed"
    assert lan.get("/v1/me", headers=h(token)).status_code == 200
    # enrolling is LAN/tailnet only
    priv, pub = phone_key()
    assert net.post("/v1/funnel/enroll", json={"public_key": pub}, headers=h(token, **FUNNEL)).json()["error"]["code"] == "not_over_funnel"
    key = lan.post("/v1/funnel/enroll", json={"public_key": pub}, headers=h(token)).json()["key"]
    me = net.get("/v1/me", headers=h(token, **FUNNEL, **{"X-Funnel-Key": key})).json()
    assert me["via"] == "funnel" and me["funnel"]["allowed"] is True
    # the key belongs to one phone: another phone's token with it is refused
    assert net.get("/v1/me", headers=h(other_token, **FUNNEL, **{"X-Funnel-Key": key})).status_code == 403
    assert net.get("/v1/me", headers=h(token, **FUNNEL, **{"X-Funnel-Key": "hrf_wrong"})).status_code == 403
    # a genuinely local client is unchanged
    assert net.get("/v1/me", headers=h(token)).json()["peer_node"] == "local"


def test_terminal_and_admin_need_an_unlock_ticket(setup):
    cfg, app, dev, token, _ = setup
    lan = TestClient(app, client=(PHONE, 50000))
    net = TestClient(app, client=("127.0.0.1", 50000))
    priv, pub = phone_key()
    key = lan.post("/v1/funnel/enroll", json={"public_key": pub}, headers=h(token)).json()["key"]
    hdr = h(token, **FUNNEL, **{"X-Funnel-Key": key})
    assert net.get("/v1/admin/devices", headers=hdr).json()["error"]["code"] == "funnel_locked"
    nonce = net.get("/v1/funnel/challenge", headers=hdr).json()["nonce"]
    # a signature over the wrong message (or by another key) buys nothing, and burns the challenge
    other, _ = phone_key()
    assert net.post("/v1/funnel/unlock", json={"nonce": nonce, "signature": sign(other, fm.unlock_message(nonce, dev.id))},
                    headers=hdr).status_code == 403
    assert net.post("/v1/funnel/unlock", json={"nonce": nonce, "signature": sign(priv, fm.unlock_message(nonce, dev.id))},
                    headers=hdr).status_code == 403  # one use
    nonce = net.get("/v1/funnel/challenge", headers=hdr).json()["nonce"]
    ticket = net.post("/v1/funnel/unlock", json={"nonce": nonce, "signature": sign(priv, fm.unlock_message(nonce, dev.id))},
                      headers=hdr).json()["ticket"]
    assert net.get("/v1/admin/devices", headers={**hdr, "X-Funnel-Unlock": ticket}).status_code == 200
    # Funnel can't be switched on from the internet, and the phone's own opt-in can't be redone there
    assert net.post("/v1/admin/funnel", json={"on": True}, headers={**hdr, "X-Funnel-Unlock": ticket}).json()["error"]["code"] == "over_funnel"


def test_tickets_expire_and_belong_to_one_phone():
    now = [0.0]
    t = fm.Tickets(clock=lambda: now[0])
    n = t.challenge("d1")
    assert t.redeem("d2", n, True) is None  # another phone's challenge
    n = t.challenge("d1")
    ticket = t.redeem("d1", n, True)
    assert t.valid("d1", ticket) and not t.valid("d2", ticket)
    now[0] += fm.TICKET_SECONDS + 1
    assert not t.valid("d1", ticket)
    n = t.challenge("d1")
    now[0] += fm.CHALLENGE_SECONDS + 1
    assert t.redeem("d1", n, True) is None


def test_bad_tokens_lock_the_internet_address_out(setup):
    cfg, app, dev, token, _ = setup
    net = TestClient(app, client=("127.0.0.1", 50000))
    for _ in range(fm.LOCK_FAILURES):
        assert net.get("/v1/me", headers=h("hrb_nope", **FUNNEL)).status_code == 401
    # now even the right token is refused from that address, but not from another one or locally
    assert net.get("/v1/me", headers=h(token, **FUNNEL)).json()["error"]["code"] == "locked_out"
    other = {**FUNNEL, "X-Forwarded-For": "198.51.100.7"}
    assert net.get("/v1/me", headers=h(token, **other)).json()["error"]["code"] == "funnel_not_allowed"
    assert net.get("/v1/me", headers=h(token)).status_code == 200


def test_serve_from_the_tailnet_needs_an_allowed_login(setup):
    cfg, app, dev, token, _ = setup
    net = TestClient(app, client=("127.0.0.1", 50000))
    assert net.get("/v1/me", headers=h(token, **{"Tailscale-User-Login": "stranger@example.com", "X-Forwarded-Host": "x"})).status_code == 403
    assert net.get("/v1/me", headers=h(token, **{"Tailscale-User-Login": "me@example.com", "X-Forwarded-Host": "x"})).status_code == 200
    assert net.get("/v1/me", headers=h(token, **{"X-Forwarded-Host": "x"})).status_code == 403  # proxied, no identity


def test_funnel_off_in_config_refuses_everything(setup, tmp_path):
    cfg, app, dev, token, _ = setup
    cfg.funnel = False
    net = TestClient(app, client=("127.0.0.1", 50000))
    assert net.get("/v1/me", headers=h(token, **FUNNEL)).json()["error"]["code"] == "funnel_off"


def test_vault_and_yolo_are_refused_over_funnel():
    from hermes_remote_bridge.relay import inspect_frame
    vault = '{"jsonrpc":"2.0","id":1,"method":"vault.list","params":{}}'
    yolo = '{"jsonrpc":"2.0","id":2,"method":"config.set","params":{"key":"yolo","value":true}}'
    assert inspect_frame(vault, 10_000).forward
    assert inspect_frame(vault, 10_000, (), fm.RESTRICTED_METHODS).forward is None
    assert inspect_frame(yolo, 10_000, (), fm.RESTRICTED_METHODS, fm.RESTRICTED_CONFIG_KEYS).forward is None
    chat = '{"jsonrpc":"2.0","id":3,"method":"prompt.submit","params":{"session_id":"s","text":"hi"}}'
    assert inspect_frame(chat, 10_000, (), fm.RESTRICTED_METHODS, fm.RESTRICTED_CONFIG_KEYS).forward


def test_enroll_refuses_a_key_that_is_not_p256(setup):
    cfg, app, dev, token, _ = setup
    lan = TestClient(app, client=(PHONE, 50000))
    rsa_like = base64.b64encode(b"x" * 80).decode()
    assert lan.post("/v1/funnel/enroll", json={"public_key": rsa_like}, headers=h(token)).json()["error"]["code"] == "bad_key"


def test_serve_config_add_and_remove_keep_other_entries():
    other = {"TCP": {"8443": {"HTTPS": True}}, "Web": {"pc.ts.net:8443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3000"}}}}}
    cfg = fm.with_funnel(other, "pc.ts.net.", 443, 8651)
    assert cfg["AllowFunnel"] == {"pc.ts.net:443": True}
    assert cfg["Web"]["pc.ts.net:443"]["Handlers"]["/"]["Proxy"] == "http://127.0.0.1:8651"
    assert fm.funnel_url(cfg, 8651) == "https://pc.ts.net"
    assert fm.funnel_url(fm.with_funnel({}, "pc.ts.net", 10000, 8651), 8651) == "https://pc.ts.net:10000"
    with pytest.raises(ValueError):
        fm.with_funnel(other, "pc.ts.net", 8443, 8651)  # the port serves something else
    with pytest.raises(ValueError):
        fm.with_funnel({}, "pc.ts.net", 8080, 8651)  # Funnel can't serve it
    back = fm.without_funnel(cfg, 8651)
    assert back == other and fm.funnel_url(back, 8651) is None


def test_why_unavailable_says_what_to_turn_on():
    base = {"BackendState": "Running", "CertDomains": ["pc.ts.net"], "Self": {"CapMap": {"funnel": None}}}
    assert fm.why_unavailable(base) is None
    assert "HTTPS Certificates" in fm.why_unavailable({**base, "CertDomains": None})
    assert "funnel" in fm.why_unavailable({**base, "Self": {"CapMap": {}}})
    assert "not running" in fm.why_unavailable({**base, "BackendState": "Stopped"})
