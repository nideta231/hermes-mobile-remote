"""Tailscale Funnel: the bridge reachable from the public internet, for phones that opted in.

Funnel (and Tailscale Serve) proxy requests to the bridge's loopback listener, so to the network
guard they look local. This module tells them apart and holds the extra rules for them:

- A Funnel request carries ``Tailscale-Funnel-Request: ?1`` (tailscaled strips any copy a client
  sent and sets it itself). A Serve request from the tailnet carries ``Tailscale-User-Login``.
  Either way tailscaled adds ``X-Forwarded-Host``; local tools (tray, CLI) never do.
- Over Funnel a phone needs its device token AND its own Funnel key (a second secret given only
  when the phone opted in from the LAN/tailnet). Chat, files and boards are open; everything else
  (terminal, PC bridge admin, monitor, notes, settings...) needs an unlock ticket: the phone signs
  a fresh challenge with a hardware key that only works after fingerprint/PIN, and gets 5 minutes.
- Wrong tokens or keys from one internet address lock that address out for a while.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from pathlib import Path

FUNNEL_HEADER = "tailscale-funnel-request"
LOGIN_HEADER = "tailscale-user-login"
PROXY_HEADER = "x-forwarded-host"
KEY_HEADER = "x-funnel-key"
UNLOCK_HEADER = "x-funnel-unlock"

TICKET_SECONDS = 300
CHALLENGE_SECONDS = 120
LOCK_FAILURES = 10  # bad tokens/keys from one address within LOCK_WINDOW...
LOCK_WINDOW = 600
LOCK_SECONDS = 900  # ...lock it out this long
PUBLIC_PORTS = (443, 8443, 10000)  # the only ports Funnel serves

# Open over Funnel with token + Funnel key: chat (live socket, chat list, files), boards, identity.
OPEN = ("/healthz", "/v1/me", "/v1/status", "/v1/profiles", "/v1/sessions", "/v1/media", "/v1/blueprints",
        "/v1/ws", "/v1/funnel/challenge", "/v1/funnel/unlock")
# Never over Funnel: local control, opting in (LAN/tailnet only), metrics.
NEVER = ("/v1/local", "/v1/funnel/enroll", "/metrics")
# Live-socket methods refused over Funnel even when unlocked: secrets and turning approvals off.
RESTRICTED_METHODS = frozenset({"vault.list", "vault.sources", "vault.add", "vault.remove"})
RESTRICTED_CONFIG_KEYS = frozenset({"yolo"})


def _under(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path == p or path.startswith(p + "/") for p in prefixes)


def route_class(path: str) -> str:
    """'open', 'unlock' (needs a ticket) or 'never' for a request path arriving over Funnel."""
    if _under(path, NEVER):
        return "never"
    if _under(path, OPEN):
        return "open"
    return "unlock"  # deny by default: anything not listed needs the phone unlocked


def proxied(peer_is_loopback: bool, headers) -> str | None:
    """'funnel', 'serve' or None (a genuinely local client) for a loopback connection."""
    if not peer_is_loopback:
        return None
    if headers.get(FUNNEL_HEADER) == "?1":
        return "funnel"
    if headers.get(LOGIN_HEADER) or headers.get(PROXY_HEADER):
        return "serve"
    return None


def client_ip(headers) -> str:
    """The internet address tailscaled saw (it sets X-Forwarded-For itself, replacing a client's)."""
    return (headers.get("x-forwarded-for") or "?").split(",")[0].strip()[:64] or "?"


def unlock_message(nonce: str, device_id: str) -> bytes:
    """What the phone's hardware key signs to unlock: bound to this challenge and this device."""
    return f"hermes-remote-funnel-unlock:{nonce}:{device_id}".encode()


def verify_signature(public_key_b64: str, message: bytes, signature_b64: str) -> bool:
    """ECDSA P-256 / SHA-256 signature by the phone's key (X.509 SubjectPublicKeyInfo, base64)."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    try:
        key = serialization.load_der_public_key(base64.b64decode(public_key_b64))
        if not isinstance(key, ec.EllipticCurvePublicKey):
            return False
        key.verify(base64.b64decode(signature_b64), message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


def check_public_key(public_key_b64: str) -> None:
    """Refuse anything but an EC P-256 public key (what Android's Keystore makes)."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    try:
        key = serialization.load_der_public_key(base64.b64decode(public_key_b64))
    except (ValueError, TypeError) as exc:
        raise ValueError("Not a public key") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
        raise ValueError("The phone key must be EC P-256")


class FunnelStore:
    """Phones allowed over Funnel: device id -> SHA-256 of its Funnel key + its unlock public key.

    A file of its own (not devices.json), so an older bridge reading devices.json is unaffected.
    """

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()

    def _read(self) -> dict:
        try:
            return json.loads(self.path.read_text() or "{}").get("devices", {})
        except FileNotFoundError:
            return {}

    def _write(self, devices: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump({"version": 1, "devices": devices}, fh, indent=2)
        os.replace(tmp, self.path)

    def enroll(self, device_id: str, public_key_b64: str) -> str:
        """Allow [device_id] over Funnel; returns its new Funnel key (shown once, only its hash is kept)."""
        check_public_key(public_key_b64)
        key = "hrf_" + secrets.token_urlsafe(32)
        with self._lock:
            devices = self._read()
            devices[device_id] = {"key_sha256": hashlib.sha256(key.encode()).hexdigest(),
                                  "public_key": public_key_b64, "enrolled_at": time.time()}
            self._write(devices)
        return key

    def remove(self, device_id: str) -> bool:
        with self._lock:
            devices = self._read()
            gone = devices.pop(device_id, None) is not None
            if gone:
                self._write(devices)
            return gone

    def enrolled(self) -> dict:
        with self._lock:
            return self._read()

    def check(self, device_id: str, key: str) -> bool:
        if not key or len(key) > 200:
            return False
        entry = self.enrolled().get(device_id)
        return entry is not None and hmac.compare_digest(entry["key_sha256"], hashlib.sha256(key.encode()).hexdigest())

    def check_id(self, device_id: str) -> bool:
        return device_id in self.enrolled()

    def public_key(self, device_id: str) -> str | None:
        return (self.enrolled().get(device_id) or {}).get("public_key")


class Lockout:
    """Counts failures per internet address; too many and the address is refused for a while."""

    def __init__(self, failures: int = LOCK_FAILURES, window: float = LOCK_WINDOW, lock: float = LOCK_SECONDS,
                 clock=time.monotonic):
        self.failures, self.window, self.lock, self.clock = failures, window, lock, clock
        self._hits: dict[str, list[float]] = {}
        self._until: dict[str, float] = {}

    def locked(self, ip: str) -> bool:
        until = self._until.get(ip)
        if until is None:
            return False
        if self.clock() >= until:
            self._until.pop(ip, None)
            return False
        return True

    def fail(self, ip: str) -> bool:
        """Note one failure; True when this one locked the address out."""
        now = self.clock()
        hits = [t for t in self._hits.get(ip, []) if now - t < self.window] + [now]
        self._hits[ip] = hits
        if len(hits) >= self.failures:
            self._until[ip] = now + self.lock
            self._hits.pop(ip, None)
            return True
        return False


class Tickets:
    """Unlock challenges (one use, 2 minutes) and the 5 minute tickets they buy."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._challenges: dict[str, tuple[str, float]] = {}
        self._tickets: dict[str, tuple[str, float]] = {}

    def challenge(self, device_id: str) -> str:
        now = self.clock()
        self._challenges = {n: v for n, v in self._challenges.items() if v[1] > now}
        nonce = secrets.token_urlsafe(24)
        self._challenges[nonce] = (device_id, now + CHALLENGE_SECONDS)
        return nonce

    def redeem(self, device_id: str, nonce: str, verified: bool) -> str | None:
        """A ticket for [device_id] when [nonce] is its live challenge and the signature [verified]."""
        entry = self._challenges.pop(nonce, None)  # one use, even when the signature is wrong
        if entry is None or entry[0] != device_id or entry[1] <= self.clock() or not verified:
            return None
        now = self.clock()
        self._tickets = {t: v for t, v in self._tickets.items() if v[1] > now}
        ticket = "hrt_" + secrets.token_urlsafe(24)
        self._tickets[ticket] = (device_id, now + TICKET_SECONDS)
        return ticket

    def valid(self, device_id: str, ticket: str | None) -> bool:
        entry = self._tickets.get(ticket or "")
        return entry is not None and entry[0] == device_id and entry[1] > self.clock()


# ------------------------------------------------------------------ Tailscale serve config

def serve_entry(dns_name: str, public_port: int) -> str:
    return f"{dns_name.rstrip('.')}:{public_port}"


def with_funnel(config: dict | None, dns_name: str, public_port: int, local_port: int) -> dict:
    """[config] (tailscaled's serve config) plus a Funnel of https://dns_name:public_port -> the bridge."""
    if public_port not in PUBLIC_PORTS:
        raise ValueError(f"Funnel serves only ports {', '.join(map(str, PUBLIC_PORTS))}")
    cfg = json.loads(json.dumps(config or {}))
    hp = serve_entry(dns_name, public_port)
    web = (cfg.get("Web") or {}).get(hp)
    proxy = f"http://127.0.0.1:{local_port}"
    if web and any(h.get("Proxy") != proxy for h in (web.get("Handlers") or {}).values()):
        raise ValueError(f"Port {public_port} already serves something else on this PC; pick another port")
    cfg.setdefault("TCP", {})[str(public_port)] = {"HTTPS": True}
    cfg.setdefault("Web", {})[hp] = {"Handlers": {"/": {"Proxy": proxy}}}
    cfg.setdefault("AllowFunnel", {})[hp] = True
    return cfg


def without_funnel(config: dict | None, local_port: int) -> dict:
    """[config] with every Funnel/Serve entry that points at the bridge removed (others are kept)."""
    cfg = json.loads(json.dumps(config or {}))
    proxy = f"http://127.0.0.1:{local_port}"
    for hp, web in list((cfg.get("Web") or {}).items()):
        if any(h.get("Proxy") == proxy for h in (web.get("Handlers") or {}).values()):
            del cfg["Web"][hp]
            (cfg.get("AllowFunnel") or {}).pop(hp, None)
            port = hp.rsplit(":", 1)[-1]
            if not any(k.rsplit(":", 1)[-1] == port for k in cfg["Web"]):
                (cfg.get("TCP") or {}).pop(port, None)
    for k in ("Web", "AllowFunnel", "TCP"):
        if k in cfg and not cfg[k]:
            del cfg[k]
    return cfg


def funnel_url(config: dict | None, local_port: int) -> str | None:
    """The public https URL when this serve config funnels to the bridge, else None."""
    proxy = f"http://127.0.0.1:{local_port}"
    for hp, web in ((config or {}).get("Web") or {}).items():
        if ((config or {}).get("AllowFunnel") or {}).get(hp) and any(
                h.get("Proxy") == proxy for h in (web.get("Handlers") or {}).values()):
            host, port = hp.rsplit(":", 1)
            return f"https://{host}" + ("" if port == "443" else f":{port}")
    return None


def why_unavailable(status: dict) -> str | None:
    """Why this tailnet can't Funnel yet (the user fixes it in the Tailscale admin console), or None."""
    self_ = status.get("Self") or {}
    caps = set((self_.get("CapMap") or {}).keys()) | set(self_.get("Capabilities") or [])
    if status.get("BackendState", "Running") != "Running":
        return "Tailscale is not running on the PC"
    if not status.get("CertDomains") and not status.get("_gate_ok"):
        return ("HTTPS certificates are off for your tailnet: in the Tailscale admin console, DNS page, "
                "enable MagicDNS and HTTPS Certificates")
    if not status.get("_gate_ok") and not any(c == "funnel" or c.endswith("/funnel") for c in caps):
        return ("Funnel is not allowed for this PC: in the Tailscale admin console, Access controls, add the "
                "\"funnel\" node attribute for it (Tailscale offers a one-click button when you first try)")
    return None


def feature_gate(answer: dict | None) -> tuple[str | None, str | None]:
    """(reason, enable link) from tailscaled's `query-feature` answer; (None, None) = allowed.

    Tailscale answers with a one-click enable page when the tailnet has the feature off (admin only),
    which is the real blocker on a fresh tailnet; the phone shows that link as a button.
    """
    if not answer or not (answer.get("Text") or answer.get("URL")):
        return None, None
    text = " ".join(str(answer.get("Text") or "").split()).removesuffix("To enable, visit:").strip()
    url = str(answer.get("URL") or "") or None
    if url and not url.startswith("https://login.tailscale.com/"):
        url = None  # only ever hand the phone Tailscale's own page
    return text or "Funnel is not enabled on your tailnet", url


class ServeConfig:
    """tailscaled's LocalAPI serve config (Linux unix socket)."""

    def __init__(self, socket_path: str | None):
        self.socket_path = socket_path

    def _client(self):
        import httpx

        if not self.socket_path:
            raise RuntimeError("Tailscale's local API is not reachable on this PC")
        return httpx.Client(transport=httpx.HTTPTransport(uds=self.socket_path), base_url="http://local-tailscaled.sock",
                            timeout=10.0, headers={"Sec-Tailscale": "localapi"})

    _asked = False  # query-feature answered (newer tailscaled): it is the authority, not CertDomains

    def _gate(self, c) -> tuple[str | None, str | None]:
        """Ask tailscaled whether Serve and Funnel are enabled for the tailnet (newer tailscaled only)."""
        self._asked = False
        for feature in ("serve", "funnel"):
            try:
                r = c.post("/localapi/v0/query-feature", params={"feature": feature})
            except Exception:  # noqa: BLE001
                return None, None
            if r.status_code != 200:
                return None, None  # an older tailscaled: fall back to the status checks
            reason, url = feature_gate(r.json())
            if reason:
                return reason, url
        self._asked = True
        return None, None

    def status(self, local_port: int) -> dict:
        with self._client() as c:
            st = c.get("/localapi/v0/status").raise_for_status().json()
            cfg = c.get("/localapi/v0/serve-config").raise_for_status().json()
            gate, enable_url = self._gate(c)
        reason = gate or why_unavailable({**st, "_gate_ok": gate is None and enable_url is None and self._asked})
        return {"available": reason is None, "reason": reason, "enable_url": enable_url,
                "dns_name": (st.get("Self") or {}).get("DNSName", "").rstrip(".") or None,
                "url": funnel_url(cfg, local_port), "on": funnel_url(cfg, local_port) is not None}

    def set(self, on: bool, local_port: int, public_port: int = 443) -> dict:
        with self._client() as c:
            st = c.get("/localapi/v0/status").raise_for_status().json()
            if on and (reason := self._gate(c)[0] or why_unavailable({**st, "_gate_ok": self._asked})):
                raise RuntimeError(reason)
            r = c.get("/localapi/v0/serve-config").raise_for_status()
            current, etag = r.json(), r.headers.get("etag", "")
            dns = (st.get("Self") or {}).get("DNSName", "")
            new = (with_funnel(current, dns, public_port, local_port) if on else without_funnel(current, local_port))
            c.post("/localapi/v0/serve-config", content=json.dumps(new),
                   headers={"If-Match": etag, "Content-Type": "application/json"}).raise_for_status()
        return self.status(local_port)
