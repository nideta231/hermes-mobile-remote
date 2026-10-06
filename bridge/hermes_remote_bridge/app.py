"""FastAPI app: the only surface the Android client talks to."""
from __future__ import annotations

import asyncio
import contextlib
import getpass
import json
import logging
import logging.handlers
import os
import re
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .config import Config
from .devices import Device, DeviceStore
from .hermes import HermesClient, HermesError
from .host import host
from .runs import RunManager
from .slash import SlashError, TuiGateway
from .network import TrustStore, network_info, serving_lan_ips
from .tailnet import TailnetClient, is_loopback, is_private_lan_ip, is_tailnet_ip

log = logging.getLogger("hermes_remote_bridge")

ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
HEAVY_MESSAGE_FIELDS = ("reasoning", "reasoning_content", "reasoning_details", "codex_reasoning_items")
CLIENT_REQ_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
APPROVAL_CHOICES = {"once", "session", "always", "deny"}


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra: Any):
        self.status, self.code, self.message, self.extra = status, code, message, extra


def _check_id(value: str, what: str) -> str:
    if not ID_RE.match(value):
        raise ApiError(400, "invalid_id", f"Invalid {what}")
    return value


# ---------------------------------------------------------------- request models

class CreateSession(BaseModel):
    title: str | None = Field(default=None, max_length=200)


class PatchSession(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    # Desktop-sidebar flags Hermes persists per session; the client only sets `pinned`.
    pinned: bool | None = None
    archived: bool | None = None


class CreateRun(BaseModel):
    session_id: str
    input: str = Field(min_length=1, max_length=100_000)
    # Per-run model override. Empty/None keeps the session's current model.
    model: str | None = Field(default=None, max_length=200)
    provider: str | None = Field(default=None, max_length=100)
    # Per-run reasoning effort; None uses the session/config default.
    reasoning_effort: str | None = Field(default=None, pattern=r"^(none|minimal|low|medium|high|xhigh|max)$")
    # Client-generated per user "send" action; resending the same id never starts a second run.
    client_request_id: str


class SlashCommand(BaseModel):
    command: str = Field(min_length=2, max_length=20_000, pattern=r"^/?[A-Za-z0-9][\w.-]*(\s[\s\S]*)?$")


class SteerRun(BaseModel):
    text: str = Field(min_length=1, max_length=20_000)


class ApprovalDecision(BaseModel):
    choice: str
    request_id: str | None = Field(default=None, max_length=256)


class SetApprovalMode(BaseModel):
    mode: str = Field(max_length=16)


# ---------------------------------------------------------------- helpers

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


async def _service_state(unit: str) -> str:
    return await asyncio.to_thread(host().service_state, unit)


async def _tcp_open(host: str, port: int) -> bool:
    try:
        _, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=2)
    except (OSError, asyncio.TimeoutError):
        return False
    writer.close()
    with contextlib.suppress(Exception):
        await writer.wait_closed()
    return True


# ---------------------------------------------------------------- app factory

APPROVAL_MODES = ("manual", "smart", "off")
REASONING_LEVELS = ("none", "low", "medium", "high", "xhigh", "max")

# Session source the bridge stamps on sessions it creates (see create_session).
SESSION_SOURCE = "cli"


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


