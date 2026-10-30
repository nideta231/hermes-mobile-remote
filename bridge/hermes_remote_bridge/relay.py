"""The phone's live connection: a WebSocket relayed to Hermes' own JSON-RPC socket.

The desktop app renders `hermes serve`'s `/api/ws`; the phone gets the very same socket, one hop
further. Every agent event (streamed text, tools, titles, the sidebar's working state) reaches
both screens from the one process, so there is nothing to sync: a chat is live wherever it is
open. Approvals and clarify questions arrive as server→client requests on the same socket and
whichever screen answers first wins.

The bridge adds only what a socket leaving the PC needs: the device token, the network guard,
a method allowlist (the RPCs the app actually uses, not the whole desktop surface), a frame size
cap and a rate limit. Frames from Hermes are forwarded untouched.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import re
from collections.abc import Iterable
from typing import Any, Awaitable, Callable, NamedTuple

import websockets
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

from .backend import BackendLocator, BackendUnavailable
from .metrics import BridgeMetrics
from .profiles import ProfileRefused, resolve_profile

log = logging.getLogger(__name__)

# What the phone may call. Everything here is something the desktop app does from its chat view.
ALLOWED_METHODS = frozenset({
    "ping", "client.capabilities",
    # sessions
    "session.list", "session.active_list", "session.most_recent", "session.resume", "session.activate",
    "session.create", "session.close", "session.history", "session.events.since", "session.title",
    "session.delete", "session.undo", "session.branch", "session.compress", "session.usage", "session.status",
    "session.save", "session.workspace.move", "session.context_breakdown",
    # projects: the drawer's group-by-project, plus "New project", rename, folder and delete from the
    # phone (delete drops Hermes' saved project only; files on the PC are never touched)
    "projects.tree", "projects.get", "projects.create", "projects.update", "projects.delete",
    "projects.add_folder", "projects.remove_folder", "projects.set_primary",
    # turns
    "prompt.submit", "prompt.btw", "prompt.background", "session.interrupt", "session.steer", "session.redirect",
    # subagents the open chat delegated to: the roster (to catch up mid-run), stopping one, steering
    # one and reading its live transcript tail. Hermes only answers for sessions this socket is
    # attached to.
    "subagent.list", "subagent.interrupt", "subagent.steer", "subagent.tail",
    # background processes the open chat started (terminal background=true): list with output tail,
    # and stop one. Scoped by Hermes to the caller's session; the registry-wide kill stays on the PC.
    "process.list", "process.kill",
    # slash commands, models, settings
    "slash.exec", "command.dispatch", "command.resolve", "commands.catalog", "complete.slash",
    "config.get", "config.set", "model.options",
    # questions from the agent
    "approval.respond", "approval.pending", "approval.received", "clarify.lock",
    # attachments
    "image.attach_bytes", "image.detach", "file.attach",
    # the vault page: list (metadata only, never a secret), add, remove. Unlocking a password manager
    # (its master password) stays on the PC.
    "vault.list", "vault.sources", "vault.add", "vault.remove",
    # read-only state of the open chat's /loop and /heartbeat (background work signals)
    "session.control.read",
})

# config.set keys the phone may change: the chat's model, reasoning and fast mode.
ALLOWED_CONFIG_KEYS = frozenset({"model", "reasoning", "fast", "yolo"})
# Steering text is an instruction folded into a child's next tool round, not a document: a cap well
# under the frame limit keeps a pasted log from bloating the child's context.
MAX_STEER_CHARS = 4000
_SERVER_REQUEST_ID = re.compile(r"^srq-[0-9a-f]{6,32}$")

CLOSE_UNAVAILABLE = 1013  # try again later: Hermes is starting or gone
CLOSE_POLICY = 1008
CLOSE_TOO_BIG = 1009


def _error(rid: Any, code: int, message: str) -> str:
    return json.dumps({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})


class Verdict(NamedTuple):
    """What to do with one phone→Hermes frame: forward it, answer it, or drop it (and why)."""
    forward: str | None
    reply: str | None
    refused: str | None = None  # a fixed reason, for the metrics; never frame content
    frame: dict | None = None


def inspect_frame(raw: str, max_bytes: int, allowed_profiles: Iterable[str] = (),
                  refused_methods: frozenset = frozenset(), refused_config_keys: frozenset = frozenset()) -> Verdict:
    """Validate one phone→Hermes frame."""
    if len(raw.encode()) > max_bytes:
        return Verdict(None, _error(None, -32600, "Frame too large"), "too_large")
    try:
        frame = json.loads(raw)
    except ValueError:
        return Verdict(None, _error(None, -32700, "Not JSON"), "not_json")
    if not isinstance(frame, dict) or frame.get("jsonrpc") != "2.0":
        return Verdict(None, _error(None, -32600, "Not a JSON-RPC 2.0 frame"), "not_jsonrpc")
    rid = frame.get("id")
    method = frame.get("method")
    if method is None:
        # A response: only to a question Hermes asked (approval, clarify).
        if isinstance(rid, str) and _SERVER_REQUEST_ID.match(rid) and ("result" in frame or "error" in frame):
            return Verdict(raw, None, frame=frame)
        return Verdict(None, None, "stray_response")  # nothing to answer a stray response with
    if not isinstance(method, str) or method not in ALLOWED_METHODS or method in refused_methods:
        return Verdict(None, _error(rid, -32601, f"Method not available from the phone: {method}"), "method")
    params = frame.get("params")
    if isinstance(params, dict) and params.get("profile") is not None:
        try:
            resolve_profile(params["profile"] if isinstance(params["profile"], str) else "?", allowed_profiles)
        except ProfileRefused as exc:
            return Verdict(None, _error(rid, -32602, str(exc)), "profile")
    if method == "config.set":
        key = (params or {}).get("key") if isinstance(params, dict) else None
        if key not in ALLOWED_CONFIG_KEYS or key in refused_config_keys:
            return Verdict(None, _error(rid, -32602, f"config key not available from the phone: {key}"), "config_key")
    if method == "subagent.steer" and (problem := _steer_text_problem(params)):
        return Verdict(None, _error(rid, -32602, problem), "steer_text")
    return Verdict(raw, None, frame=frame)


def check_frame(raw: str, max_bytes: int, allowed_profiles: Iterable[str] = ()) -> tuple[str | None, str | None]:
    """(frame to forward, error reply to send back) for one phone→Hermes frame."""
    verdict = inspect_frame(raw, max_bytes, allowed_profiles)
    return verdict.forward, verdict.reply


def _steer_text_problem(params: Any) -> str | None:
    text = params.get("text") if isinstance(params, dict) else None
    if not isinstance(text, str) or not text.strip():
        return "subagent.steer needs text"
    if len(text) > MAX_STEER_CHARS:
        return f"steer text longer than {MAX_STEER_CHARS} characters"
    return None


class _Live:
    """A phone connection that is running now; `replaced` is set when the same device connects again."""

    def __init__(self) -> None:
        self.replaced = asyncio.Event()
        self.done = asyncio.Event()


PROBE_PREFIX = "brg-"  # ids of the bridge's own requests; replies to them are never forwarded
PROBE_INTERVAL = 30.0  # seconds between work checks on a parked socket
PROBE_TIMEOUT = 10.0  # seconds to wait for one probe reply


class _Parked:
    def __init__(self, upstream: Any, ready: str, sessions: set):
        self.upstream = upstream
        self.ready = ready
        self.sessions = sessions  # runtime session ids attached on this socket
        self.tasks: list[asyncio.Task] = []
        self.pending: dict[str, asyncio.Future] = {}
        self.seq = 0
        self.elapsed = 0.0  # seconds held, as the watch loop counts them
        self.idle = 0.0
        self.work: list[str] = []  # work kinds seen on the last probe


def _is_probe_frame(text: str) -> dict | None:
    """The parsed frame when `text` is a reply to one of the bridge's own probes."""
    if f'"{PROBE_PREFIX}' not in text:
        return None
    try:
        frame = json.loads(text)
    except ValueError:
        return None
    rid = frame.get("id") if isinstance(frame, dict) else None
    return frame if isinstance(rid, str) and rid.startswith(PROBE_PREFIX) else None


