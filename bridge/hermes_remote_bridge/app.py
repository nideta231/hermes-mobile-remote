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
import base64
import contextlib
import hmac
import json
import logging
import logging.handlers
import mimetypes
import os
import re
import socket
import time
from pathlib import Path
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Request, WebSocket
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .backend import SESSION_HEADER, Backend, BackendLocator, BackendUnavailable
from .config import Config
from .devices import Device, DeviceStore
from . import local_secret
from . import selfupdate
from . import notes as notes_mod
from . import blueprint as bp_mod
from .local_api import devices_doc, pairing_payload, pairing_uri, qr_png, trust_doc
from .network import TrustStore, current_network, network_info, serving_lan_ips
from .chat_files import referenced_file
from .folders import FolderError, list_dirs, make_dir
from .metrics import CONTENT_TYPE as METRICS_CONTENT_TYPE
from .metrics import BridgeMetrics, read_metrics_token
from .project_notes import IDEA_MAX_BYTES, NoFolder, read_idea, write_idea
from .profiles import ProfileRefused, is_visible, media_roots, profile_home, resolve_profile
from .monitor import RANGES, TRACKING, ClaudeUsage, Monitor
from .relay import ParkedUpstreams, Relay
from .terminal import Shell
from .terminal import serve as serve_terminal
from .tailnet import TailnetClient, is_loopback, is_private_lan_ip, is_tailnet_ip

log = logging.getLogger("hermes_remote_bridge")

ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
APPROVAL_MODES = ("manual", "smart", "off")
# Images the phone may fetch for a chat (same set and cap as Hermes' own /api/media, minus SVG: script).
MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
               ".webp": "image/webp", ".bmp": "image/bmp"}
MEDIA_MAX_BYTES = 25 * 1024 * 1024
# Files a chat names (reports, spreadsheets, PDFs) the phone may download.
CHAT_FILE_MAX_BYTES = 100 * 1024 * 1024
# The wire contract between this bridge and the app. 2 = the WebSocket relay (v1.0.0).
PROTOCOL = 2
# Board attachments from the phone: larger than max_body_bytes, capped by blueprint.MAX_UPLOAD instead.
_UPLOAD_PATH = re.compile(r"/v1/blueprints/[^/]+/upload")
# Sent by a local client (the desktop app) on every `/v1/local/*` request.
LOCAL_HEADER = "X-Hermes-Local"


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra: Any):
        self.status, self.code, self.message, self.extra = status, code, message, extra


def _check_id(value: str, what: str) -> str:
    if not ID_RE.match(value):
        raise ApiError(400, "invalid_id", f"Invalid {what}")
    return value


# What the phone may do to a cron job. "trigger" is Hermes' word for "run now".
CRON_ACTIONS = ("pause", "resume", "trigger")
# The fields of a Hermes cron job the app shows. Prompts, scripts, delivery targets and paths stay
# on the PC: the phone needs to recognise a job and see how it is doing, not what it runs.
_CRON_JOB_FIELDS = ("id", "name", "schedule_display", "state", "enabled", "next_run_at", "last_run_at",
                    "last_status", "last_error", "failure_streak", "model")
_CRON_RUN_FIELDS = ("id", "title", "preview", "source", "started_at", "ended_at", "message_count", "model")


# Session search: how long a query may be, how many chats one search returns, and how much of the
# matching message the phone gets (a drawer line, not the message).
SEARCH_QUERY_MAX = 200
SEARCH_LIMIT_MAX = 50
SNIPPET_MAX_CHARS = 160
# The fields of a search hit the phone gets: a /v1/sessions row plus the matching text.
_SEARCH_ROW_FIELDS = ("id", "title", "preview", "source", "message_count", "last_active", "model", "pinned")
# Hermes' FTS5 snippet() wraps the match in these markers for the desktop to highlight.
_SNIPPET_MARKS = re.compile(r">>>|<<<")


def _snippet(text: Any) -> str:
    """The matching text on one short line: highlight markers dropped, whitespace collapsed."""
    flat = " ".join(_SNIPPET_MARKS.sub("", text if isinstance(text, str) else "").split())
    return flat if len(flat) <= SNIPPET_MAX_CHARS else flat[:SNIPPET_MAX_CHARS - 1].rstrip() + "…"


def _search_hit(row: dict) -> dict:
    """One Hermes search result in the shape of a /v1/sessions row (+ `snippet`).

    Hermes names the chat `session_id` (its compression tip) and leaves `last_active` empty for
    pure text hits; the phone wants `id` and some recency, so fall back to when the chat started.
    """
    hit = {k: row.get(k) for k in _SEARCH_ROW_FIELDS}
    hit["id"] = row.get("session_id") or row.get("id")
    hit["last_active"] = row.get("last_active") or row.get("started_at") or row.get("session_started")
    hit["pinned"] = bool(row.get("pinned"))
    hit["snippet"] = _snippet(row.get("snippet"))
    return hit


def _visible_hits(rows: list, limit: int) -> list[dict]:
    """The hits the sidebar would show: no archived chats (Hermes' search includes them), no
    cron runs, at most `limit`."""
    hits = [_search_hit(r) for r in rows
            if isinstance(r, dict) and not r.get("archived") and r.get("source") != "cron"
            and (r.get("session_id") or r.get("id"))]
    return hits[:limit]


def _cron_job(job: dict) -> dict:
    return {k: job.get(k) for k in _CRON_JOB_FIELDS}


def _cron_run(run: dict) -> dict:
    return {k: run.get(k) for k in _CRON_RUN_FIELDS}