def create_app(cfg: Config, *, hermes: HermesClient | None = None, tailnet: TailnetClient | None = None,
               devices: DeviceStore | None = None, owner_login: str | None = None,
               hermes_cli=None, trust: TrustStore | None = None, slash: TuiGateway | None = None) -> FastAPI:
    hermes = hermes or HermesClient(cfg.hermes_url, cfg.hermes_env)
    cli = hermes_cli or (lambda *a: _hermes_cli(cfg, *a))
    tailnet = tailnet or TailnetClient()
    devices = devices or DeviceStore(cfg.devices_file)
    trust = trust or TrustStore(cfg.trust_file)
    slash = slash or TuiGateway(cfg.hermes_root, cfg.hermes_python)
    allowed_logins = set(cfg.allowed_logins) or ({owner_login} if owner_login else set())
    audit = _audit_logger(cfg)
    limiter = RateLimiter()
    runs = RunManager(hermes.open_run_events, hermes.run_status,
                      max_events=cfg.run_buffer_events, retention_seconds=cfg.run_retention_seconds)
    client_requests: dict[str, tuple[str, float]] = {}  # device:client_request_id -> (run_id, ts)

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        async def housekeeping() -> None:
            while True:
                await asyncio.sleep(60)
                runs.prune()
                cutoff = time.time() - 86_400
                for key, (_, ts) in list(client_requests.items()):
                    if ts < cutoff:
                        del client_requests[key]
                with contextlib.suppress(Exception):
                    devices.flush_last_seen()
                await slash.close_if_idle()

        task = asyncio.create_task(housekeeping())
        try:
            yield
        finally:
            task.cancel()
            await runs.shutdown()
            await slash.close()
            with contextlib.suppress(Exception):
                devices.flush_last_seen()
            await hermes.aclose()
            await tailnet.aclose()

    app = FastAPI(title="Hermes Remote Bridge", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.runs = runs

    # ------------------------------------------------------------ error mapping

    def _err(status: int, code: str, message: str, **extra: Any) -> JSONResponse:
        return JSONResponse({"error": {"code": code, "message": message, **extra}}, status_code=status)

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        return _err(exc.status, exc.code, exc.message, **exc.extra)

    @app.exception_handler(SlashError)
    async def _slash_error(_: Request, exc: SlashError):
        return _err(exc.status, exc.code, exc.message)

    @app.exception_handler(HermesError)
    async def _hermes_error(_: Request, exc: HermesError):
        return _err(exc.status, exc.code, exc.message)

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

    # ------------------------------------------------------------ network + size guard

    @app.middleware("http")
    async def guard(request: Request, call_next):
        start = time.monotonic()
        peer = request.client.host if request.client else ""
        request.state.peer_node = "local" if is_loopback(peer) else None
        request.state.device = None
        if not is_loopback(peer):
            if is_tailnet_ip(peer):
                who = await tailnet.whois(peer)
                if who is None or who["login"] not in allowed_logins:
                    audit.info(json.dumps({"ts": time.time(), "event": "peer_rejected", "peer": peer,
                                           "login": who and who["login"]}))
                    return _err(403, "forbidden_peer", "This tailnet identity is not allowed")
                request.state.peer_node = who["node"]
            elif cfg.lan and is_private_lan_ip(peer) and request.url.scheme == "https":
                # No tailnet identity exists on the LAN, so the device token is the only
                # authenticator. Logged distinctly so LAN use is always attributable.
                request.state.peer_node = f"lan:{peer}"
            else:
                return _err(403, "forbidden_network",
                            "Only tailnet peers may connect" if not cfg.lan
                            else "Only tailnet or local-network peers may connect")
        if request.method in ("POST", "PATCH", "PUT"):
            length = request.headers.get("content-length")
            if length is None and request.headers.get("transfer-encoding"):
                return _err(411, "length_required", "Chunked request bodies are not accepted")
            if length is not None and (not length.isdigit() or int(length) > cfg.max_body_bytes):
                return _err(413, "body_too_large", "Request body too large")
        response = await call_next(request)
        if request.url.path != "/healthz":
            device: Device | None = request.state.device
            audit.info(json.dumps({
                "ts": round(time.time(), 3), "peer": request.state.peer_node,
                "device": device.name if device else None, "method": request.method,
                "path": request.url.path, "status": response.status_code,
                "ms": round((time.monotonic() - start) * 1000)}))
        return response

    # ------------------------------------------------------------ device auth

    async def device_auth(request: Request) -> Device:
        header = request.headers.get("authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        device = devices.authenticate(token) if token else None
        if device is None:
            raise ApiError(401, "unauthorized", "Missing, invalid or revoked device token")
        request.state.device = device
        if not limiter.allow(f"req:{device.id}", cfg.requests_per_minute):
            raise ApiError(429, "rate_limited", "Too many requests")
        return device

    # ------------------------------------------------------------ meta

    @app.get("/healthz")
    async def healthz():
        return {"ok": True}

    async def _tailnet_ips() -> list[str]:
        """This node's tailnet addresses, or [] when Tailscale is not running."""
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
                # LAN addresses are only those served right now (trusted network, HTTPS).
                "addresses": {"lan": await asyncio.to_thread(serving_lan_ips, cfg.lan, trust),
                              "tailnet": await _tailnet_ips()},
                "lan_scheme": "https",
                "network": await asyncio.to_thread(network_info, trust) if cfg.lan else None,
                "bridge_version": __version__}

    @app.get("/v1/status")
    async def status(_: Device = Depends(device_auth)):
        components: dict[str, Any] = {"bridge": {"status": "ok", "version": __version__,
                                                 "active_runs": len(runs.list(active_only=True))}}
        try:
            h = await hermes.health()
            readiness = h.get("readiness") or {}
            components["hermes"] = {
                "status": readiness.get("status") or h.get("status"),
                "version": h.get("version"),
                "gateway_state": h.get("gateway_state"),
                "checks": {k: v.get("status") for k, v in (readiness.get("checks") or {}).items()},
            }
        except HermesError as exc:
            components["hermes"] = {"status": "unreachable", "error": exc.message}
        try:
            mo = (await hermes.request("GET", "/api/model/options", ok=(200,))).json()
            components["model"] = {"provider": mo.get("provider"), "model": mo.get("model")}
        except HermesError:
            components["model"] = None
        # "unknown" = this OS has no unit to ask (Windows RDP is a built-in service): the open
        # port is then the whole answer.
        krdp_state = await _service_state(cfg.krdp_unit)
        components["desktop"] = {"status": "ok" if krdp_state in ("active", "unknown") and await _tcp_open("127.0.0.1", cfg.krdp_port)
                                 else "down", "unit_state": krdp_state, "port": cfg.krdp_port}
        try:
            ts = await tailnet.status()
            components["tailscale"] = {
                "status": "ok" if ts.get("BackendState") == "Running" else ts.get("BackendState"),
                "host": ts["Self"].get("HostName"), "dns_name": ts["Self"].get("DNSName", "").rstrip("."),
                "peers": [{"name": p.get("HostName"), "os": p.get("OS"), "online": bool(p.get("Online"))}
                          for p in (ts.get("Peer") or {}).values()],
            }
        except Exception:
            components["tailscale"] = {"status": "unreachable"}
        return {"components": components, "checked_at": time.time()}

    @app.get("/v1/desktop")
    async def desktop(_: Device = Depends(device_auth)):
        ts = await tailnet.status()
        ips = ts["Self"]["TailscaleIPs"]
        return {"protocol": "rdp", "host": next((ip for ip in ips if "." in ip), ips[0]),
                "dns_name": ts["Self"].get("DNSName", "").rstrip("."), "port": cfg.krdp_port,
                "username": getpass.getuser(),
                "rdp_uri": f"rdp://full%20address=s:{next((ip for ip in ips if '.' in ip), ips[0])}:{cfg.krdp_port}"}

    # ------------------------------------------------------------ sessions

    @app.get("/v1/sessions")
    async def list_sessions(limit: int = 30, offset: int = 0, _: Device = Depends(device_auth)):
        params = {"limit": max(1, min(limit, 100)), "offset": max(0, offset)}
        return (await hermes.request("GET", "/api/sessions", params=params, ok=(200,))).json()

    @app.post("/v1/sessions", status_code=201)
    async def create_session(body: CreateSession, _: Device = Depends(device_auth)):
        # `cli`, not the API's default `api_server`: the desktop files api_server sessions under
        # Messaging and keeps them out of its Sessions sidebar, so a chat started on the phone
        # would never show up on the PC. The bridge drives the same agent loop the CLI does.
        payload = {"source": SESSION_SOURCE} | ({"title": body.title} if body.title else {})
        return (await hermes.request("POST", "/api/sessions", json=payload)).json()

    @app.get("/v1/sessions/{session_id}")
    async def get_session(session_id: str, _: Device = Depends(device_auth)):
        data = (await hermes.request("GET", f"/api/sessions/{_check_id(session_id, 'session id')}", ok=(200,))).json()
        active = runs.active_for_session(session_id)
        data["active_run"] = active.snapshot() if active else None
        return data

    @app.patch("/v1/sessions/{session_id}")
    async def patch_session(session_id: str, body: PatchSession, _: Device = Depends(device_auth)):
        payload: dict[str, Any] = {}
        if body.title is not None:
            payload["title"] = body.title
        if body.pinned is not None:
            payload["pinned"] = body.pinned
        if body.archived is not None:
            payload["archived"] = body.archived
        if not payload:
            raise ApiError(400, "empty_patch", "Provide at least one of: title, pinned, archived")
        return (await hermes.request("PATCH", f"/api/sessions/{_check_id(session_id, 'session id')}",
                                     json=payload, ok=(200,))).json()

    @app.delete("/v1/sessions/{session_id}")
    async def delete_session(session_id: str, confirm: str = "", _: Device = Depends(device_auth)):
        _check_id(session_id, "session id")
        if confirm != session_id:
            raise ApiError(428, "confirmation_required",
                           "Deleting a session is permanent; repeat the session id in ?confirm=")
        if runs.active_for_session(session_id):
            raise ApiError(409, "session_busy", "Stop the active run before deleting this session")
        return (await hermes.request("DELETE", f"/api/sessions/{session_id}", ok=(200, 204))).json()

    @app.get("/v1/sessions/{session_id}/messages")
    async def session_messages(session_id: str, limit: int = 200, offset: int = 0,
                               _: Device = Depends(device_auth)):
        params = {"limit": max(1, min(limit, 500)), "offset": max(0, offset)}
        data = (await hermes.request("GET", f"/api/sessions/{_check_id(session_id, 'session id')}/messages",
                                     params=params, ok=(200,))).json()
        # The phone never shows the model's reasoning; it is most of a long session's bytes.
        for row in data.get("data", []):
            for key in HEAVY_MESSAGE_FIELDS:
                row.pop(key, None)
        return data

    @app.post("/v1/sessions/{session_id}/fork", status_code=201)
    async def fork_session(session_id: str, body: CreateSession, _: Device = Depends(device_auth)):
        payload = {"title": body.title} if body.title else {}
        return (await hermes.request("POST", f"/api/sessions/{_check_id(session_id, 'session id')}/fork",
                                     json=payload)).json()

    @app.get("/v1/sessions/{session_id}/sync")
    async def sync_session(session_id: str, since: int = 0, limit: int = 60,
                            _: Device = Depends(device_auth)):
        """Tail a session for messages appended since `since` (a message count).

        Lets the app follow a conversation that is running on another surface (desktop,
        Telegram) instead of only runs the bridge itself started. Returns the messages
        after the caller's cursor plus the new cursor, so a poller never re-sends or
        skips a message. `active` is true while the session is mid-turn.
        """
        _check_id(session_id, "session id")
        head = (await hermes.request("GET", f"/api/sessions/{session_id}", ok=(200,))).json()
        session = head.get("session", head)
        count = int(session.get("message_count") or 0)
        if since <= 0:
            # First sync of a session: hand back only the tail so the app is not
            # re-rendering a 500-message history it already has cached.
            cursor = max(0, count - min(limit, 60))
        else:
            cursor = min(since, count)
        rows = []
        if count > cursor:
            page = (await hermes.request("GET", f"/api/sessions/{session_id}/messages",
                                         params={"limit": min(500, count - cursor), "offset": cursor,
                                                 "order": "oldest"}, ok=(200,))).json()
            rows = page.get("data", [])
            for row in rows:
                for key in HEAVY_MESSAGE_FIELDS:
                    row.pop(key, None)
        return {
            "messages": rows,
            "cursor": count,
            "count": count,
            "changed": count != since,
            "active": runs.active_for_session(session_id) is not None,
            "ended_at": session.get("ended_at"),
            "end_reason": session.get("end_reason"),
            "pinned": bool(session.get("pinned")),
            "model": session.get("model"),
            "title": session.get("title"),
        }

    # ------------------------------------------------------------ models

    @app.get("/v1/models")
    async def list_models(_: Device = Depends(device_auth)):
        """Selectable models: authenticated providers only, current one first.

        Hermes marks a provider's unavailable models in `unavailable_models`; those are dropped
        so the app never offers a model the account cannot run.

        One exception: the provider in use right now is always offered, even if Hermes reports all
        of its models as unavailable. That happens with Hermes' own provider, where the live model
        is a private alias rather than anything in the catalog, so the catalog says "unavailable"
        for all 55 while the alias runs fine. Dropping it would leave the app offering nothing that
        is actually in use, and point the user at some other provider instead.
        """
        raw = (await hermes.request("GET", "/api/model/options", ok=(200,))).json()
        providers = []
        for p in raw.get("providers", []):
            if not p.get("authenticated") or not p.get("models"):
                continue
            is_current = bool(p.get("is_current"))
            blocked = set(p.get("unavailable_models") or ())
            models = [m for m in p["models"] if m not in blocked]
            if not models and is_current:
                # Keep the group so the current model stays selectable; the app shows the live
                # alias from `current` rather than this list.
                models = list(p["models"])[:1]
                blocked.discard(models[0])
            if not models:
                continue
            featured = [m for m in (p.get("featured_models") or ()) if m in models]
            if is_current and raw.get("model") not in featured:
                featured.insert(0, raw.get("model"))
            providers.append({
                "slug": p["slug"], "name": p.get("name") or p["slug"],
                "current": is_current,
                "default": p.get("source") == "virtual" and len(models) == 1,
                "models": models, "featured": featured,
                "capabilities": {m: p.get("capabilities", {}).get(m) for m in featured},
            })
        return {"current": {"model": raw.get("model"), "provider": raw.get("provider")},
                "providers": providers, "reasoning": await _reasoning_default()}

    async def _reasoning_default() -> dict:
        """Configured default effort plus the levels the app offers."""
        effort = ""
        with contextlib.suppress(Exception):
            effort = (await cli("config", "get", "agent.reasoning_effort")).strip().strip('"').lower()
        return {"default": effort or "medium", "levels": list(REASONING_LEVELS)}

    # ------------------------------------------------------------ slash commands

    @app.get("/v1/commands")
    async def list_commands(_: Device = Depends(device_auth)):
        return {"data": await slash.catalog()}

    @app.post("/v1/sessions/{session_id}/command")
    async def run_command(session_id: str, body: SlashCommand, device: Device = Depends(device_auth)):
        _check_id(session_id, "session id")
        if runs.active_for_session(session_id):
            raise ApiError(409, "session_busy", "Wait for the current reply to finish")
        if not limiter.allow(f"cmd:{device.id}", cfg.runs_per_minute):
            raise ApiError(429, "rate_limited", "Too many commands")
        return await slash.run(session_id, body.command)

    # ------------------------------------------------------------ runs

    @app.post("/v1/runs", status_code=202)
    async def create_run(body: CreateRun, device: Device = Depends(device_auth)):
        _check_id(body.session_id, "session id")
        if not CLIENT_REQ_RE.match(body.client_request_id):
            raise ApiError(400, "invalid_client_request_id", "client_request_id must be 8-64 [A-Za-z0-9_-]")
        dedupe_key = f"{device.id}:{body.client_request_id}"
        known = client_requests.get(dedupe_key)
        if known and (run := runs.get(known[0])):
            return {**run.snapshot(), "replayed": True}
        active = runs.active_for_session(body.session_id)
        if active:
            raise ApiError(409, "session_busy", "This session already has an active run", run_id=active.run_id)
        if not limiter.allow(f"run:{device.id}", cfg.runs_per_minute):
            raise ApiError(429, "rate_limited", "Too many runs started")
        payload: dict[str, Any] = {"input": body.input, "session_id": body.session_id}
        if body.model:
            payload["model"] = body.model
        if body.provider:
            payload["provider"] = body.provider
        if body.reasoning_effort:
            payload["model_options"] = {"reasoning_effort": body.reasoning_effort}
        resp = await hermes.request("POST", "/v1/runs", json=payload,
                                    headers={"Idempotency-Key": dedupe_key})
        data = resp.json()
        run = runs.track(data["run_id"], body.session_id, device.id)
        client_requests[dedupe_key] = (run.run_id, time.time())
        return {**run.snapshot(), "replayed": bool(data.get("replayed"))}

    @app.get("/v1/runs")
    async def list_runs(session_id: str | None = None, active: bool = False, _: Device = Depends(device_auth)):
        return {"data": [r.snapshot() for r in runs.list(session_id, active_only=active)]}

    def _tracked(run_id: str):
        run = runs.get(_check_id(run_id, "run id"))
        if run is None:
            raise ApiError(404, "run_not_found", "Run not known to the bridge (expired or never started here)")
        return run

    @app.get("/v1/runs/{run_id}")
    async def get_run(run_id: str, _: Device = Depends(device_auth)):
        return _tracked(run_id).snapshot()

    @app.get("/v1/runs/{run_id}/events")
    async def run_events(run_id: str, request: Request, after: int | None = None,
                         _: Device = Depends(device_auth)):
        run = _tracked(run_id)
        last_id = request.headers.get("last-event-id", "")
        cursor = after if after is not None else (int(last_id) if last_id.isdigit() else 0)
        return StreamingResponse(runs.subscribe(run, max(0, cursor)), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.post("/v1/runs/{run_id}/stop")
    async def stop_run(run_id: str, _: Device = Depends(device_auth)):
        run = _tracked(run_id)
        if run.terminal:
            return run.snapshot()
        await hermes.request("POST", f"/v1/runs/{run_id}/stop", ok=(200, 202, 409))
        if not run.terminal:
            run.status = "stopping"
        return run.snapshot()

    @app.post("/v1/runs/{run_id}/steer")
    async def steer_run(run_id: str, body: SteerRun, _: Device = Depends(device_auth)):
        _tracked(run_id)
        return (await hermes.request("POST", f"/v1/runs/{run_id}/steer", json={"input": body.text}, ok=(200,))).json()

    @app.post("/v1/runs/{run_id}/approval")
    async def approve_run(run_id: str, body: ApprovalDecision, _: Device = Depends(device_auth)):
        run = _tracked(run_id)
        if body.choice not in APPROVAL_CHOICES:
            raise ApiError(400, "invalid_choice", f"choice must be one of {sorted(APPROVAL_CHOICES)}")
        if run.pending_approval is None:
            raise ApiError(409, "no_pending_approval", "This run is not waiting for approval")
        payload: dict[str, Any] = {"choice": body.choice}
        request_id = body.request_id or run.pending_approval.get("request_id")
        if request_id:
            payload["request_id"] = request_id
        return (await hermes.request("POST", f"/v1/runs/{run_id}/approval", json=payload, ok=(200,))).json()

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
        audit.info(json.dumps({"ts": time.time(), "event": "approval_mode_changed", "device": device.name,
                               "from": before, "to": after}))
        return {"mode": after, "modes": list(APPROVAL_MODES)}

    return app