class ParkedUpstreams:
    """Hermes sockets kept open, per device, after the phone's socket dropped.

    Hermes reaps a session 20 s after its socket detaches (and never waits for a background
    process). While parked, the bridge asks Hermes every 30 s whether a session attached on the
    socket still has work (a turn, subagent, /loop, /heartbeat or running process) and keeps it
    open while so. Once none has, it is released after `park_seconds` of idle; `park_max_seconds`
    releases it regardless. A phone that comes back re-attaches to the same socket. Frames Hermes
    sends meanwhile are dropped: the phone's `session.resume` rebuilds state.
    """

    def __init__(self, park_seconds: float = 0, *, sleep: Callable[[float], Awaitable[Any]] = asyncio.sleep,
                 audit: Callable[[dict], None] = lambda e: None, park_max_seconds: float = 86400):
        self.park_seconds = park_seconds
        self.park_max_seconds = park_max_seconds
        self._sleep = sleep
        self._audit = audit
        self._parked: dict[str, _Parked] = {}
        self.live: dict[str, _Live] = {}
        self._closing: set[asyncio.Task] = set()

    @property
    def enabled(self) -> bool:
        return self.park_seconds > 0

    def park(self, device: str, upstream: Any, ready: str, sessions: Iterable[str] = ()) -> None:
        self._discard(device)
        entry = _Parked(upstream, ready, set(sessions))

        async def watch() -> None:
            # time is what the sleeps add up to, so a fake sleep is a fake clock
            while True:
                step = min(PROBE_INTERVAL, max(self.park_seconds - entry.idle, 0.0),
                           self.park_max_seconds - entry.elapsed)
                await self._sleep(step)
                entry.elapsed += step
                if entry.elapsed >= self.park_max_seconds:
                    self._audit({"event": "ws_park_cap", "device": device})
                    break
                entry.work = await self._work_kinds(entry)
                entry.idle = 0.0 if entry.work else entry.idle + step
                if entry.idle >= self.park_seconds:
                    self._audit({"event": "ws_park_expired", "device": device})
                    break
            self._discard(device)

        async def drain() -> None:
            with contextlib.suppress(Exception):
                async for raw in upstream:
                    frame = _is_probe_frame(raw if isinstance(raw, str) else raw.decode(errors="replace"))
                    waiter = entry.pending.get(frame["id"]) if frame else None
                    if waiter is not None and not waiter.done():
                        waiter.set_result(frame)
            self._discard(device)  # Hermes closed (or errored) while parked

        self._parked[device] = entry
        entry.tasks = [asyncio.create_task(watch()), asyncio.create_task(drain())]

    async def _ask(self, entry: _Parked, method: str, params: dict) -> Any:
        entry.seq += 1
        rid = f"{PROBE_PREFIX}{entry.seq}"
        waiter = entry.pending[rid] = asyncio.get_running_loop().create_future()
        try:
            await entry.upstream.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
            frame = await asyncio.wait_for(waiter, PROBE_TIMEOUT)
        finally:
            entry.pending.pop(rid, None)
        if "error" in frame:
            raise RuntimeError(f"{method} refused")
        return frame.get("result") or {}

    async def _work_kinds(self, entry: _Parked) -> list[str]:
        """The kinds of work attached sessions have (the first kind found). A failed or refused probe is `unknown`."""
        if not entry.sessions:
            return []
        try:
            listing = await self._ask(entry, "session.active_list", {})
            if any(row.get("id") in entry.sessions and row.get("status") != "idle"
                   for row in listing.get("sessions") or []):
                return ["turn"]
            for sid in list(entry.sessions):
                params = {"session_id": sid}
                if any(p.get("status") == "running"
                       for p in (await self._ask(entry, "process.list", params)).get("processes") or []):
                    return ["process"]
                if (await self._ask(entry, "subagent.list", params)).get("subagents"):
                    return ["subagent"]
                control = (await self._ask(entry, "session.control.read", params)).get("control") or {}
                for kind in ("loop", "heartbeat"):
                    if (control.get(kind) or {}).get("status") == "active":
                        return [kind]
        except Exception:  # noqa: BLE001 - never destructive: when unsure, keep the socket
            return ["unknown"]
        return []

    def snapshot(self) -> list[dict]:
        """Read-only: who is held, for how long, and why. Never any session content."""
        return [{"device": device, "held_seconds": round(e.elapsed), "sessions": len(e.sessions),
                 "work": list(e.work),
                 "releases_in": None if e.work else max(round(self.park_seconds - e.idle), 0)}
                for device, e in self._parked.items()]

    def claim(self, device: str) -> tuple[Any, str, set] | None:
        entry = self._parked.pop(device, None)
        if entry is None:
            return None
        for t in entry.tasks:
            t.cancel()
        return entry.upstream, entry.ready, entry.sessions

    def _discard(self, device: str) -> None:
        entry = self._parked.pop(device, None)
        if entry is not None:
            for t in entry.tasks:
                t.cancel()
            task = asyncio.get_running_loop().create_task(_close_quietly(entry.upstream))
            self._closing.add(task)  # keep a reference until it is done
            task.add_done_callback(self._closing.discard)

    async def close_all(self) -> None:
        entries, self._parked = list(self._parked.values()), {}
        for entry in entries:
            for t in entry.tasks:
                t.cancel()
            await _close_quietly(entry.upstream)


