"""PC monitor: live stats, their history, the process list and Claude plan usage.

This is the former PC Glance agent folded into the bridge, so the phone gets it over the same
authenticated connection instead of a second server with its own token. A daemon thread samples
every [SAMPLE_SECONDS] into SQLite (raw rows for 48 h, 1-minute averages for 30 days, 15-minute
averages for a year), and polls Claude's usage endpoint every [CLAUDE_POLL_SECONDS].
"""
from __future__ import annotations

import json
import logging
import os
import sqlite3
import threading
import time
import urllib.request
from pathlib import Path

import psutil

from .host import host

log = logging.getLogger(__name__)

SAMPLE_SECONDS = 10
# What the monitor reads, in groups the user can turn off to save resources (phone: PC page >
# What to track). Off = not read at all; its history shows gaps.
TRACKING = {"cpu": "CPU and load", "memory": "Memory and swap", "temp": "CPU temperature", "disk": "Disk usage and I/O",
            "network": "Network traffic", "processes": "Top processes", "claude": "Claude usage"}
CLAUDE_POLL_SECONDS = 300
RAW_KEEP = 48 * 3600
M1_KEEP = 30 * 86400
M15_KEEP = 365 * 86400
MAX_POINTS = 360
METRICS = ("cpu", "mem", "swap", "temp", "disk", "load1", "rx", "tx", "rd", "wr")
RANGES = {"1h": 3600, "6h": 6 * 3600, "24h": 86400, "7d": 7 * 86400, "30d": 30 * 86400, "1y": 365 * 86400}
SORTS = ("cpu", "mem", "name", "pid")
# Alerts fire when the threshold held for the whole window (80% of its samples present).
ALERT_TEMP_C = 90
ALERT_MEM_PCT = 95
ALERT_MINUTES = 5


def read_temp() -> float | None:
    """CPU package temperature where the OS exposes one (Linux sensors; not Windows)."""
    try:
        temps = psutil.sensors_temperatures()  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001 - absent on Windows/macOS, or no permission
        return None
    for chip in ("k10temp", "coretemp", "zenpower", "cpu_thermal", "acpitz"):
        entries = temps.get(chip)
        if not entries:
            continue
        by_label = {e.label: e.current for e in entries}
        for label in ("Tdie", "Package id 0", "Tctl", ""):
            if label in by_label:
                return round(by_label[label], 1)
        return round(entries[0].current, 1)
    return None


def _root_disk() -> str:
    return os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/"


class ClaudeUsage:
    """Claude plan usage (session and weekly limits) read with Claude Code's own login.

    The access token is only read, never refreshed: rotating the refresh token here would sign
    Claude Code out. An expired token is reported as such until Claude Code refreshes it.
    """

    URL = "https://api.anthropic.com/api/oauth/usage"

    def __init__(self, credentials: Path, opener=None) -> None:
        self.credentials = credentials
        self.latest: dict = {"ok": False, "error": "not checked yet"}
        self._open = opener or (lambda req: urllib.request.urlopen(req, timeout=20).read())

    def poll(self) -> dict:
        try:
            if not self.credentials.exists():
                raise RuntimeError("Claude Code is not signed in on this PC")
            oauth = json.loads(self.credentials.read_text())["claudeAiOauth"]
            if oauth.get("expiresAt", 0) / 1000 < time.time():
                raise RuntimeError("Claude Code login expired; open Claude Code once to refresh it")
            req = urllib.request.Request(self.URL, headers={
                "Authorization": "Bearer " + oauth["accessToken"],
                "anthropic-beta": "oauth-2025-04-20", "User-Agent": "hermes-remote-bridge"})
            data = json.loads(self._open(req))
            self.latest = parse_usage(data, oauth.get("subscriptionType"), oauth.get("rateLimitTier"))
        except Exception as exc:  # noqa: BLE001 - shown to the user, keep the last good numbers
            prev = self.latest if self.latest.get("ok") or "session" in self.latest else {}
            self.latest = dict(prev, ok=False, error=str(exc)[:200], error_ts=int(time.time()))
        return self.latest


# Per-model weekly windows of the older answer shape (shown as extra limits when present).
_LEGACY_WEEKLY = {"seven_day_opus": "Weekly · Opus", "seven_day_sonnet": "Weekly · Sonnet",
                  "seven_day_oauth_apps": "Weekly · apps", "seven_day_cowork": "Weekly · Cowork"}


