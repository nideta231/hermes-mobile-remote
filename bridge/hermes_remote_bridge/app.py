"""FastAPI app: the only surface the Android client talks to.

Two kinds of endpoint:

* `/v1/ws`: the live connection. A relay to the JSON-RPC socket of the same `hermes serve`
  process the desktop app uses (relay.py), so the phone and the PC are two views of one Hermes.
* A few REST calls for what Hermes serves over HTTP rather than the socket: identity and
  addresses (`/v1/me`), health (`/v1/status`), the session sidebar list with its pinned flag,
  and the global approval mode.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import logging.handlers
import os
import re
import socket
import time
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Request, WebSocket
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .backend import SESSION_HEADER, Backend, BackendLocator, BackendUnavailable
from .config import Config
from .devices import Device, DeviceStore
from .network import TrustStore, network_info, serving_lan_ips
from .relay import Relay
from .tailnet import TailnetClient, is_loopback, is_private_lan_ip, is_tailnet_ip

log = logging.getLogger("hermes_remote_bridge")

ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
APPROVAL_MODES = ("manual", "smart", "off")
# The wire contract between this bridge and the app. 2 = the WebSocket relay (v1.0.0).
PROTOCOL = 2


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra: Any):
        self.status, self.code, self.message, self.extra = status, code, message, extra


def _check_id(value: str, what: str) -> str:
    if not ID_RE.match(value):
        raise ApiError(400, "invalid_id", f"Invalid {what}")
    return value


class PatchSession(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    # Desktop-sidebar flags Hermes persists per session.
    pinned: bool | None = None
    archived: bool | None = None


class SetApprovalMode(BaseModel):
    mode: str = Field(max_length=16)


class RateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str, limit: int, window: float = 60.0) -> bool:
        now = time.monotonic()
        hits = self._hits[key]
        while hits and now - hits[0] > window:
            hits.popleft()
        if len(hits) >= limit:
            return False
        hits.append(now)
        return True


def _audit_logger(cfg: Config) -> logging.Logger:
    cfg.audit_log.parent.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger(f"hermes_remote_bridge.audit.{cfg.audit_log}")
    logger.propagate = False
    if not logger.handlers:
        handler = logging.handlers.RotatingFileHandler(cfg.audit_log, maxBytes=2_000_000, backupCount=5)
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    os.chmod(cfg.audit_log, 0o600)
    return logger


async def _hermes_cli(cfg: Config, *args: str) -> str:
    """Run the Hermes CLI with fixed argv (no shell) and return stdout; raise on failure."""
    proc = await asyncio.create_subprocess_exec(
        str(cfg.hermes_bin), *args,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=20)
    except asyncio.TimeoutError:
        proc.kill()
        raise ApiError(504, "hermes_cli_timeout", "Hermes CLI did not answer in time")
    if proc.returncode != 0:
        raise ApiError(502, "hermes_cli_failed", (err or out).decode(errors="replace").strip()[-300:])
    return out.decode(errors="replace")


def create_app(cfg: Config, *, locator: BackendLocator | None = None, tailnet: TailnetClient | None = None,
               devices: DeviceStore | None = None, owner_login: str | None = None,
               hermes_cli=None, trust: TrustStore | None = None,
               http: httpx.AsyncClient | None = None) -> FastAPI:
    locator = locator or BackendLocator(cfg.hermes_home, cfg.hermes_root, cfg.hermes_python, cfg.backend_log,
                                        spawn=cfg.start_hermes)
    cli = hermes_cli or (lambda *a: _hermes_cli(cfg, *a))
    tailnet = tailnet or TailnetClient()
    devices = devices or DeviceStore(cfg.devices_file)
    trust = trust or TrustStore(cfg.trust_file)
    http = http or httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=2.0))
    allowed_logins = set(cfg.allowed_logins) or ({owner_login} if owner_login else set())
    audit = _audit_logger(cfg)
    limiter = RateLimiter()

    def audit_event(entry: dict) -> None:
        audit.info(json.dumps({"ts": round(time.time(), 3), **entry}))

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        async def housekeeping() -> None:
            while True:
                await asyncio.sleep(60)
                with contextlib.suppress(Exception):
                    devices.flush_last_seen()

        task = asyncio.create_task(housekeeping())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(Exception):
                devices.flush_last_seen()
            await locator.aclose()
            await http.aclose()
            await tailnet.aclose()

    app = FastAPI(title="Hermes Remote Bridge", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    # ------------------------------------------------------------ error mapping

    def _err(status: int, code: str, message: str, **extra: Any) -> JSONResponse:
        return JSONResponse({"error": {"code": code, "message": message, **extra}}, status_code=status)

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        return _err(exc.status, exc.code, exc.message, **exc.extra)

    @app.exception_handler(BackendUnavailable)
    async def _backend_down(_: Request, exc: BackendUnavailable):
        return _err(503, "hermes_unavailable", str(exc))

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError):
        fields = [".".join(str(p) for p in e.get("loc", [])[1:]) for e in exc.errors()]
        return _err(422, "invalid_request", "Request body failed validation", fields=fields)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException):
        return _err(exc.status_code, "not_found" if exc.status_code == 404 else "http_error", str(exc.detail))

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception):
        log.exception("unhandled error")
        return _err(500, "internal_error", "Internal bridge error")

    # ------------------------------------------------------------ network guard (HTTP and WS)

    async def peer_verdict(peer: str, scheme_secure: bool) -> tuple[str | None, tuple[int, str, str] | None]:
        """(peer node label, rejection) for a connecting address."""
        if is_loopback(peer):
            return "local", None
        if is_tailnet_ip(peer):
            who = await tailnet.whois(peer)
            if who is None or who["login"] not in allowed_logins:
                audit_event({"event": "peer_rejected", "peer": peer, "login": who and who["login"]})
                return None, (403, "forbidden_peer", "This tailnet identity is not allowed")
            return who["node"], None
        if cfg.lan and is_private_lan_ip(peer) and scheme_secure:
            # No tailnet identity exists on the LAN, so the device token is the only
            # authenticator. Logged distinctly so LAN use is always attributable.
            return f"lan:{peer}", None
        return None, (403, "forbidden_network", "Only tailnet peers may connect" if not cfg.lan
                      else "Only tailnet or local-network peers may connect")

    @app.middleware("http")
    async def guard(request: Request, call_next):
        start = time.monotonic()
        peer = request.client.host if request.client else ""
        request.state.device = None
        node, rejected = await peer_verdict(peer, request.url.scheme == "https")
        if rejected:
            return _err(*rejected)
        request.state.peer_node = node
        if request.method in ("POST", "PATCH", "PUT"):
            length = request.headers.get("content-length")
            if length is None and request.headers.get("transfer-encoding"):
                return _err(411, "length_required", "Chunked request bodies are not accepted")
            if length is not None and (not length.isdigit() or int(length) > cfg.max_body_bytes):
                return _err(413, "body_too_large", "Request body too large")
        response = await call_next(request)
        if request.url.path != "/healthz":
            device: Device | None = request.state.device
            audit_event({"peer": node, "device": device.name if device else None, "method": request.method,
                         "path": request.url.path, "status": response.status_code,
                         "ms": round((time.monotonic() - start) * 1000)})
        return response

    # ------------------------------------------------------------ device auth

    def _bearer(header: str) -> str:
        return header[7:].strip() if header.lower().startswith("bearer ") else ""

    async def device_auth(request: Request) -> Device:
        token = _bearer(request.headers.get("authorization", ""))
        device = devices.authenticate(token) if token else None
        if device is None:
            raise ApiError(401, "unauthorized", "Missing, invalid or revoked device token")
        request.state.device = device
        if not limiter.allow(f"req:{device.id}", cfg.requests_per_minute):
            raise ApiError(429, "rate_limited", "Too many requests")
        return device

    # ------------------------------------------------------------ Hermes' HTTP API

    async def hermes_http(method: str, path: str, **kw) -> Any:
        backend: Backend = await locator.get()
        try:
            r = await http.request(method, f"{backend.http}{path}", headers={SESSION_HEADER: backend.token}, **kw)
        except httpx.HTTPError as exc:
            locator.forget(backend)
            raise ApiError(503, "hermes_unavailable", f"Hermes did not answer: {type(exc).__name__}") from exc
        if r.status_code == 401:
            locator.forget(backend)  # a new backend took the port; resolve again next time
            raise ApiError(503, "hermes_unavailable", "Hermes restarted; try again")
        if r.status_code >= 400:
            detail = ""
            with contextlib.suppress(Exception):
                detail = str(r.json().get("detail") or "")
            raise ApiError(r.status_code if r.status_code < 500 else 502, "hermes_error",
                           detail or f"Hermes answered HTTP {r.status_code}")
        return r.json() if r.content else {}

    # ------------------------------------------------------------ meta

    @app.get("/healthz")
    async def healthz():
        return {"ok": True}

    async def _tailnet_ips() -> list[str]:
        try:
            return await tailnet.status_self_ips()
        except Exception:
            return []

    @app.get("/v1/me")
    async def me(request: Request, device: Device = Depends(device_auth)):
        """Identity, plus every address this bridge answers on so the client can offer a switch."""
        return {"device": {"id": device.id, "name": device.name, "created_at": device.created_at},
                "peer_node": request.state.peer_node,
                "via": "lan" if str(request.state.peer_node).startswith("lan:") else "tailnet",
                "port": cfg.port,
                "addresses": {"lan": await asyncio.to_thread(serving_lan_ips, cfg.lan, trust),
                              "tailnet": await _tailnet_ips()},
                "lan_scheme": "https",
                "network": await asyncio.to_thread(network_info, trust) if cfg.lan else None,
                "pc_name": socket.gethostname(),
                "bridge_version": __version__, "protocol": PROTOCOL}

    @app.get("/v1/status")
    async def status(_: Device = Depends(device_auth)):
        components: dict[str, Any] = {"bridge": {"status": "ok", "version": __version__, "protocol": PROTOCOL}}
        try:
            backend = await locator.get(start=False)
            info = await hermes_http("GET", "/api/status")
            components["hermes"] = {"status": "ok", "version": info.get("version"), "port": backend.port,
                                    "shared_with_desktop": not backend.started_here}
        except (BackendUnavailable, ApiError) as exc:
            components["hermes"] = {"status": "down", "error": getattr(exc, "message", str(exc)),
                                    "starts_on_connect": cfg.start_hermes}
        try:
            ts = await tailnet.status()
            components["tailscale"] = {
                "status": "ok" if ts.get("BackendState") == "Running" else ts.get("BackendState"),
                "host": ts["Self"].get("HostName"), "dns_name": ts["Self"].get("DNSName", "").rstrip("."),
            }
        except Exception:
            components["tailscale"] = {"status": "unreachable"}
        return {"components": components, "checked_at": time.time()}

    # ------------------------------------------------------------ sessions (the sidebar list)

    @app.get("/v1/sessions")
    async def list_sessions(limit: int = 40, offset: int = 0, _: Device = Depends(device_auth)):
        """The desktop sidebar's own query: newest activity first, pinned rows included."""
        params = {"limit": max(1, min(limit, 100)), "offset": max(0, offset), "min_messages": 0,
                  "archived": "exclude", "order": "recent", "exclude_sources": "cron"}
        data = await hermes_http("GET", "/api/sessions", params=params)
        return {"sessions": data.get("sessions", []), "total": data.get("total"),
                "limit": data.get("limit"), "offset": data.get("offset")}

    @app.patch("/v1/sessions/{session_id}")
    async def patch_session(session_id: str, body: PatchSession, _: Device = Depends(device_auth)):
        payload = {k: v for k, v in body.model_dump().items() if v is not None}
        if not payload:
            raise ApiError(400, "empty_patch", "Provide at least one of: title, pinned, archived")
        return await hermes_http("PATCH", f"/api/sessions/{_check_id(session_id, 'session id')}", json=payload)

    # ------------------------------------------------------------ settings

    async def _approval_mode() -> str:
        mode = (await cli("config", "get", "approvals.mode")).strip().strip('"').lower()
        return mode if mode in APPROVAL_MODES else "manual"

    @app.get("/v1/settings/approvals")
    async def get_approvals(_: Device = Depends(device_auth)):
        return {"mode": await _approval_mode(), "modes": list(APPROVAL_MODES)}

    @app.put("/v1/settings/approvals")
    async def set_approvals(body: SetApprovalMode, device: Device = Depends(device_auth)):
        if body.mode not in APPROVAL_MODES:
            raise ApiError(400, "invalid_mode", f"mode must be one of {list(APPROVAL_MODES)}")
        before = await _approval_mode()
        if before != body.mode:
            await cli("config", "set", "approvals.mode", body.mode)
        after = await _approval_mode()
        if after != body.mode:
            raise ApiError(502, "not_applied", f"Hermes still reports approvals.mode={after}")
        audit_event({"event": "approval_mode_changed", "device": device.name, "from": before, "to": after})
        return {"mode": after, "modes": list(APPROVAL_MODES)}

    # ------------------------------------------------------------ the live connection

    @app.websocket("/v1/ws")
    async def live(ws: WebSocket):
        peer = ws.client.host if ws.client else ""
        node, rejected = await peer_verdict(peer, ws.url.scheme == "wss")
        if rejected:
            await ws.close(code=4403, reason=rejected[2])
            return
        # The token rides in the Authorization header (OkHttp sets it on the upgrade request),
        # never in the URL, so it stays out of every log line.
        token = _bearer(ws.headers.get("authorization", ""))
        device = devices.authenticate(token) if token else None
        if device is None:
            audit_event({"event": "ws_unauthorized", "peer": node})
            await ws.close(code=4401, reason="Missing, invalid or revoked device token")
            return
        frames_key = f"frames:{device.id}:{id(ws)}"
        relay = Relay(locator, max_frame_bytes=cfg.max_frame_bytes,
                      allow_frame=lambda: limiter.allow(frames_key, cfg.frames_per_minute),
                      audit=lambda e: audit_event({**e, "peer": node}))
        await relay.run(ws, device.name)

    app.state.locator = locator
    return app