async def _close_quietly(upstream: Any) -> None:
    with contextlib.suppress(BaseException):
        await upstream.close()


class Relay:
    """One phone connection ↔ one Hermes connection."""

    def __init__(self, locator: BackendLocator, *, max_frame_bytes: int,
                 allow_frame: Callable[[], bool], audit: Callable[[dict], None],
                 allowed_profiles: Iterable[str] = (), parked: ParkedUpstreams | None = None,
                 metrics: BridgeMetrics | None = None,
                 refused_methods: frozenset = frozenset(), refused_config_keys: frozenset = frozenset()):
        self.refused_methods = refused_methods  # e.g. over Tailscale Funnel: the vault
        self.refused_config_keys = refused_config_keys
        self.locator = locator
        self.metrics = metrics or BridgeMetrics("", 0)
        self.max_frame_bytes = max_frame_bytes
        self.allow_frame = allow_frame
        self.audit = audit
        self.allowed_profiles = frozenset(allowed_profiles)
        self.parked = parked or ParkedUpstreams(0)

    async def run(self, phone: WebSocket, device_name: str, device_id: str | None = None) -> None:
        try:
            backend = await self.locator.get()
        except BackendUnavailable as exc:
            self.metrics.ws_rejected.inc(endpoint="live", reason="hermes_unavailable")
            await phone.close(code=CLOSE_UNAVAILABLE, reason=str(exc)[:120])
            return
        parked = self.parked
        old = parked.live.get(device_name)
        if old is not None:
            # Same device again: the old phone side ends, and its upstream is parked for us below.
            old.replaced.set()
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(old.done.wait(), 5)
        claimed = parked.claim(device_name)
        ready: str | None = None
        sessions: set = set()  # runtime session ids attached on this upstream socket
        asked: dict[Any, str] = {}  # id of a relayed session.create/resume -> its method
        if claimed is not None:
            upstream, ready, sessions = claimed
        else:
            try:
                upstream = await websockets.connect(backend.ws, max_size=None, ping_interval=20, ping_timeout=20,
                                                    open_timeout=10, close_timeout=2, compression=None)
            except (OSError, websockets.WebSocketException, asyncio.TimeoutError) as exc:
                log.warning("could not reach Hermes on port %d: %s", backend.port, exc)
                self.locator.forget(backend)
                self.metrics.ws_rejected.inc(endpoint="live", reason="hermes_unavailable")
                await phone.close(code=CLOSE_UNAVAILABLE, reason="Hermes is not answering")
                return
        await phone.accept()
        live = parked.live[device_name] = _Live()
        if claimed is not None:
            self.audit({"event": "ws_reattach", "device": device_name})
        self.audit({"event": "ws_open", "device": device_name, "backend_port": backend.port})
        self.metrics.socket_opened("live", device_id)
        stats = self.metrics.connection()
        methods: dict[str, int] = {}

        async def phone_to_hermes() -> None:
            while True:
                msg = await phone.receive()
                if msg["type"] == "websocket.disconnect":
                    return
                raw = msg.get("text")
                if raw is None and msg.get("bytes") is not None:
                    raw = msg["bytes"].decode(errors="replace")
                if raw is None:
                    continue
                if not self.allow_frame():
                    stats.refused("rate_limited")
                    await phone.send_text(_error(None, -32000, "Too many requests"))
                    continue
                verdict = inspect_frame(raw, self.max_frame_bytes, self.allowed_profiles,
                                        self.refused_methods, self.refused_config_keys)
                if verdict.refused:
                    stats.refused(verdict.refused)
                if verdict.reply:
                    await phone.send_text(verdict.reply)
                if verdict.forward:
                    method = verdict.frame.get("method")
                    if isinstance(method, str):
                        methods[method] = methods.get(method, 0) + 1
                    rid = verdict.frame.get("id")
                    if method in ("session.create", "session.resume") and isinstance(rid, (int, str)):
                        asked[rid] = method
                    elif method == "session.close":
                        sessions.discard((verdict.frame.get("params") or {}).get("session_id"))
                    stats.from_phone(verdict.frame)
                    await upstream.send(verdict.forward)

        async def hermes_to_phone() -> None:
            nonlocal ready
            if ready is not None:
                await phone.send_text(ready)  # Hermes sent its gateway.ready to the first phone only
            async for raw in upstream:
                text = raw if isinstance(raw, str) else raw.decode(errors="replace")
                if ready is None and "gateway.ready" in text and _is_ready(text):
                    ready = text
                if _is_probe_frame(text) is not None:
                    continue  # the answer to a probe sent while parked: the bridge's, not the phone's
                if asked and '"result"' in text:
                    _note_session(text, asked, sessions)
                stats.from_hermes(text)
                await phone.send_text(text)

        tasks = [asyncio.create_task(phone_to_hermes()), asyncio.create_task(hermes_to_phone()),
                 asyncio.create_task(live.replaced.wait())]
        reason = "phone left"
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                exc = t.exception()
                if t is tasks[2]:
                    reason = "replaced by a new connection"
                elif t is tasks[1]:
                    reason = "hermes closed" if exc is None else f"hermes error: {type(exc).__name__}"
                elif exc is not None and not isinstance(exc, WebSocketDisconnect):
                    reason = f"phone error: {type(exc).__name__}"
        except asyncio.CancelledError:
            reason = "bridge shutting down"
            raise
        finally:
            # Synchronous bookkeeping first: the awaits below may be cancelled (shutdown, the
            # server dropping the task), and the audit line must be written regardless.
            for t in tasks:
                t.cancel()
            if reason.startswith("hermes"):
                # The backend went away (desktop closed it, update, crash): drop the cached one so
                # the phone's reconnect attaches to, or starts, whatever runs next.
                self.locator.forget(backend)
            self.audit({"event": "ws_close", "device": device_name, "reason": reason, "methods": methods})
            keep = (parked.enabled and ready is not None
                    and not reason.startswith("hermes") and reason != "bridge shutting down")
            stats.close()
            self.metrics.socket_closed("live", device_id)
            # The reader task must have ended before the parked drain reads the same socket.
            with contextlib.suppress(BaseException):
                await asyncio.gather(*tasks, return_exceptions=True)
            if keep:
                parked.park(device_name, upstream, ready, sessions)
                self.audit({"event": "ws_park", "device": device_name})
            if parked.live.get(device_name) is live:
                del parked.live[device_name]
            live.done.set()
            with contextlib.suppress(BaseException):
                if not keep:
                    await upstream.close()
                if (phone.application_state != WebSocketState.DISCONNECTED
                        and phone.client_state != WebSocketState.DISCONNECTED):
                    await phone.close(code=1012 if reason.startswith("hermes") else 1000)


def _note_session(text: str, asked: dict, sessions: set) -> None:
    """Remember the runtime session id a relayed session.create/resume answered with."""
    try:
        frame = json.loads(text)
    except ValueError:
        return
    rid = frame.get("id") if isinstance(frame, dict) else None
    if isinstance(rid, (int, str)) and rid in asked:
        del asked[rid]
        sid = (frame.get("result") or {}).get("session_id") if isinstance(frame.get("result"), dict) else None
        if isinstance(sid, str):
            sessions.add(sid)


def _is_ready(raw: str) -> bool:
    try:
        return json.loads(raw).get("params", {}).get("type") == "gateway.ready"
    except (ValueError, AttributeError):
        return False


AuditFn = Callable[[dict], None]
GuardFn = Callable[[WebSocket], Awaitable[str | None]]