def _money(amount_minor, exponent) -> float | None:
    if amount_minor is None:
        return None
    return round(amount_minor / (10 ** (exponent or 0)), 2)


def _extra(data: dict) -> dict | None:
    """Pay-as-you-go credits beyond the plan ("extra usage"), when the account has them."""
    ex = data.get("extra_usage") or {}
    spend = data.get("spend") or {}
    used = spend.get("used") or {}
    limit = spend.get("limit") or {}
    if not ex and not spend:
        return None
    return {"enabled": bool(ex.get("is_enabled") or spend.get("enabled")),
            "currency": used.get("currency") or ex.get("currency") or "USD",
            "used": _money(used.get("amount_minor"), used.get("exponent")) if used else ex.get("used_credits"),
            "limit": _money(limit.get("amount_minor"), limit.get("exponent")) if isinstance(limit, dict) and limit
            else ex.get("monthly_limit"),
            "percent": spend.get("percent") if spend.get("percent") is not None else ex.get("utilization"),
            "limit_reached": bool(ex.get("spend_limit_reached")),
            "disabled_reason": ex.get("disabled_reason") or spend.get("disabled_reason")}


def parse_usage(data: dict, plan: str | None, tier: str | None = None) -> dict:
    limits = []
    for lim in data.get("limits") or []:
        scope = lim.get("scope") or {}
        model = (scope.get("model") or {}).get("display_name") if isinstance(scope, dict) else None
        label = {"session": "Session (5h)", "weekly": "Weekly"}.get(lim.get("kind"))
        if label is None:
            label = ("Weekly" + (f" · {model}" if model else "")) if lim.get("group") == "weekly" else lim.get("kind", "?")
        limits.append({"label": label, "percent": lim.get("percent") or 0, "resets_at": lim.get("resets_at"),
                       "active": bool(lim.get("is_active")), "severity": lim.get("severity", "normal"),
                       "kind": lim.get("kind"), "group": lim.get("group")})
    five = data.get("five_hour") or {}
    seven = data.get("seven_day") or {}
    if not limits:
        # Older answer shape: only the two windows.
        if five.get("utilization") is not None:
            limits.append({"label": "Session (5h)", "percent": five["utilization"], "resets_at": five.get("resets_at"),
                           "active": True, "severity": "normal"})
        if seven.get("utilization") is not None:
            limits.append({"label": "Weekly", "percent": seven["utilization"], "resets_at": seven.get("resets_at"),
                           "active": True, "severity": "normal"})
    known = {l["label"] for l in limits}
    for key, label in _LEGACY_WEEKLY.items():
        win = data.get(key) or {}
        if win.get("utilization") is not None and label not in known:
            limits.append({"label": label, "percent": win["utilization"], "resets_at": win.get("resets_at"),
                           "active": True, "severity": "normal", "kind": key, "group": "weekly"})
    weekly_vals = [l["percent"] for l in limits if l["label"].startswith("Weekly")]
    weekly = seven.get("utilization") if seven.get("utilization") is not None else (max(weekly_vals) if weekly_vals else None)
    weekly_reset = seven.get("resets_at") or next((l["resets_at"] for l in limits if l["label"].startswith("Weekly")), None)
    return {"ok": True, "ts": int(time.time()), "plan": plan, "session": five.get("utilization"), "weekly": weekly,
            "session_resets_at": five.get("resets_at"), "weekly_resets_at": weekly_reset, "limits": limits,
            "tier": tier, "locked_reason": five.get("locked_reason"), "extra": _extra(data)}