class PatchSession(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    # Desktop-sidebar flags Hermes persists per session.
    pinned: bool | None = None
    archived: bool | None = None


class MakeFolder(BaseModel):
    path: str = Field(min_length=1, max_length=4096)


class ProjectIdea(BaseModel):
    text: str = Field(default="", max_length=IDEA_MAX_BYTES)


class CreateCronJob(BaseModel):
    """A job made from the phone: a prompt on a schedule, delivered locally. Scripts, working
    folders, toolsets and delivery targets stay a PC-side choice."""
    name: str = Field(default="", max_length=120)
    schedule: str = Field(min_length=1, max_length=120)
    prompt: str = Field(min_length=1, max_length=20_000)
    paused: bool = False


class SetApprovalMode(BaseModel):
    mode: str = Field(max_length=16)


class SetDefaultModel(BaseModel):
    model: str = Field(min_length=1, max_length=200)
    provider: str = Field(default="", max_length=100)
    confirm_expensive_model: bool = False


class LocalPair(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    replace: bool = False


class LocalName(BaseModel):
    name: str = Field(min_length=1, max_length=64)


class LocalNetworkId(BaseModel):
    id: str = Field(min_length=1, max_length=200)


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
               http: httpx.AsyncClient | None = None, monitor: Monitor | None = None,
               shell_factory=None, park_sleep=asyncio.sleep) -> FastAPI:
    if monitor is None and cfg.monitor:
        monitor = Monitor(cfg.monitor_db, ClaudeUsage(cfg.claude_credentials) if cfg.claude_usage else None)
    shell_factory = shell_factory or (lambda **kw: Shell(cfg=cfg, **kw))
    locator = locator or BackendLocator(cfg.hermes_home, cfg.hermes_root, cfg.hermes_python, cfg.backend_log,
                                        hermes_bin=cfg.hermes_bin, spawn=cfg.start_hermes)
    cli = hermes_cli or (lambda *a: _hermes_cli(cfg, *a))
    tailnet = tailnet or TailnetClient()
    devices = devices or DeviceStore(cfg.devices_file)
    trust = trust or TrustStore(cfg.trust_file)
    http = http or httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=2.0))
    allowed_logins = set(cfg.allowed_logins) or ({owner_login} if owner_login else set())
    audit = _audit_logger(cfg)
    limiter = RateLimiter()
    metrics = BridgeMetrics(__version__, PROTOCOL)
    metrics_token = read_metrics_token(cfg.metrics_token_file) if cfg.metrics else None

    def audit_event(entry: dict) -> None:
        audit.info(json.dumps({"ts": round(time.time(), 3), **entry}))

    parked = ParkedUpstreams(cfg.park_seconds, sleep=park_sleep, audit=audit_event,
                             park_max_seconds=cfg.park_max_seconds)

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        async def housekeeping() -> None:
            while True:
                await asyncio.sleep(60)
                with contextlib.suppress(Exception):
                    devices.flush_last_seen()

        task = asyncio.create_task(housekeeping())
        if monitor is not None:
            monitor.start()
        try:
            yield
        finally:
            task.cancel()
            await parked.close_all()
            if monitor is not None:
                monitor.stop()
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

    @app.exception_handler(ProfileRefused)
    async def _profile_refused(_: Request, exc: ProfileRefused):
        return _err(exc.status, "invalid_profile" if exc.status == 400 else "profile_refused", str(exc))

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
            return counted(request, start, _err(*rejected), "refused")
        request.state.peer_node = node
        if request.method in ("POST", "PATCH", "PUT"):
            length = request.headers.get("content-length")
            if length is None and request.headers.get("transfer-encoding"):
                return counted(request, start, _err(411, "length_required", "Chunked request bodies are not accepted"),
                               "refused")
            # A board attachment has its own, larger cap (checked by the upload route with a clear message).
            cap = bp_mod.MAX_UPLOAD if _UPLOAD_PATH.fullmatch(request.url.path) else cfg.max_body_bytes
            if length is not None and (not length.isdigit() or int(length) > cap):
                msg = (bp_mod.too_large_message(request.query_params.get("name") or "The file", int(length))
                       if cap == bp_mod.MAX_UPLOAD and length.isdigit() else "Request body too large")
                return counted(request, start, _err(413, "body_too_large", msg), "refused")
        try:
            response = counted(request, start, await call_next(request))
        except Exception:
            counted(request, start, JSONResponse({}, status_code=500))
            raise
        if request.url.path != "/healthz":
            device: Device | None = request.state.device
            audit_event({"peer": node, "device": device.name if device else None, "method": request.method,
                         "path": request.url.path, "status": response.status_code,
                         "ms": round((time.monotonic() - start) * 1000)})
        return response

    def counted(request: Request, start: float, response, route: str | None = None):
        """Count a REST answer under its route template (`/v1/sessions/{session_id}`), never the raw
        path: ids, file paths and query strings stay out of the metrics. Requests refused before
        routing are `refused`; paths no route serves are `unmatched`."""
        matched = request.scope.get("route")
        route = route or getattr(matched, "path", None) or "unmatched"
        metrics.http_request(route, request.method, response.status_code, time.monotonic() - start)
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

    async def metrics_auth(request: Request) -> None:
        """A paired device's token, or the local metrics token (read-only, this endpoint only)."""
        token = _bearer(request.headers.get("authorization", ""))
        if metrics_token and token and hmac.compare_digest(token.encode(), metrics_token.encode()):
            if not limiter.allow("req:metrics-token", cfg.requests_per_minute):
                raise ApiError(429, "rate_limited", "Too many requests")
            return
        await device_auth(request)

    @app.get("/metrics")
    async def get_metrics(format: str = "text", _: None = Depends(metrics_auth)):
        """Prometheus text format 0.0.4; `?format=json` is the same data for the CLI."""
        if not cfg.metrics:
            raise ApiError(404, "metrics_off", "Metrics are turned off in the bridge's config.toml")
        metrics.paired_devices.set(sum(d.active for d in await asyncio.to_thread(devices.list)))
        if format == "json":
            return metrics.registry.snapshot()
        return Response(metrics.registry.render(), media_type=METRICS_CONTENT_TYPE)

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
                # What this bridge serves beyond chat, so the app hides pages an older or
                # restricted bridge would refuse.
                "features": [f for f, on in (("monitor", monitor is not None),
                                              ("claude_usage", monitor is not None and cfg.claude_usage),
                                              ("terminal", cfg.terminal),
                                              ("speedtest", monitor is not None),
                                              ("folders", True),
                                              ("file_info", True),
                                              ("bridge_update", cfg.self_update),
                                              ("notes", cfg.notes),
                                              ("blueprints", cfg.blueprints),
                                              ("admin", cfg.remote_admin),
                                              ("parked", cfg.remote_admin)) if on],
                "bridge_version": __version__, "protocol": PROTOCOL}

    note_store = notes_mod.NoteStore(cfg.notes_dir)

    def _notes_on() -> None:
        if not cfg.notes:
            raise ApiError(404, "notes_off", "Notes are off (notes = false)")

    def _note(note_id: str):
        try:
            return note_store.get(note_id)
        except KeyError:
            raise ApiError(404, "note_not_found", "No such note")

    @app.get("/v1/notes")
    async def list_notes(q: str = "", archived: bool = False, label: str | None = None, _: Device = Depends(device_auth)):
        _notes_on()
        items = await asyncio.to_thread(note_store.list, q, archived, label)
        return {"notes": [n.to_dict() for n in items], "labels": await asyncio.to_thread(note_store.labels),
                "colors": list(notes_mod.COLORS)}

    @app.post("/v1/notes")
    async def create_note(request: Request, _: Device = Depends(device_auth)):
        _notes_on()
        body = await request.json()
        return (await asyncio.to_thread(note_store.create, **(body if isinstance(body, dict) else {}))).to_dict()

    @app.get("/v1/notes/{note_id}")
    async def get_note(note_id: str, _: Device = Depends(device_auth)):
        _notes_on()
        return _note(note_id).to_dict()

    @app.patch("/v1/notes/{note_id}")
    async def update_note(note_id: str, request: Request, _: Device = Depends(device_auth)):
        _notes_on()
        _note(note_id)
        body = await request.json()
        return (await asyncio.to_thread(note_store.update, note_id, **(body if isinstance(body, dict) else {}))).to_dict()

    @app.delete("/v1/notes/{note_id}")
    async def delete_note(note_id: str, _: Device = Depends(device_auth)):
        _notes_on()
        _note(note_id)
        await asyncio.to_thread(note_store.delete, note_id)
        return {"deleted": True}

    # ------------------------------------------------------------ Project blueprints
    # A flow diagram + task board per Hermes project, JSON files Hermes edits through the CLI. Every
    # change is a batch of ops applied to the newest copy under a lock, so phone and Hermes merge.
    bp_store = bp_mod.BlueprintStore(cfg.blueprints_dir)

    def _bp_on() -> None:
        if not cfg.blueprints:
            raise ApiError(404, "blueprints_off", "Blueprints are off (blueprints = false)")

    def _bp(pid: str) -> dict:
        try:
            # the global board settings and agent instructions ride along
            return {**bp_store.get(pid), "defaults": bp_store.defaults(), "default_instructions": bp_store.default_instructions()}
        except KeyError:
            raise ApiError(404, "no_blueprint", "This project has no blueprint")

    @app.get("/v1/blueprints")
    async def list_blueprints(_: Device = Depends(device_auth)):
        _bp_on()
        docs = await asyncio.to_thread(bp_store.all)
        return {"blueprints": [bp_mod.summary(d) for d in docs], "trash": await asyncio.to_thread(bp_store.trashed),
                "defaults": await asyncio.to_thread(bp_store.defaults),
                "default_instructions": await asyncio.to_thread(bp_store.default_instructions)}

    @app.put("/v1/blueprints-settings")
    async def blueprint_defaults(request: Request, device: Device = Depends(device_auth)):
        """Global board settings: every board that has no choice of its own follows them."""
        _bp_on()
        body = await request.json()
        body = dict(body) if isinstance(body, dict) else {}
        text = body.pop("instructions", None)  # global agent instructions (every board without its own)
        try:
            if text is not None:
                await asyncio.to_thread(bp_store.set_default_instructions, str(text))
            cur = await asyncio.to_thread(bp_store.set_defaults, body)
        except bp_mod.BlueprintError as exc:
            raise ApiError(400, "bad_setting", str(exc))
        audit_event({"event": "blueprint_defaults", "device": device.id})
        return {"defaults": cur, "default_instructions": await asyncio.to_thread(bp_store.default_instructions)}

    @app.get("/v1/blueprints/{pid}")
    async def get_blueprint(pid: str, since: int = 0, _: Device = Depends(device_auth)):
        _bp_on()
        doc = await asyncio.to_thread(_bp, pid)
        if since and doc.get("version") == since:
            return {"version": since, "unchanged": True}
        return doc

    @app.post("/v1/blueprints/{pid}")
    async def create_blueprint(pid: str, request: Request, device: Device = Depends(device_auth)):
        _bp_on()
        body = await request.json() if (await request.body()) else {}
        body = body if isinstance(body, dict) else {}
        try:
            doc = await asyncio.to_thread(bp_store.create, pid, str(body.get("name") or "")[:200], body.get("path") or None)
        except KeyError:
            raise ApiError(400, "bad_project", "Bad project id")
        except bp_mod.BlueprintError as exc:
            raise ApiError(409, "blueprint_exists", str(exc))
        audit_event({"event": "blueprint_create", "device": device.id, "project": pid})
        return doc

    @app.post("/v1/blueprints/{pid}/ops")
    async def blueprint_ops(pid: str, request: Request, _: Device = Depends(device_auth)):
        _bp_on()
        body = await request.json()
        ops = body.get("ops") if isinstance(body, dict) else None
        _bp(pid)
        try:
            doc, applied = await asyncio.to_thread(bp_store.apply, pid, ops, "phone")
        except bp_mod.BlueprintError as exc:
            raise ApiError(400, "bad_op", str(exc), doc=await asyncio.to_thread(_bp, pid))
        return {"doc": {**doc, "defaults": await asyncio.to_thread(bp_store.defaults),
                        "default_instructions": await asyncio.to_thread(bp_store.default_instructions)}, "applied": applied}

    def _bp_file(pid: str, path: str) -> str:
        target = bp_mod.comment_file(_bp(pid), path)
        if target is None:
            raise ApiError(403, "not_attached", "No comment on this board attaches that file")
        return target

    @app.post("/v1/blueprints/{pid}/upload")
    async def upload_blueprint_file(pid: str, name: str, request: Request, device: Device = Depends(device_auth)):
        """A file the phone attaches to a card (description or comment). Raw body; answers its PC path,
        which the phone then puts in a card.update `files` or card.comment `files` op."""
        _bp_on()
        await asyncio.to_thread(_bp, pid)
        size = int(request.headers.get("content-length") or 0)
        if size > bp_mod.MAX_UPLOAD:
            raise ApiError(413, "too_large", bp_mod.too_large_message(name, size))
        data = await request.body()
        try:
            path = await asyncio.to_thread(bp_store.upload, pid, name, data)
        except bp_mod.BlueprintError as exc:
            raise ApiError(413, "too_large", str(exc))
        audit_event({"event": "blueprint_upload", "device": device.id, "project": pid, "bytes": len(data)})
        return {"path": path, "name": os.path.basename(path), "size": len(data)}

    @app.get("/v1/blueprints/{pid}/file")
    async def get_blueprint_file(pid: str, path: str, _: Device = Depends(device_auth)):
        """A file a card comment attaches (a result Hermes shared), for the phone to open or save."""
        _bp_on()
        target = await asyncio.to_thread(_bp_file, pid, path)
        if not os.path.isfile(target):
            raise ApiError(404, "not_found", "File not found on the PC")
        if os.path.getsize(target) > CHAT_FILE_MAX_BYTES:
            raise ApiError(413, "too_large", "File too large to send to the phone")
        return FileResponse(target, media_type=mimetypes.guess_type(target)[0] or "application/octet-stream",
                            filename=os.path.basename(target), headers={"Cache-Control": "private, no-cache"})

    @app.get("/v1/blueprints/{pid}/file/info")
    async def get_blueprint_file_info(pid: str, path: str, _: Device = Depends(device_auth)):
        _bp_on()
        target = await asyncio.to_thread(_bp_file, pid, path)
        try:
            st = await asyncio.to_thread(os.stat, target)
        except OSError:
            return {"path": target, "name": os.path.basename(target), "exists": False}
        return {"path": target, "name": os.path.basename(target), "exists": True, "size": st.st_size,
                "modified": int(st.st_mtime), "mime": mimetypes.guess_type(target)[0] or "application/octet-stream"}

    @app.delete("/v1/blueprints/{pid}")
    async def trash_blueprint(pid: str, device: Device = Depends(device_auth)):
        _bp_on()
        _bp(pid)
        tid = await asyncio.to_thread(bp_store.trash, pid)
        audit_event({"event": "blueprint_trash", "device": device.id, "project": pid})
        return {"trashed": tid}

    @app.post("/v1/blueprints-trash/{tid}/restore")
    async def restore_blueprint(tid: str, device: Device = Depends(device_auth)):
        _bp_on()
        try:
            doc = await asyncio.to_thread(bp_store.restore, tid)
        except KeyError:
            raise ApiError(404, "not_in_trash", "Not in the trash")
        except bp_mod.BlueprintError as exc:
            raise ApiError(409, "blueprint_exists", str(exc))
        audit_event({"event": "blueprint_restore", "device": device.id, "trash": tid})
        return doc

    @app.delete("/v1/blueprints-trash/{tid}")
    async def purge_blueprint(tid: str, device: Device = Depends(device_auth)):
        _bp_on()
        try:
            await asyncio.to_thread(bp_store.purge, tid)
        except KeyError:
            raise ApiError(404, "not_in_trash", "Not in the trash")
        audit_event({"event": "blueprint_purge", "device": device.id, "trash": tid})
        return {"deleted": True}

    update_check = selfupdate.CachedCheck()

    @app.get("/v1/bridge/update")
    async def bridge_update_check(refresh: bool = False, _: Device = Depends(device_auth)):
        """This bridge's release, the newest one, and the last update's outcome."""
        if not cfg.self_update:
            raise ApiError(404, "update_off", "Bridge updates from the phone are off (self_update = false)")
        return await asyncio.to_thread(update_check.get, refresh)

    @app.post("/v1/bridge/update")
    async def bridge_update_start(device: Device = Depends(device_auth)):
        """Start the update outside the bridge; it restarts the bridge when done (pairings are kept)."""
        if not cfg.self_update:
            raise ApiError(404, "update_off", "Bridge updates from the phone are off (self_update = false)")
        info = await asyncio.to_thread(update_check.get, True)
        if not info["available"]:
            raise ApiError(409, "up_to_date", "Already up to date")
        try:
            await asyncio.to_thread(selfupdate.launch)
        except RuntimeError as exc:
            raise ApiError(409, "update_refused", str(exc))
        audit_event({"event": "bridge_update", "device": device.id, "to": info["latest"]})
        return {"started": True, "to": info["latest"]}

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

    # ------------------------------------------------------------ local control API (desktop app)
    # Loopback alone is not trusted (`tailscale serve`, other accounts and local programs reach
    # 127.0.0.1): the caller must also present the secret from the user-only local-secret file.
    # No device token, so no device rate limit; the guard middleware audits these like any request.

    async def local_auth(request: Request) -> None:
        peer = request.client.host if request.client else ""
        presented = request.headers.get(LOCAL_HEADER, "")
        if not is_loopback(peer) or not await asyncio.to_thread(
                local_secret.matches, cfg.local_secret_file, presented):
            raise ApiError(403, "forbidden_local", "Local control needs a local client with the local secret")

    local = Depends(local_auth)
    local_secret.ensure_secret(cfg.local_secret_file)  # first start creates it

    @app.get("/v1/local/status")
    async def local_status(_: None = local):
        from . import firewall

        fw = await asyncio.to_thread(firewall.state, cfg.port)
        net = await asyncio.to_thread(current_network)
        lan = await asyncio.to_thread(serving_lan_ips, cfg.lan, trust)
        return {"running": True, "version": __version__, "protocol": PROTOCOL, "port": cfg.port,
                "listen": [h for h in cfg.listen if h != "tailscale"] + await _tailnet_ips() + lan,
                "network": ({"id": net.id, "name": net.name, "trusted": net.id in trust.list()}
                            if net else None),
                "firewall_ok": not fw.needs_opening}

    @app.get("/v1/local/devices")
    async def local_devices(_: None = local):
        return await asyncio.to_thread(devices_doc, devices)

    @app.post("/v1/local/pair")
    async def local_pair(body: LocalPair, _: None = local):
        from . import cli  # the one place that knows which addresses go in a pairing code

        try:
            urls = await asyncio.to_thread(cli._bridge_urls, cfg)
        except SystemExit as exc:
            raise ApiError(409, "no_address", str(exc))
        try:
            device, token = await asyncio.to_thread(lambda: devices.pair(body.name, replace=body.replace))
        except ValueError as exc:
            raise ApiError(409, "pair_refused", str(exc))
        payload = await asyncio.to_thread(pairing_payload, device, token, urls, cfg.tls_dir)
        code = pairing_uri(payload)
        return {"device": {"id": device.id, "name": device.name}, "code": code, "url": urls[0], "token": token,
                "qr_png_base64": base64.b64encode(await asyncio.to_thread(qr_png, code)).decode()}

    @app.post("/v1/local/revoke")
    async def local_revoke(body: LocalName, _: None = local):
        try:
            device = await asyncio.to_thread(devices.revoke, body.name)
        except ValueError as exc:
            raise ApiError(404, "no_such_device", str(exc))
        return {"revoked": {"id": device.id, "name": device.name}}

    @app.get("/v1/local/trust")
    async def local_trust(_: None = local):
        return trust_doc(trust, await asyncio.to_thread(current_network))

    @app.post("/v1/local/trust")
    async def local_trust_now(_: None = local):
        net = await asyncio.to_thread(current_network)
        if net is None:
            raise ApiError(409, "no_network", "Not on a private network (or the router has not been seen yet)")
        trust.trust(net)
        return trust_doc(trust, net)

    @app.post("/v1/local/untrust")
    async def local_untrust(body: LocalNetworkId, _: None = local):
        removed = trust.untrust(body.id)
        return {**trust_doc(trust, await asyncio.to_thread(current_network)), "removed": removed}

    @app.get("/v1/local/doctor")
    async def local_doctor(_: None = local):
        from . import doctor

        return {"checks": await asyncio.to_thread(doctor.collect, cfg)}

    # ------------------------------------------------------------ PC bridge admin (from the phone)
    # What the desktop app and the CLI do, for a paired device: health checks with one-tap fixes,
    # restart, trusted networks, paired devices, the log. Nothing here can pair a new device or
    # widen access beyond trusting the network the PC is on now.

    def _admin_on() -> None:
        if not cfg.remote_admin:
            raise ApiError(404, "admin_off", "PC bridge admin from the phone is off (remote_admin = false)")

    async def _doctor_doc() -> dict:
        from . import doctor

        checks = await asyncio.to_thread(doctor.collect, cfg)
        net = await asyncio.to_thread(current_network)
        fixes = {"network": "trust" if (cfg.lan and net is not None and net.id not in trust.list()) else None,
                 "service": "restart", "hermes": "start_hermes" if cfg.start_hermes else None}
        return {"checks": [{**c, "fix": fixes.get(c["id"]) if c["level"] != "ok" else None} for c in checks],
                "version": __version__, "running": selfupdate.RUNNING_TAG, "checked_at": time.time()}

    @app.get("/v1/admin/doctor")
    async def admin_doctor(_: Device = Depends(device_auth)):
        _admin_on()
        return await _doctor_doc()

    @app.post("/v1/admin/fix/{fix}")
    async def admin_fix(fix: str, device: Device = Depends(device_auth)):
        _admin_on()
        audit_event({"event": "admin_fix", "device": device.id, "fix": fix})
        if fix == "trust":
            net = await asyncio.to_thread(current_network)
            if net is None:
                raise ApiError(409, "no_network", "Not on a private network (or the router has not been seen yet)")
            trust.trust(net)
        elif fix == "start_hermes":
            try:
                await locator.get(start=True)
            except Exception as exc:  # noqa: BLE001
                raise ApiError(502, "hermes_start_failed", str(exc))
        elif fix == "restart":
            try:
                await asyncio.to_thread(selfupdate.launch_restart)
            except RuntimeError as exc:
                raise ApiError(409, "restart_refused", str(exc))
            return {"restarting": True}
        else:
            raise ApiError(404, "no_such_fix", f"Unknown fix: {fix}")
        return await _doctor_doc()

    @app.get("/v1/admin/parked")
    async def admin_parked(_: Device = Depends(device_auth)):
        _admin_on()
        return {"parked": parked.snapshot()}

    @app.post("/v1/admin/restart")
    async def admin_restart(device: Device = Depends(device_auth)):
        _admin_on()
        audit_event({"event": "admin_restart", "device": device.id})
        try:
            await asyncio.to_thread(selfupdate.launch_restart)
        except RuntimeError as exc:
            raise ApiError(409, "restart_refused", str(exc))
        return {"restarting": True}

    @app.get("/v1/admin/trust")
    async def admin_trust(_: Device = Depends(device_auth)):
        _admin_on()
        return trust_doc(trust, await asyncio.to_thread(current_network))

    @app.post("/v1/admin/trust")
    async def admin_trust_now(device: Device = Depends(device_auth)):
        _admin_on()
        net = await asyncio.to_thread(current_network)
        if net is None:
            raise ApiError(409, "no_network", "Not on a private network (or the router has not been seen yet)")
        trust.trust(net)
        audit_event({"event": "admin_trust", "device": device.id, "network": net.name})
        return trust_doc(trust, net)

    @app.post("/v1/admin/untrust")
    async def admin_untrust(body: LocalNetworkId, device: Device = Depends(device_auth)):
        _admin_on()
        removed = trust.untrust(body.id)
        audit_event({"event": "admin_untrust", "device": device.id})
        return {**trust_doc(trust, await asyncio.to_thread(current_network)), "removed": removed}

    @app.get("/v1/admin/devices")
    async def admin_devices(device: Device = Depends(device_auth)):
        _admin_on()
        doc = await asyncio.to_thread(devices_doc, devices)
        for d in doc["devices"]:
            d["this"] = d["id"] == device.id
        return doc

    @app.post("/v1/admin/revoke")
    async def admin_revoke(body: LocalName, device: Device = Depends(device_auth)):
        _admin_on()
        target = next((d for d in devices.list() if d.name == body.name or d.id == body.name), None)
        if target is not None and target.id == device.id:
            raise ApiError(409, "revoke_self", "Use Forget this PC to unpair this phone")
        try:
            gone = await asyncio.to_thread(devices.revoke, body.name)
        except ValueError as exc:
            raise ApiError(404, "no_such_device", str(exc))
        audit_event({"event": "admin_revoke", "device": device.id, "revoked": gone.id})
        return {"revoked": {"id": gone.id, "name": gone.name}}

    @app.get("/v1/admin/logs")
    async def admin_logs(lines: int = 200, _: Device = Depends(device_auth)):
        _admin_on()
        n = max(20, min(lines, 1000))

        def read() -> str:
            import subprocess as sp
            try:
                r = sp.run(["journalctl", "--user", "-u", "hermes-remote-bridge", "-n", str(n), "--no-pager", "-o", "short-iso"],
                           capture_output=True, text=True, timeout=10)
                if r.returncode == 0 and r.stdout.strip():
                    return r.stdout
            except (OSError, sp.SubprocessError):
                pass
            from .config import STATE_DIR
            for f in (STATE_DIR / "bridge.log", selfupdate.log_file()):
                try:
                    return "\n".join(Path(f).read_text(errors="replace").splitlines()[-n:])
                except OSError:
                    continue
            return ""
        return {"text": await asyncio.to_thread(read)}

    # ------------------------------------------------------------ Hermes profiles
    # One `hermes serve` serves every profile. A REST call names the one it means with
    # `?profile=`; leaving it out (or `default`) is the default profile, as before.

    async def profile_param(profile: str | None = None, _: Device = Depends(device_auth)) -> str | None:
        # Authenticate first, so an unpaired caller gets 401 and learns nothing about profile names.
        return resolve_profile(profile, cfg.allowed_profiles)

    def scoped(profile: str | None) -> dict[str, str]:
        """Query params that point a Hermes REST call at `profile`."""
        return {"profile": profile} if profile else {}

    def cli_scoped(profile: str | None, *args: str) -> tuple[str, ...]:
        """Hermes CLI argv for `profile` (its global `-p` flag)."""
        return ("-p", profile, *args) if profile else args

    @app.get("/v1/profiles")
    async def list_profiles(_: Device = Depends(device_auth)):
        """The profiles the phone may switch to, with what the picker shows for each."""
        data = await hermes_http("GET", "/api/profiles")
        rows = [p for p in data.get("profiles", []) if isinstance(p, dict) and isinstance(p.get("name"), str)]
        return {"profiles": [
            {"name": p["name"], "display_name": p.get("display_name") or "", "description": p.get("description") or "",
             "model": p.get("model") or "", "provider": p.get("provider") or "", "is_default": bool(p.get("is_default"))}
            for p in rows if is_visible(p["name"], cfg.allowed_profiles)]}

    # ------------------------------------------------------------ sessions (the sidebar list)

    @app.get("/v1/sessions")
    async def list_sessions(limit: int = 40, offset: int = 0, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        """The desktop sidebar's own query: newest activity first, pinned rows included."""
        params = {"limit": max(1, min(limit, 100)), "offset": max(0, offset), "min_messages": 0,
                  "archived": "exclude", "order": "recent", "exclude_sources": "cron", **scoped(profile)}
        data = await hermes_http("GET", "/api/sessions", params=params)
        return {"sessions": data.get("sessions", []), "total": data.get("total"),
                "limit": data.get("limit"), "offset": data.get("offset")}

    @app.get("/v1/sessions/search")
    async def search_sessions(q: str = "", limit: int = 20, profile: str | None = Depends(profile_param),
                              _: Device = Depends(device_auth)):
        """Chats whose messages (or id) contain `q`: the desktop sidebar's own search
        (Hermes' FTS5 index, one row per conversation across compressions)."""
        query = q.strip()
        if not 1 <= len(query) <= SEARCH_QUERY_MAX:
            raise ApiError(400, "invalid_query", f"q must be 1..{SEARCH_QUERY_MAX} characters")
        limit = max(1, min(limit, SEARCH_LIMIT_MAX))
        # Over-fetch a little: archived chats are dropped here, Hermes cannot leave them out.
        params = {"q": query, "limit": min(limit + 10, 100), "exclude_sources": "cron", **scoped(profile)}
        data = await hermes_http("GET", "/api/sessions/search", params=params)
        return {"sessions": _visible_hits(data.get("results", []), limit)}

    @app.patch("/v1/sessions/{session_id}")
    async def patch_session(session_id: str, body: PatchSession, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        payload = {k: v for k, v in body.model_dump().items() if v is not None}
        if not payload:
            raise ApiError(400, "empty_patch", "Provide at least one of: title, pinned, archived")
        # Hermes takes the owning profile in the body here, not the query.
        return await hermes_http("PATCH", f"/api/sessions/{_check_id(session_id, 'session id')}",
                                 json={**payload, **scoped(profile)})

    # ------------------------------------------------------------ cron jobs
    # Read, create (a prompt on a schedule), pause, resume and run now. Editing and deleting a job,
    # and jobs with scripts or other delivery targets, stay on the PC.

    @app.get("/v1/cron/jobs")
    async def list_cron_jobs(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        return {"jobs": [_cron_job(j) for j in await cron_jobs_of(profile)]}

    async def cron_jobs_of(profile: str | None) -> list[dict]:
        # Always name the profile: without one Hermes lists every profile's jobs.
        data = await hermes_http("GET", "/api/cron/jobs", params={"profile": profile or "default"})
        rows = data if isinstance(data, list) else data.get("jobs", [])
        return [j for j in rows if isinstance(j, dict)]

    async def owned_cron_job(job_id: str, profile: str | None) -> str:
        """The job's id when it belongs to `profile`, else 404. Hermes treats the profile of a per-job
        call as a hint and falls back to searching every profile, which would let a phone reach a job
        in a profile `allowed_profiles` keeps it out of."""
        _check_id(job_id, "job id")
        if not any(j.get("id") == job_id for j in await cron_jobs_of(profile)):
            raise ApiError(404, "job_not_found", "No such cron job in this profile")
        return job_id

    @app.post("/v1/cron/jobs")
    async def create_cron_job(body: CreateCronJob, profile: str | None = Depends(profile_param),
                              device: Device = Depends(device_auth)):
        payload = {"name": body.name.strip(), "schedule": body.schedule.strip(), "prompt": body.prompt.strip(),
                   "deliver": "local", "paused": body.paused}
        job = await hermes_http("POST", "/api/cron/jobs", json=payload, params={"profile": profile or "default"})
        job = job.get("job", job) if isinstance(job, dict) else None
        audit_event({"event": "cron_create", "device": device.name, "profile": profile or "default",
                     "job": job.get("id") if isinstance(job, dict) else None})
        return {"job": _cron_job(job) if isinstance(job, dict) else None}

    @app.get("/v1/cron/jobs/{job_id}/runs")
    async def list_cron_runs(job_id: str, limit: int = 10, profile: str | None = Depends(profile_param),
                             _: Device = Depends(device_auth)):
        job_id = await owned_cron_job(job_id, profile)
        data = await hermes_http("GET", f"/api/cron/jobs/{job_id}/runs",
                                 params={"limit": max(1, min(limit, 50)), **scoped(profile)})
        rows = data.get("runs", []) if isinstance(data, dict) else data
        return {"runs": [_cron_run(r) for r in rows if isinstance(r, dict)]}

    @app.post("/v1/cron/jobs/{job_id}/{action}")
    async def cron_action(job_id: str, action: str, profile: str | None = Depends(profile_param),
                          device: Device = Depends(device_auth)):
        if action not in CRON_ACTIONS:
            raise ApiError(404, "not_found", f"Unknown cron action: {action}")
        job_id = await owned_cron_job(job_id, profile)
        job = await hermes_http("POST", f"/api/cron/jobs/{job_id}/{action}", params=scoped(profile))
        audit_event({"event": f"cron_{action}", "device": device.name, "profile": profile or "default",
                     "job": job_id})
        return {"job": _cron_job(job) if isinstance(job, dict) else None}

    # ------------------------------------------------------------ settings

    async def _approval_mode(profile: str | None) -> str:
        mode = (await cli(*cli_scoped(profile, "config", "get", "approvals.mode"))).strip().strip('"').lower()
        return mode if mode in APPROVAL_MODES else "manual"

    @app.get("/v1/settings/approvals")
    async def get_approvals(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        return {"mode": await _approval_mode(profile), "modes": list(APPROVAL_MODES)}

    @app.put("/v1/settings/approvals")
    async def set_approvals(body: SetApprovalMode, profile: str | None = Depends(profile_param),
                            device: Device = Depends(device_auth)):
        if body.mode not in APPROVAL_MODES:
            raise ApiError(400, "invalid_mode", f"mode must be one of {list(APPROVAL_MODES)}")
        before = await _approval_mode(profile)
        if before != body.mode:
            await cli(*cli_scoped(profile, "config", "set", "approvals.mode", body.mode))
        after = await _approval_mode(profile)
        if after != body.mode:
            raise ApiError(502, "not_applied", f"Hermes still reports approvals.mode={after}")
        audit_event({"event": "approval_mode_changed", "device": device.name, "profile": profile or "default",
                     "from": before, "to": after})
        return {"mode": after, "modes": list(APPROVAL_MODES)}

    @app.get("/v1/settings/model")
    async def get_default_model(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        info = await hermes_http("GET", "/api/model/info", params=scoped(profile))
        return {"model": info.get("model") or "", "provider": info.get("provider") or ""}

    @app.put("/v1/settings/model")
    async def set_default_model(body: SetDefaultModel, profile: str | None = Depends(profile_param),
                                device: Device = Depends(device_auth)):
        """The profile default for NEW chats (Hermes' own /api/model/set, scope=main).
        Open chats keep their model; they switch per session over the live connection."""
        res = await hermes_http("POST", "/api/model/set", params=scoped(profile), json={
            "scope": "main", "model": body.model, "provider": body.provider,
            "confirm_expensive_model": body.confirm_expensive_model})
        if res.get("confirm_required"):
            return {"confirm_required": True, "confirm_message": res.get("confirm_message") or "",
                    "model": body.model, "provider": body.provider}
        if res.get("ok") is False:
            raise ApiError(502, "not_applied", str(res.get("error") or res.get("detail") or "Hermes refused the model"))
        info = await hermes_http("GET", "/api/model/info", params=scoped(profile))
        audit_event({"event": "default_model_changed", "device": device.name, "profile": profile or "default",
                     "to": info.get("model")})
        return {"model": info.get("model") or body.model, "provider": info.get("provider") or body.provider}

    # ------------------------------------------------------------ media (images in a chat)

    @app.get("/v1/media")
    async def get_media(path: str, _: Device = Depends(device_auth)):
        """An image a chat shows (one the phone or the desktop attached, a screenshot, a generated
        image), by the path Hermes stored in the message. Only image files inside a served profile's
        images/screenshots/cache folders: never an arbitrary file from the PC."""
        try:
            target = await asyncio.to_thread(lambda: os.path.realpath(os.path.expanduser(path)))
        except (OSError, ValueError):
            raise ApiError(400, "invalid_path", "Invalid path")
        mime = MEDIA_TYPES.get(os.path.splitext(target)[1].lower())
        if mime is None:
            raise ApiError(415, "unsupported_media", "Not an image")
        roots = media_roots(cfg.hermes_home, cfg.allowed_profiles)
        if not any(target.startswith(root + os.sep) for root in roots):
            raise ApiError(403, "outside_media_roots", "Not one of Hermes' image folders")
        if not os.path.isfile(target):
            raise ApiError(404, "not_found", "Image not found")
        if os.path.getsize(target) > MEDIA_MAX_BYTES:
            raise ApiError(413, "too_large", "Image too large")
        return FileResponse(target, media_type=mime, headers={"Cache-Control": "private, max-age=86400"})

    @app.get("/v1/sessions/{session_id}/file")
    async def get_chat_file(session_id: str, path: str, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        """A file a chat names (a reply's `MEDIA:` line, or an `@file:` the user sent), for the
        phone to preview or save. Only paths that chat's own stored messages reference are served."""
        _check_id(session_id, "session id")
        home = profile_home(cfg.hermes_home, profile)
        target = await asyncio.to_thread(referenced_file, home, session_id, path)
        if target is None:
            raise ApiError(403, "not_in_chat", "This chat doesn't name that file")
        if not os.path.isfile(target):
            raise ApiError(404, "not_found", "File not found on the PC")
        if os.path.getsize(target) > CHAT_FILE_MAX_BYTES:
            raise ApiError(413, "too_large", "File too large to send to the phone")
        mime = mimetypes.guess_type(target)[0] or "application/octet-stream"
        return FileResponse(target, media_type=mime, filename=os.path.basename(target),
                            headers={"Cache-Control": "private, no-cache"})

    @app.get("/v1/sessions/{session_id}/file/info")
    async def get_chat_file_info(session_id: str, path: str, profile: str | None = Depends(profile_param),
                                 _: Device = Depends(device_auth)):
        """Name, full path, size, type and modified time of a file or picture the chat shows,
        without downloading it. Same rule as the download: the chat names it, or it is a picture
        in one of Hermes' image folders."""
        _check_id(session_id, "session id")
        home = profile_home(cfg.hermes_home, profile)
        target = await asyncio.to_thread(referenced_file, home, session_id, path)
        if target is None:
            try:
                real = await asyncio.to_thread(lambda: os.path.realpath(os.path.expanduser(path)))
            except (OSError, ValueError):
                raise ApiError(400, "invalid_path", "Invalid path")
            roots = media_roots(cfg.hermes_home, cfg.allowed_profiles)
            if os.path.splitext(real)[1].lower() in MEDIA_TYPES and any(real.startswith(r + os.sep) for r in roots):
                target = real
        if target is None:
            raise ApiError(403, "not_in_chat", "This chat doesn't name that file")
        try:
            st = await asyncio.to_thread(os.stat, target)
        except OSError:
            return {"path": target, "name": os.path.basename(target), "exists": False}
        return {"path": target, "name": os.path.basename(target), "exists": True, "size": st.st_size,
                "modified": int(st.st_mtime), "mime": mimetypes.guess_type(target)[0] or "application/octet-stream"}

    @app.get("/v1/projects/{project_id}/idea")
    async def get_project_idea(project_id: str, profile: str | None = Depends(profile_param),
                               _: Device = Depends(device_auth)):
        """The project's IDEA.md (its description, as the desktop's "New project" writes it)."""
        _check_id(project_id, "project id")
        try:
            return await asyncio.to_thread(read_idea, profile_home(cfg.hermes_home, profile), project_id)
        except NoFolder as exc:
            raise ApiError(404, "no_folder", str(exc))

    @app.put("/v1/projects/{project_id}/idea")
    async def put_project_idea(project_id: str, body: ProjectIdea, profile: str | None = Depends(profile_param),
                               _: Device = Depends(device_auth)):
        """Write (or, when empty, remove) IDEA.md in the project's own folder; no other path is writable."""
        _check_id(project_id, "project id")
        try:
            return await asyncio.to_thread(write_idea, profile_home(cfg.hermes_home, profile), project_id, body.text)
        except NoFolder as exc:
            raise ApiError(404, "no_folder", str(exc))
        except OSError as exc:
            raise ApiError(500, "write_failed", f"Could not write IDEA.md: {exc.strerror or exc}")

    @app.get("/v1/folders")
    async def get_folders(path: str = "", hidden: bool = False, _: Device = Depends(device_auth)):
        """Sub-folders of a PC path (home when empty), for the project folder picker. Names only, no files."""
        try:
            return await asyncio.to_thread(list_dirs, path[:4096], hidden)
        except FolderError as exc:
            raise ApiError(exc.status, exc.code, exc.message)

    @app.post("/v1/folders")
    async def post_folder(body: MakeFolder, device: Device = Depends(device_auth)):
        """Create one new folder (the picker's "New folder"); its parent must exist."""
        try:
            out = await asyncio.to_thread(make_dir, body.path)
        except FolderError as exc:
            raise ApiError(exc.status, exc.code, exc.message)
        audit_event({"event": "folder_create", "device": device.name, "path": out["path"]})
        return out

    # ------------------------------------------------------------ the live connection

    # ------------------------------------------------------------ PC monitor + Claude usage

    def need_monitor() -> Monitor:
        if monitor is None:
            raise ApiError(404, "monitor_off", "The PC monitor is turned off in the bridge's config.toml")
        return monitor

    async def off_loop(fn, *args):
        return await asyncio.get_running_loop().run_in_executor(None, fn, *args)

    @app.get("/v1/monitor/now")
    async def monitor_now(live: bool = False, _: Device = Depends(device_auth)):
        return await off_loop(need_monitor().now, live)

    @app.get("/v1/monitor/history")
    async def monitor_history(range: str = "1h", _: Device = Depends(device_auth)):  # noqa: A002 - query name
        if range not in RANGES:
            raise ApiError(400, "invalid_range", f"range must be one of {', '.join(RANGES)}")
        return await off_loop(need_monitor().history, range)

    @app.get("/v1/monitor/processes")
    async def monitor_processes(sort: str = "cpu", limit: int = 80, q: str = "", _: Device = Depends(device_auth)):
        return await off_loop(need_monitor().processes, sort, limit, q[:100])

    @app.post("/v1/monitor/processes/{pid}/stop")
    async def stop_process(pid: int, force: bool = False, device: Device = Depends(device_auth)):
        m = need_monitor()
        try:
            result = await off_loop(m.signal, pid, force)
        except PermissionError as exc:
            raise ApiError(403, "forbidden_process", str(exc) or "Not allowed to stop that process") from exc
        except Exception as exc:  # psutil.NoSuchProcess / AccessDenied
            name = type(exc).__name__
            if name == "NoSuchProcess":
                raise ApiError(404, "no_such_process", f"No process {pid}") from exc
            if name == "AccessDenied":
                raise ApiError(403, "forbidden_process", "That process belongs to another user") from exc
            raise
        audit_event({"event": "process_stop", "device": device.name, "pid": pid, "name": result["name"], "force": force})
        return result

    @app.post("/v1/monitor/speedtest")
    async def run_speed_test(device: Device = Depends(device_auth)):
        m = need_monitor()
        if not limiter.allow(f"speed:{device.id}", 6):
            raise ApiError(429, "rate_limited", "Too many speed tests; wait a minute")
        try:
            result = await off_loop(m.speed_test)
        except RuntimeError as exc:
            raise ApiError(409, "speedtest_busy", str(exc))
        except OSError as exc:
            raise ApiError(502, "speedtest_failed", f"Speed test failed: {getattr(exc, 'reason', None) or exc}")
        audit_event({"event": "speed_test", "device": device.name, "down": result["down_mbps"], "up": result["up_mbps"]})
        return result

    @app.get("/v1/monitor/tracking")
    async def monitor_tracking(_: Device = Depends(device_auth)):
        m = need_monitor()
        return {"tracking": dict(m.tracking), "labels": dict(TRACKING)}

    @app.put("/v1/monitor/tracking")
    async def set_monitor_tracking(request: Request, device: Device = Depends(device_auth)):
        """Turn monitor groups on/off (e.g. {"network": false}); off groups are not read at all."""
        m = need_monitor()
        body = await request.json()
        try:
            cur = await off_loop(m.set_tracking, body if isinstance(body, dict) else {})
        except ValueError as exc:
            raise ApiError(400, "bad_tracking", str(exc))
        audit_event({"event": "monitor_tracking", "device": device.name, "tracking": cur})
        return {"tracking": cur, "labels": dict(TRACKING)}

    @app.get("/v1/monitor/speedtest")
    async def speed_tests(limit: int = 30, _: Device = Depends(device_auth)):
        return await off_loop(need_monitor().speed_history, limit)

    @app.get("/v1/claude/usage")
    async def claude_usage(refresh: bool = False, _: Device = Depends(device_auth)):
        m = need_monitor()
        return await off_loop(m.claude_poll) if refresh else m.claude_latest()

    @app.get("/v1/claude/history")
    async def claude_history(range: str = "24h", _: Device = Depends(device_auth)):  # noqa: A002
        if range not in RANGES:
            raise ApiError(400, "invalid_range", f"range must be one of {', '.join(RANGES)}")
        return await off_loop(need_monitor().claude_history, range)

    # ------------------------------------------------------------ live sockets

    async def ws_device(ws: WebSocket, endpoint: str) -> tuple[str | None, Device | None]:
        """Same gate as HTTP: network, then the device token from the upgrade request's header."""
        peer = ws.client.host if ws.client else ""
        node, rejected = await peer_verdict(peer, ws.url.scheme == "wss")
        if rejected:
            metrics.ws_rejected.inc(endpoint=endpoint, reason="network")
            await ws.close(code=4403, reason=rejected[2])
            return None, None
        # The token rides in the Authorization header (OkHttp sets it on the upgrade request),
        # never in the URL, so it stays out of every log line.
        token = _bearer(ws.headers.get("authorization", ""))
        device = devices.authenticate(token) if token else None
        if device is None:
            audit_event({"event": "ws_unauthorized", "peer": node, "path": ws.url.path})
            metrics.ws_rejected.inc(endpoint=endpoint, reason="unauthorized")
            await ws.close(code=4401, reason="Missing, invalid or revoked device token")
            return node, None
        return node, device

    @app.websocket("/v1/terminal")
    async def terminal(ws: WebSocket, cols: int = 80, rows: int = 24):
        node, device = await ws_device(ws, "terminal")
        if device is None:
            return
        if not cfg.terminal:
            metrics.ws_rejected.inc(endpoint="terminal", reason="disabled")
            await ws.close(code=4404, reason="The terminal is turned off in the bridge's config.toml")
            return
        await ws.accept()
        shell = shell_factory(cols=max(10, min(cols, 500)), rows=max(4, min(rows, 300)))
        metrics.socket_opened("terminal")
        try:
            await serve_terminal(ws, shell, audit=lambda e: audit_event({**e, "peer": node, "device": device.name}))
        finally:
            metrics.socket_closed("terminal")

    @app.websocket("/v1/ws")
    async def live(ws: WebSocket):
        node, device = await ws_device(ws, "live")
        if device is None:
            return
        frames_key = f"frames:{device.id}:{id(ws)}"
        relay = Relay(locator, max_frame_bytes=cfg.max_frame_bytes,
                      allow_frame=lambda: limiter.allow(frames_key, cfg.frames_per_minute),
                      audit=lambda e: audit_event({**e, "peer": node}),
                      allowed_profiles=cfg.allowed_profiles, parked=parked, metrics=metrics)
        await relay.run(ws, device.name, device.id)

    app.state.locator = locator
    app.state.metrics = metrics
    return app