class Monitor:
    def __init__(self, db_path: Path, claude: ClaudeUsage | None = None) -> None:
        self.db_path = db_path
        self.claude = claude
        self.latest: dict = {}
        self.cores: list[float] = []
        self._procs: dict[int, psutil.Process] = {}
        self._times: dict[int, tuple[int, float]] = {}  # pid -> (created, cpu seconds), snapshot path
        self._times_at = 0.0
        self._lock = threading.Lock()  # db + sampler state
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._conn: sqlite3.Connection | None = None
        self._prev_net = self._prev_disk = None
        self._prev_t = 0.0
        self._speed_lock = threading.Lock()  # one speed test at a time
        # PC monitor switched off (Additional features): the loop reads nothing until it is back on.
        self.paused = False
        self.tracking_path = db_path.parent / "monitor-tracking.json"
        self.tracking: dict[str, bool] = self._load_tracking()

    # ------------------------------------------------------------ what to track
    def _load_tracking(self) -> dict[str, bool]:
        try:
            saved = json.loads(self.tracking_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        return {k: bool(saved.get(k, True)) if isinstance(saved, dict) else True for k in TRACKING}

    def on(self, group: str) -> bool:
        return self.tracking.get(group, True)

    def set_tracking(self, changes: dict) -> dict[str, bool]:
        """Turn groups on/off; unknown names are an error. Saved next to the database."""
        bad = [k for k in changes if k not in TRACKING]
        if bad:
            raise ValueError(f"Unknown: {', '.join(bad)} (known: {', '.join(TRACKING)})")
        with self._lock:
            self.tracking = {**self.tracking, **{k: bool(v) for k, v in changes.items()}}
            self.tracking_path.parent.mkdir(parents=True, exist_ok=True)
            self.tracking_path.write_text(json.dumps(self.tracking), encoding="utf-8")
            if not self.on("network"):
                self._prev_net = None
            if not self.on("disk"):
                self._prev_disk = None
        return dict(self.tracking)

    # ------------------------------------------------------------ storage
    def _db(self) -> sqlite3.Connection:
        if self._conn is None:
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(self.db_path, check_same_thread=False)
            conn.execute("PRAGMA journal_mode=WAL")
            cols = ", ".join(f"{m} REAL" for m in METRICS)
            for table in ("raw", "m1", "m15"):
                conn.execute(f"CREATE TABLE IF NOT EXISTS {table} (ts INTEGER PRIMARY KEY, {cols})")
            conn.execute("CREATE TABLE IF NOT EXISTS claude (ts INTEGER PRIMARY KEY, session REAL, weekly REAL)")
            conn.execute("CREATE TABLE IF NOT EXISTS speed (ts INTEGER PRIMARY KEY, ping_ms REAL, jitter_ms REAL, "
                         "down_mbps REAL, up_mbps REAL)")
            conn.commit()
            self._conn = conn
        return self._conn

    # ------------------------------------------------------------ sampling
    def sample(self) -> dict:
        with self._lock:
            return self._sample()

    def _sample(self) -> dict:
        now = time.time()
        first = self._prev_t == 0.0
        cores = (psutil.cpu_percent(percpu=True) or [0.0]) if self.on("cpu") else []
        vm, sw = (psutil.virtual_memory(), psutil.swap_memory()) if self.on("memory") else (None, None)
        du = None
        if self.on("disk"):
            try:
                du = psutil.disk_usage(_root_disk())
            except OSError:
                du = None
        net = psutil.net_io_counters() if self.on("network") else None
        disk = psutil.disk_io_counters() if self.on("disk") else None
        dt = max(now - self._prev_t, 0.001)

        def rate(cur, prev, field):
            if first or cur is None or prev is None:
                return 0
            return max(0, round((getattr(cur, field) - getattr(prev, field)) / dt))

        load1 = None
        if self.on("cpu"):
            try:
                load1 = round(psutil.getloadavg()[0], 2)
            except (OSError, AttributeError):
                load1 = None
        net_on, disk_on = net is not None, disk is not None
        row = {"cpu": round(sum(cores) / len(cores), 1) if cores else None,
               "mem": round(vm.percent, 1) if vm else None, "swap": round(sw.percent, 1) if sw else None,
               "temp": read_temp() if self.on("temp") else None, "disk": round(du.percent, 1) if du else None, "load1": load1,
               "rx": rate(net, self._prev_net, "bytes_recv") if net_on else None, "tx": rate(net, self._prev_net, "bytes_sent") if net_on else None,
               "rd": rate(disk, self._prev_disk, "read_bytes") if disk_on else None, "wr": rate(disk, self._prev_disk, "write_bytes") if disk_on else None}
        self._prev_net, self._prev_disk, self._prev_t = net, disk, now
        self.cores = [round(c, 1) for c in cores]
        self.latest = dict(row, ts=int(now), mem_used_gb=round(vm.used / 1e9, 1) if vm else None, mem_total_gb=round(vm.total / 1e9, 1) if vm else None,
                           swap_used_gb=round(sw.used / 1e9, 1) if sw else None, swap_total_gb=round(sw.total / 1e9, 1) if sw else None,
                           disk_used_gb=round(du.used / 1e9) if du else None, disk_total_gb=round(du.total / 1e9) if du else None,
                           uptime_s=int(now - psutil.boot_time()), cores=len(cores) or psutil.cpu_count() or 0,
                           tracking=dict(self.tracking))
        if not first:  # the first sample has no rates yet; don't store zeros
            conn = self._db()
            conn.execute(f"INSERT OR REPLACE INTO raw (ts, {', '.join(METRICS)}) VALUES (?{', ?' * len(METRICS)})",
                         [int(now)] + [row[m] for m in METRICS])
            conn.commit()
        return self.latest

    def processes(self, sort: str = "cpu", limit: int = 60, query: str = "") -> dict:
        """Every process with CPU% (share of the whole machine) and resident memory.

        CPU% needs two readings, so Process objects are kept between calls; the first call after
        start shows 0% for processes it has not seen before.

        Only name, memory and CPU are read for every process. User, status and command line are
        each a separate per-process kernel query on Windows (status alone was ~11 ms x 380
        processes), so they are filled in only for the rows returned, or when a search needs the
        command line. Reading them all made /v1/monitor/now take 9-16 s on Windows.
        """
        sort = sort if sort in SORTS else "cpu"
        ncpu = psutil.cpu_count() or 1
        q = query.strip().lower()
        snapshot = host().process_snapshot()
        rows = self._rows_from_snapshot(snapshot, ncpu, q) if snapshot is not None else self._rows_from_psutil(ncpu, q)
        key = {"cpu": lambda x: (-x["cpu"], -x["mem_mb"]), "mem": lambda x: (-x["mem_mb"], -x["cpu"]),
               "name": lambda x: (x["name"].lower(), x["pid"]), "pid": lambda x: x["pid"]}[sort]
        rows.sort(key=lambda r: key(r[2]))
        out = []
        for p, cmd, row in rows[:max(1, min(limit, 500))]:
            out.append({**row, **_details(p, cmd)})
        return {"total": len(rows), "sort": sort, "processes": out}

    def _rows_from_snapshot(self, snapshot: list[dict], ncpu: int, q: str) -> list:
        """CPU% from the change in each process's CPU seconds since the previous snapshot."""
        now = time.monotonic()
        dt = now - self._times_at if self._times_at else 0.0
        rows, times = [], {}
        with self._lock:
            for s in snapshot:
                pid = s["pid"]
                if pid == 0:  # "System Idle Process": idle time, not a process
                    continue
                times[pid] = (s["created"], s["cpu_s"])
                prev = self._times.get(pid)
                cpu = 0.0
                if dt > 0 and prev and prev[0] == s["created"]:
                    cpu = max(0.0, (s["cpu_s"] - prev[1]) / dt * 100 / ncpu)
                name = s["name"] or ("System" if pid == 4 else "?")
                p = _LazyProcess(pid)
                cmd = None
                if q and q not in name.lower() and q != str(pid):
                    cmd = _cmdline(p)
                    if q not in cmd.lower():
                        continue
                rows.append((p, cmd, {"pid": pid, "name": name, "cpu": round(cpu, 1),
                                      "mem_mb": round(s["rss"] / 1048576)}))
            self._times, self._times_at = times, now
        return rows

    def _rows_from_psutil(self, ncpu: int, q: str) -> list:
        rows = []
        with self._lock:
            seen: dict[int, psutil.Process] = {}
            for p in psutil.process_iter(["name", "memory_info"]):
                proc = self._procs.get(p.pid)
                if proc is None or proc.create_time() != p.create_time():
                    proc = p
                seen[p.pid] = proc
                try:
                    cpu = proc.cpu_percent()
                    info = p.info
                    name = info.get("name") or "?"
                    cmd = None
                    if q and q not in name.lower() and q != str(p.pid):
                        cmd = _cmdline(p)
                        if q not in cmd.lower():
                            continue
                    rss = info["memory_info"].rss if info.get("memory_info") else 0
                    rows.append((p, cmd, {"pid": p.pid, "name": name, "cpu": round(cpu / ncpu, 1),
                                          "mem_mb": round(rss / 1048576)}))
                except (psutil.Error, OSError):
                    continue
            self._procs = seen
        return rows

    def top(self, n: int = 8) -> list[dict]:
        return [{k: p[k] for k in ("pid", "name", "cpu", "mem_mb")} for p in self.processes("cpu", n)["processes"]]

    def signal(self, pid: int, force: bool) -> dict:
        if pid <= 1 or pid == os.getpid() or pid == os.getppid():
            raise PermissionError("That process can't be stopped from the phone")
        proc = psutil.Process(pid)
        name = proc.name()
        proc.kill() if force else proc.terminate()
        try:
            proc.wait(timeout=3)
            alive = False
        except psutil.TimeoutExpired:
            alive = True
        return {"pid": pid, "name": name, "stopped": not alive}

    # ------------------------------------------------------------ history
    def rollup(self) -> None:
        """Average raw into 1-minute and 1-minute into 15-minute buckets, then prune."""
        now = int(time.time())
        avg = ", ".join(f"AVG({m})" for m in METRICS)
        with self._lock:
            conn = self._db()
            for src, dst, size in (("raw", "m1", 60), ("m1", "m15", 900)):
                last = conn.execute(f"SELECT COALESCE(MAX(ts), 0) FROM {dst}").fetchone()[0]
                upto = now - now % size
                conn.execute(f"INSERT OR REPLACE INTO {dst} (ts, {', '.join(METRICS)}) "
                             f"SELECT (ts / {size}) * {size} AS b, {avg} FROM {src} WHERE ts >= ? AND ts < ? GROUP BY b",
                             (last, upto))
            conn.execute("DELETE FROM raw WHERE ts < ?", (now - RAW_KEEP,))
            conn.execute("DELETE FROM m1 WHERE ts < ?", (now - M1_KEEP,))
            conn.execute("DELETE FROM m15 WHERE ts < ?", (now - M15_KEEP,))
            conn.execute("DELETE FROM claude WHERE ts < ?", (now - M15_KEEP,))
            conn.commit()

    def history(self, range_key: str) -> dict:
        span = RANGES.get(range_key, 3600)
        now = int(time.time())
        start = now - span
        table = "raw" if span <= RAW_KEEP else ("m1" if span <= M1_KEEP else "m15")
        bucket = max(span // MAX_POINTS, {"raw": SAMPLE_SECONDS, "m1": 60, "m15": 900}[table])
        sel = ", ".join(f"AVG({m}), MAX({m})" for m in METRICS)
        with self._lock:
            rows = self._db().execute(f"SELECT (ts / {bucket}) * {bucket} AS b, {sel} FROM {table} "
                                      f"WHERE ts >= ? GROUP BY b ORDER BY b", (start,)).fetchall()
        out: dict = {"range": range_key if range_key in RANGES else "1h", "start": start, "end": now, "bucket": bucket,
                     "ts": [r[0] for r in rows]}
        for i, m in enumerate(METRICS):
            out[m] = [None if r[1 + 2 * i] is None else round(r[1 + 2 * i], 2) for r in rows]
            out[m + "_max"] = [None if r[2 + 2 * i] is None else round(r[2 + 2 * i], 2) for r in rows]
        return out

    def claude_poll(self) -> dict:
        if self.claude is None:
            return {"ok": False, "error": "Claude usage is turned off on this PC"}
        latest = self.claude.poll()
        if latest.get("ok"):
            with self._lock:
                conn = self._db()
                conn.execute("INSERT OR REPLACE INTO claude VALUES (?, ?, ?)",
                             (latest["ts"], latest.get("session"), latest.get("weekly")))
                conn.commit()
        return latest

    def claude_latest(self) -> dict:
        return self.claude.latest if self.claude else {"ok": False, "error": "Claude usage is turned off on this PC"}

    def claude_history(self, range_key: str) -> dict:
        start = int(time.time()) - RANGES.get(range_key, 86400)
        with self._lock:
            rows = self._db().execute("SELECT ts, session, weekly FROM claude WHERE ts >= ? ORDER BY ts", (start,)).fetchall()
        return {"range": range_key, "start": start, "end": int(time.time()), "ts": [r[0] for r in rows],
                "session": [r[1] for r in rows], "weekly": [r[2] for r in rows]}

    # ------------------------------------------------------------ speed test
    def speed_test(self, runner=None) -> dict:
        """Run one internet speed test now and store it; refuses while another is running."""
        from . import speedtest

        if not self._speed_lock.acquire(blocking=False):
            raise RuntimeError("A speed test is already running")
        try:
            result = (runner or speedtest.run)()
        finally:
            self._speed_lock.release()
        with self._lock:
            conn = self._db()
            conn.execute("INSERT OR REPLACE INTO speed VALUES (?, ?, ?, ?, ?)",
                         (result["ts"], result["ping_ms"], result["jitter_ms"], result["down_mbps"], result["up_mbps"]))
            conn.execute("DELETE FROM speed WHERE ts < ?", (int(time.time()) - M15_KEEP,))
            conn.commit()
        return result

    def speed_history(self, limit: int = 30) -> dict:
        with self._lock:
            rows = self._db().execute("SELECT ts, ping_ms, jitter_ms, down_mbps, up_mbps FROM speed "
                                      "ORDER BY ts DESC LIMIT ?", (max(1, min(limit, 500)),)).fetchall()
        tests = [{"ts": r[0], "ping_ms": r[1], "jitter_ms": r[2], "down_mbps": r[3], "up_mbps": r[4]} for r in rows]
        return {"running": self._speed_lock.locked(), "tests": tests}

    def alerts(self) -> list[str]:
        window = int(time.time()) - ALERT_MINUTES * 60
        with self._lock:
            n, temp, mem = self._db().execute("SELECT COUNT(*), MIN(temp), MIN(mem) FROM raw WHERE ts >= ?", (window,)).fetchone()
        out = []
        if n and n >= (ALERT_MINUTES * 60 // SAMPLE_SECONDS) * 0.8:
            if temp is not None and temp >= ALERT_TEMP_C:
                out.append(f"CPU temperature above {ALERT_TEMP_C}°C for {ALERT_MINUTES} min")
            if mem is not None and mem >= ALERT_MEM_PCT:
                out.append(f"RAM above {ALERT_MEM_PCT}% for {ALERT_MINUTES} min")
        return out

    def now(self, live: bool = False) -> dict:
        stats = self.sample() if live or not self.latest else self.latest
        return {"host": _hostname(), "stats": stats, "core_pct": self.cores, "top": self.top() if self.on("processes") else [],
                "claude": self.claude_latest(), "alerts": self.alerts()}

    # ------------------------------------------------------------ background loop
    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._loop, name="monitor", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        last_rollup = 0.0
        next_claude = time.time() + 5  # not at import time: let the bridge finish starting
        while not self._stop.is_set():
            t0 = time.time()
            if self.paused:
                self._prev_net = self._prev_disk = None
                self._stop.wait(SAMPLE_SECONDS)
                continue
            try:
                self.sample()
                if t0 - last_rollup >= 60:
                    self.rollup()
                    last_rollup = t0
                if self.claude is not None and self.on("claude") and t0 >= next_claude:
                    next_claude = t0 + CLAUDE_POLL_SECONDS
                    threading.Thread(target=self.claude_poll, daemon=True).start()
            except Exception:  # noqa: BLE001 - one bad sample must not end monitoring
                log.exception("monitor sample failed")
            self._stop.wait(max(1.0, SAMPLE_SECONDS - (time.time() - t0)))


def _hostname() -> str:
    import socket

    return socket.gethostname()


class _LazyProcess:
    """A psutil.Process opened only if one of its expensive fields is actually asked for."""

    def __init__(self, pid: int) -> None:
        self.pid = pid
        self._p: psutil.Process | None = None

    def _proc(self) -> psutil.Process:
        if self._p is None:
            self._p = psutil.Process(self.pid)
        return self._p

    def cmdline(self):
        return self._proc().cmdline()

    def username(self):
        return self._proc().username()

    def status(self):
        return self._proc().status()


def _cmdline(p) -> str:
    try:
        return " ".join(p.cmdline() or [])[:300]
    except (psutil.Error, OSError):
        return ""


def _details(p, cmd: str | None) -> dict:
    """The per-process fields that are expensive on Windows; "" when the process denies them."""
    def get(fn):
        try:
            return fn() or ""
        except (psutil.Error, OSError):
            return ""
    return {"user": get(p.username).split("\\")[-1], "status": get(p.status),
            "cmd": cmd if cmd is not None else _cmdline(p)}

