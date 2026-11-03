"""Autopilot: Hermes works on its own while nobody is using it (opt-in, an Additional feature).

A Hermes cron job wakes every few minutes, but its gate script (`hermes-remote-bridge autopilot gate`)
lets the agent run only when all of these hold:

- Autopilot is switched on and not paused;
- it is inside the work window (default 23:00-06:00) OR no chat has been active for `idle_minutes`;
- optional usage guard: when the user set `session_max`/`weekly_max` below 100, the plan usage the PC monitor
  already reads must be under them;
- no other Autopilot run is still going.

"Run now" opens a manual window that skips the time/idle check (never the usage guard).

What a run may do is written down in the `autopilot` Hermes skill and enforced by Hermes itself
(cron approvals deny dangerous commands): work only inside the lab folder, install skills only from
the official hub, disable (never delete) skills nobody uses, and write every action to the journal
here, which the phone shows. Skills disabled by Autopilot are listed so the phone can undo them.

Everything lives in STATE_DIR/autopilot: settings.json, journal.jsonl, usage-cache.json, run.lock.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import sqlite3
import subprocess
import tempfile
import threading
import time
from pathlib import Path

JOB_NAME = "autopilot"
SKILL_NAME = "autopilot"
GATE_SCRIPT = "autopilot_gate.sh"
JOURNAL_KINDS = ("start", "plan", "action", "learn", "build", "skill", "result", "stop", "error", "note")
MAX_TEXT = 2000
USAGE_CACHE_SECONDS = 300
RUN_STALE_SECONDS = 2 * 3600  # a run lock older than this is considered dead

DEFAULTS: dict = {
    "enabled": False,       # mirrors the Additional features switch, kept here for the CLI/gate
    "paused": False,        # temporary stop without switching the feature off
    "window_start": "23:00",
    "window_end": "06:00",
    "idle_minutes": 45,
    "session_max": 100,     # optional guard: pause above this % of the short usage window (100 = off)
    "weekly_max": 100,      # optional guard: pause above this % of the weekly window (100 = off)
    "every_minutes": 20,    # how often the gate is checked
    "lab_dir": str(Path.home() / "autopilot-lab"),
    "focus": "",            # optional free text: what the user would like it to work on
    "manual_until": 0,      # epoch seconds: a "Run now" window
}
_INT_KEYS = {"idle_minutes": (5, 24 * 60), "session_max": (5, 100), "weekly_max": (5, 100), "every_minutes": (5, 240)}


class AutopilotError(ValueError):
    pass


def paths(state_dir: Path) -> dict[str, Path]:
    d = Path(state_dir) / "autopilot"
    return {"dir": d, "settings": d / "settings.json", "journal": d / "journal.jsonl",
            "usage": d / "usage-cache.json", "lock": d / "run.lock"}


def _atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}-")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.replace(tmp, path)


def _hhmm(value) -> str:
    try:
        h, m = str(value).split(":")
        h, m = int(h), int(m)
    except (ValueError, AttributeError):
        raise AutopilotError(f"Time must look like 23:00, not {value!r}")
    if not (0 <= h < 24 and 0 <= m < 60):
        raise AutopilotError(f"Time out of range: {value!r}")
    return f"{h:02d}:{m:02d}"


class Settings:
    def __init__(self, state_dir: Path) -> None:
        self.p = paths(state_dir)
        self._lock = threading.Lock()

    def get(self) -> dict:
        try:
            saved = json.loads(self.p["settings"].read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        out = dict(DEFAULTS)
        if isinstance(saved, dict):
            out.update({k: v for k, v in saved.items() if k in DEFAULTS})
        return out

    def set(self, changes: dict) -> dict:
        clean = {}
        for k, v in (changes or {}).items():
            if k not in DEFAULTS:
                raise AutopilotError(f"Unknown setting: {k} (known: {', '.join(DEFAULTS)})")
            if k in ("enabled", "paused"):
                if not isinstance(v, bool):
                    raise AutopilotError(f"{k} must be true or false")
            elif k in _INT_KEYS:
                if isinstance(v, bool) or not isinstance(v, (int, float)) and not str(v).isdigit():
                    raise AutopilotError(f"{k} must be a number")
                lo, hi = _INT_KEYS[k]
                v = int(v)
                if not lo <= v <= hi:
                    raise AutopilotError(f"{k} must be between {lo} and {hi}")
            elif k in ("window_start", "window_end"):
                v = _hhmm(v)
            elif k == "lab_dir":
                v = str(Path(str(v)).expanduser())
                if not Path(v).is_absolute() or Path(v) == Path.home() or len(Path(v).parts) < 3:
                    raise AutopilotError("lab_dir must be its own folder, e.g. ~/autopilot-lab")
            elif k == "focus":
                v = str(v)[:500]
            elif k == "manual_until":
                v = int(v)
            clean[k] = v
        with self._lock:
            cur = self.get()
            cur.update(clean)
            _atomic_write(self.p["settings"], json.dumps(cur, indent=2))
            return cur


# ---------------------------------------------------------------------- the gate

def in_window(now: dt.datetime, start: str, end: str) -> bool:
    s, e = _hhmm(start), _hhmm(end)
    t = now.strftime("%H:%M")
    if s == e:
        return True
    return s <= t < e if s < e else (t >= s or t < e)


def last_activity(hermes_home: Path) -> float | None:
    """Newest message of a chat a person had (cron runs, i.e. Autopilot itself, don't count)."""
    db = Path(hermes_home) / "state.db"
    if not db.exists():
        return None
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        try:
            row = con.execute(
                "select max(m.timestamp) from messages m join sessions s on s.id = m.session_id "
                "where s.source not in ('cron')").fetchone()
        finally:
            con.close()
    except sqlite3.Error:
        return None
    return float(row[0]) if row and row[0] else None


def usage_numbers(usage: dict | None) -> tuple[float | None, float | None]:
    if not usage or "session" not in usage and "weekly" not in usage:
        return None, None
    return usage.get("session"), usage.get("weekly")


def decide(settings: dict, *, now: float, last_active: float | None, usage: dict | None,
           running: bool, tz_now: dt.datetime | None = None) -> dict:
    """Pure decision: should a run start now? Returns {wake, reason, checks}."""
    tz_now = tz_now or dt.datetime.fromtimestamp(now)
    idle_min = None if last_active is None else max(0.0, (now - last_active) / 60)
    session, weekly = usage_numbers(usage)
    manual = settings.get("manual_until", 0) > now
    window = in_window(tz_now, settings["window_start"], settings["window_end"])
    idle = idle_min is None or idle_min >= settings["idle_minutes"]
    checks = {
        "enabled": bool(settings.get("enabled")), "paused": bool(settings.get("paused")),
        "manual": manual, "in_window": window, "idle_minutes": None if idle_min is None else round(idle_min, 1),
        "idle": idle, "session": session, "weekly": weekly, "running": running,
        "usage_known": session is not None or weekly is not None,
    }

    def out(wake: bool, reason: str) -> dict:
        return {"wake": wake, "reason": reason, "checks": checks}

    if not settings.get("enabled"):
        return out(False, "Autopilot is off")
    if settings.get("paused"):
        return out(False, "Paused")
    if running:
        return out(False, "A run is still going")
    if session is not None and settings["session_max"] < 100 and session >= settings["session_max"]:
        return out(False, f"Usage guard: short window at {session:.0f}% (guard {settings['session_max']}%)")
    if weekly is not None and settings["weekly_max"] < 100 and weekly >= settings["weekly_max"]:
        return out(False, f"Usage guard: weekly at {weekly:.0f}% (guard {settings['weekly_max']}%)")
    if usage is not None and usage.get("locked_reason"):
        return out(False, f"Usage locked: {usage['locked_reason']}")
    if manual:
        return out(True, "Run now (manual)")
    if window:
        return out(True, f"Work window {settings['window_start']}-{settings['window_end']}")
    if idle:
        return out(True, f"Idle for {idle_min:.0f} min" if idle_min is not None else "No chats yet")
    return out(False, f"In use ({idle_min:.0f} min ago); waits for {settings['idle_minutes']} min idle "
                      f"or {settings['window_start']}")


# ---------------------------------------------------------------------- run lock

def run_state(p: dict, now: float | None = None) -> dict | None:
    """The current run ({started, session}) or None. A lock older than RUN_STALE_SECONDS is dead."""
    now = now or time.time()
    try:
        st = json.loads(p["lock"].read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(st, dict) or now - float(st.get("started", 0)) > RUN_STALE_SECONDS:
        return None
    return st


def begin_run(p: dict, session: str | None = None) -> dict:
    st = {"started": time.time(), "session": session}
    _atomic_write(p["lock"], json.dumps(st))
    return st


def end_run(p: dict) -> None:
    try:
        p["lock"].unlink()
    except FileNotFoundError:
        pass


# ---------------------------------------------------------------------- journal

def log(p: dict, kind: str, text: str, *, title: str = "", files: list[str] | None = None,
        session: str | None = None, data: dict | None = None) -> dict:
    if kind not in JOURNAL_KINDS:
        raise AutopilotError(f"kind must be one of {', '.join(JOURNAL_KINDS)}")
    text = (text or "").strip()
    if not text and not title:
        raise AutopilotError("Write what happened")
    entry = {"ts": round(time.time(), 3), "kind": kind, "title": title.strip()[:200], "text": text[:MAX_TEXT]}
    if files:
        entry["files"] = [str(f)[:500] for f in files][:20]
    if session:
        entry["session"] = session
    if data:
        entry["data"] = data
    p["journal"].parent.mkdir(parents=True, exist_ok=True)
    with open(p["journal"], "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return entry


def journal(p: dict, *, limit: int = 100, since: float = 0, kinds: list[str] | None = None) -> list[dict]:
    """Newest first."""
    try:
        lines = p["journal"].read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out = []
    for line in reversed(lines):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("ts", 0) <= since:
            break
        if kinds and e.get("kind") not in kinds:
            continue
        out.append(e)
        if len(out) >= limit:
            break
    return out


def stats(p: dict, now: float | None = None) -> dict:
    now = now or time.time()
    entries = journal(p, limit=5000)
    day = [e for e in entries if now - e["ts"] < 86400]
    return {"total": len(entries), "today": len(day),
            "runs_today": sum(e["kind"] == "start" for e in day),
            "built_today": sum(e["kind"] == "build" for e in day),
            "learned_today": sum(e["kind"] == "learn" for e in day),
            "skills_today": sum(e["kind"] == "skill" for e in day),
            "last": entries[0] if entries else None}


# ---------------------------------------------------------------------- plan usage (own cache, guard only)

def cached_usage(p: dict, fetch, now: float | None = None) -> dict | None:
    """Usage at most USAGE_CACHE_SECONDS old; `fetch()` returns parse_usage() output or raises."""
    now = now or time.time()
    try:
        c = json.loads(p["usage"].read_text(encoding="utf-8"))
        if now - c.get("_at", 0) < USAGE_CACHE_SECONDS:
            return c
    except (OSError, ValueError):
        c = None
    try:
        u = fetch()
    except Exception:  # noqa: BLE001 - no number = gate by time/idle only
        return c
    if not u or not u.get("ok"):
        return c
    u = dict(u, _at=now)
    _atomic_write(p["usage"], json.dumps(u))
    return u


# ---------------------------------------------------------------------- skills Autopilot switched off

def _hermes(hermes_bin: Path, *args: str, timeout: int = 60) -> subprocess.CompletedProcess:
    return subprocess.run([str(hermes_bin), *args], capture_output=True, text=True, timeout=timeout,
                          stdin=subprocess.DEVNULL)


PROTECTED_SKILLS = frozenset({SKILL_NAME, "project-blueprint", "personal-notes", "hermes-agent"})


def disabled_by_autopilot(p: dict) -> list[dict]:
    """Skills Autopilot disabled and nobody re-enabled since (from the journal), newest first."""
    seen: dict[str, dict] = {}
    for e in reversed(journal(p, limit=5000)):
        d = e.get("data") or {}
        if e.get("kind") == "skill" and d.get("skill"):
            if d.get("op") == "disable":
                seen[d["skill"]] = {"skill": d["skill"], "ts": e["ts"], "why": e.get("text", "")}
            elif d.get("op") == "enable":
                seen.pop(d["skill"], None)
    return sorted(seen.values(), key=lambda x: -x["ts"])


def cli_toggle(hermes_bin: Path):
    """Skill switch through Hermes' own config writer (for the CLI, outside the bridge's sandbox)."""
    from .extras import disabled_skills

    def toggle(name: str, enable: bool) -> None:
        cur = disabled_skills(hermes_bin, None)
        new = sorted(set(cur) - {name}) if enable else sorted(set(cur) | {name})
        if new != sorted(cur):
            r = _hermes(hermes_bin, "config", "set", "--force", "skills.disabled", json.dumps(new), timeout=30)
            if r.returncode != 0:
                raise AutopilotError((r.stderr or r.stdout).strip()[-300:] or "hermes config set failed")
    return toggle


def check_skill(name: str, enable: bool, why: str) -> str:
    name = (name or "").strip()
    if not name or "/" in name or len(name) > 100:
        raise AutopilotError("Give the skill's name")
    if not enable and name in PROTECTED_SKILLS:
        raise AutopilotError(f"{name} is needed and can't be disabled")
    if not enable and not (why or "").strip():
        raise AutopilotError("Say why the skill is not useful")
    return name


def set_skill(p: dict, toggle, name: str, enable: bool, why: str, *, by: str = "autopilot") -> dict:
    """Disable (or re-enable) one skill; never deletes anything. `toggle(name, enable)` does the switch."""
    name = check_skill(name, enable, why)
    toggle(name, enable)
    return log(p, "skill", why.strip() or ("Re-enabled" if enable else "Disabled"),
               title=f"{'Enabled' if enable else 'Disabled'} skill {name}",
               data={"op": "enable" if enable else "disable", "skill": name, "by": by})


def skill_usage(hermes_home: Path) -> list[dict]:
    """Every skill Hermes tracks: name, uses, last used, state, who made it (agent/installed/bundled)."""
    try:
        data = json.loads((Path(hermes_home) / "skills" / ".usage.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    out = []
    for name, u in (data.items() if isinstance(data, dict) else []):
        if isinstance(u, dict):
            out.append({"name": name, "uses": u.get("use_count", 0), "last_used": u.get("last_used_at"),
                        "state": u.get("state"), "created_by": u.get("created_by"), "pinned": bool(u.get("pinned"))})
    return sorted(out, key=lambda s: (-(s["uses"] or 0), s["name"]))


# ---------------------------------------------------------------------- lab

def lab_projects(lab_dir: Path, limit: int = 50) -> list[dict]:
    """Folders in the lab, newest first, with their README's first line."""
    d = Path(lab_dir)
    if not d.is_dir():
        return []
    out = []
    for f in d.iterdir():
        if not f.is_dir() or f.name.startswith("."):
            continue
        summary = ""
        for readme in ("README.md", "readme.md", "NOTES.md"):
            r = f / readme
            if r.is_file():
                for line in r.read_text(encoding="utf-8", errors="replace").splitlines():
                    line = line.strip().lstrip("#").strip()
                    if line:
                        summary = line[:200]
                        break
                break
        try:
            mtime = max((x.stat().st_mtime for x in f.rglob("*") if x.is_file() and ".git" not in x.parts),
                        default=f.stat().st_mtime)
        except OSError:
            mtime = 0
        out.append({"name": f.name, "summary": summary, "updated": mtime})
    return sorted(out, key=lambda x: -x["updated"])[:limit]


_TEXT_SUFFIXES = {".md", ".txt", ".py", ".kt", ".js", ".ts", ".html", ".css", ".json", ".toml", ".yaml", ".yml", ".sh", ""}


def lab_project(lab_dir: Path, name: str) -> dict:
    """Files (max 300) and README of one lab project. Only inside the lab; no symlinks out."""
    if not name or "/" in name or "\\" in name or name.startswith("."):
        raise AutopilotError("Bad project name")
    root = Path(lab_dir).resolve()
    d = (root / name).resolve()
    if d.parent != root or not d.is_dir():
        raise AutopilotError("No such lab project")
    files = []
    for f in sorted(d.rglob("*")):
        if len(files) >= 300:
            break
        if f.is_file() and not any(part.startswith(".") or part in ("node_modules", "build", "__pycache__")
                                   for part in f.relative_to(d).parts):
            files.append({"path": str(f.relative_to(d)), "size": f.stat().st_size})
    readme = ""
    for r in ("README.md", "readme.md", "NOTES.md"):
        if (d / r).is_file():
            readme = (d / r).read_text(encoding="utf-8", errors="replace")[:20000]
            break
    return {"name": name, "files": files, "readme": readme}


LAB_FILE_MAX = 200_000


def lab_file(lab_dir: Path, project: str, rel: str) -> dict:
    """One text file of a lab project (max LAB_FILE_MAX bytes). Never outside the project, never dotfiles."""
    if not project or "/" in project or project.startswith("."):
        raise AutopilotError("Bad project name")
    root = (Path(lab_dir) / project).resolve()
    if root.parent != Path(lab_dir).resolve() or not root.is_dir():
        raise AutopilotError("No such lab project")
    rel = (rel or "").strip().lstrip("/")
    f = (root / rel).resolve()
    try:
        parts = f.relative_to(root).parts
    except ValueError:
        raise AutopilotError("Outside the project")
    if not parts or any(p.startswith(".") for p in parts) or not f.is_file():
        raise AutopilotError("No such file")
    data = f.read_bytes()[:LAB_FILE_MAX]
    if b"\x00" in data[:4096]:
        raise AutopilotError("Not a text file")
    return {"path": "/".join(parts), "size": f.stat().st_size, "truncated": f.stat().st_size > LAB_FILE_MAX,
            "text": data.decode("utf-8", errors="replace")}


# ---------------------------------------------------------------------- missions (tasks the user queues)

MISSION_STATES = ("queued", "active", "done", "dropped")
MISSION_MAX = 50


def _missions_path(p: dict) -> Path:
    return p["dir"] / "missions.json"


def missions(p: dict) -> list[dict]:
    try:
        rows = json.loads(_missions_path(p).read_text(encoding="utf-8"))
        return [r for r in rows if isinstance(r, dict)] if isinstance(rows, list) else []
    except (OSError, ValueError):
        return []


def _save_missions(p: dict, rows: list[dict]) -> None:
    _atomic_write(_missions_path(p), json.dumps(rows, indent=1, ensure_ascii=False))


_missions_lock = threading.Lock()


def add_mission(p: dict, text: str, by: str = "phone") -> dict:
    text = (text or "").strip()
    if not text:
        raise AutopilotError("Describe the mission")
    if len(text) > 2000:
        raise AutopilotError("Keep a mission under 2000 characters")
    with _missions_lock:
        rows = missions(p)
        if sum(r.get("state") in ("queued", "active") for r in rows) >= MISSION_MAX:
            raise AutopilotError(f"At most {MISSION_MAX} open missions")
        m = {"id": f"m{int(time.time() * 1000):x}", "text": text, "state": "queued", "by": by,
             "created": round(time.time(), 3), "updated": round(time.time(), 3), "result": ""}
        rows.append(m)
        _save_missions(p, rows)
    log(p, "note", text[:300], title="Mission queued", data={"mission": m["id"], "by": by})
    return m


def set_mission(p: dict, mid: str, state: str, result: str = "") -> dict:
    if state not in MISSION_STATES:
        raise AutopilotError(f"state must be one of {', '.join(MISSION_STATES)}")
    with _missions_lock:
        rows = missions(p)
        m = next((r for r in rows if r.get("id") == mid), None)
        if m is None:
            raise AutopilotError("No such mission")
        m["state"], m["updated"] = state, round(time.time(), 3)
        if result:
            m["result"] = result.strip()[:2000]
        _save_missions(p, rows)
    if state in ("done", "dropped"):
        log(p, "result" if state == "done" else "note", (result or m["text"])[:600],
            title=f"Mission {'done' if state == 'done' else 'dropped'}: {m['text'][:80]}", data={"mission": mid})
    return m


def next_mission(p: dict) -> dict | None:
    """The active mission, else the oldest queued one."""
    rows = missions(p)
    return next((r for r in rows if r.get("state") == "active"), None) or \
        next((r for r in rows if r.get("state") == "queued"), None)


# ---------------------------------------------------------------------- Guardian (audits finished runs)

_RM = re.compile(r"\brm\s+((?:-[a-zA-Z]+\s+)+)([^;&|\n)'\"]*)")
_SEVERE = [
    (re.compile(r"\bgit\s+push\b"), "git push"),
    (re.compile(r"\bgit\s+reset\s+--hard\b"), "git reset --hard"),
    (re.compile(r"(^|[;&|\s])sudo\s"), "sudo"),
    (re.compile(r"\bsystemctl\s+(--user\s+)?(stop|disable|mask|restart)\b"), "service change"),
    (re.compile(r"\bpkexec\b"), "pkexec"),
]
_WRITE_TOOLS = {"write_file", "patch"}


_QUOTED = re.compile(r"'[^']*'|\"(?:\\.|[^\"\\])*\"")
_JOURNAL_CMD = re.compile(r"(\$B|\$\{B\}|hermes-remote-bridge|hermes_remote_bridge\.cli)\s+autopilot\s+(log|end|mission)\b")


def strip_journal_text(cmd: str) -> str:
    """Blank the quoted text given to `autopilot log/end/mission`: it is prose about the work ("…before a git
    push…"), not a command. Quoted text anywhere else is kept, so `bash -c "git push"` is still seen."""
    out, last = [], 0
    for m in _QUOTED.finditer(cmd):
        prefix = "".join(out) + cmd[last:m.start()]
        seg = re.split(r"\n|;|&&|\|\|", prefix)[-1]
        out.append(cmd[last:m.start()])
        out.append('""' if _JOURNAL_CMD.search(seg) else m.group(0))
        last = m.end()
    out.append(cmd[last:])
    return "".join(out)


def risky_rm_targets(targets: str, lab: str) -> list[str]:
    """Targets of a recursive `rm` that could hit something outside the lab. Relative paths (the run works
    inside its lab) and temp folders ($T from mktemp, /tmp, $TMPDIR) are fine."""
    bad = []
    for t in targets.split():
        t = t.strip("'\"")
        if not t or t.startswith("-"):
            continue
        if t in ("/", "*", ".", "..", "~") or t.startswith(("~", "$HOME", "${HOME}", "..")) or "/.." in t or t.endswith("/*") and t.startswith("/"):
            bad.append(t)
        elif t.startswith("/") and not (t.replace("\\", "/").startswith(lab) or t.startswith(("/tmp/", "/var/tmp/"))):
            bad.append(t)
    return bad


def _tool_calls(hermes_home: Path, session_id: str) -> list[tuple[str, dict]]:
    db = Path(hermes_home) / "state.db"
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        try:
            rows = con.execute("select tool_calls from messages where session_id = ? and tool_calls is not null order by id",
                               (session_id,)).fetchall()
        finally:
            con.close()
    except sqlite3.Error:
        return []
    out = []
    for (raw,) in rows:
        try:
            calls = json.loads(raw)
        except ValueError:
            continue
        for c in calls if isinstance(calls, list) else []:
            fn = (c or {}).get("function") or {}
            try:
                args = json.loads(fn.get("arguments") or "{}")
            except ValueError:
                args = {"raw": fn.get("arguments")}
            out.append((fn.get("name") or "?", args if isinstance(args, dict) else {"raw": args}))
    return out


def audit_session(hermes_home: Path, session_id: str, lab_dir: Path, watched: list[Path], own_skills: set[str]) -> dict:
    """What a finished run did that it should not have. {severe: [...], warnings: [...]}."""
    def norm(p) -> str:
        return str(p).replace("\\", "/")

    lab = norm(Path(lab_dir).resolve())
    roots = [norm(Path(w).expanduser()) for w in watched]
    severe, warn = [], []

    def strings(v):
        if isinstance(v, str):
            yield v
        elif isinstance(v, dict):
            for x in v.values():
                yield from strings(x)
        elif isinstance(v, list):
            for x in v:
                yield from strings(x)

    def outside(args: dict) -> list[str]:
        hits = []
        for text in strings(args):
            t = norm(text)
            for r in roots:
                for m in re.finditer(re.escape(r) + r"[^\s'\"]*", t):
                    if not m.group(0).startswith(lab):
                        hits.append(m.group(0))
        return hits

    for name, args in _tool_calls(hermes_home, session_id):
        if name in ("terminal", "execute_code"):
            cmd = strip_journal_text(str(args.get("command") or args.get("code") or ""))
            for rx, label in _SEVERE:
                if rx.search(cmd):
                    severe.append(f"{label}: {cmd[:160]}")
            for m in _RM.finditer(cmd):
                if "r" in m.group(1).lower() and (bad := risky_rm_targets(m.group(2), lab)):
                    severe.append(f"recursive delete of {', '.join(bad)[:120]}")
        if name in _WRITE_TOOLS:
            path = str(args.get("path") or "")
            if path and not norm(Path(path).expanduser().resolve()).startswith(lab):
                severe.append(f"wrote outside the lab: {path}")
        if name == "skill_manage":
            for op in args.get("operations") or []:
                sk = (op or {}).get("name")
                if sk and sk not in own_skills and sk != SKILL_NAME:
                    severe.append(f"edited skill it does not own: {sk}")
        for p in outside(args)[:3]:
            warn.append(f"{name} touched {p}")
    return {"severe": sorted(set(severe)), "warnings": sorted(set(warn))[:20]}


def _step_summary(name: str, args: dict) -> str:
    """One short line for a tool call, for the live view (never the full payload)."""
    for k in ("command", "path", "query", "url", "name", "code", "goal"):
        v = args.get(k)
        if isinstance(v, str) and v.strip():
            return " ".join(v.split())[:160]
    return ""


def live_steps(hermes_home: Path, job_id: str | None, after: int = 0, limit: int = 60) -> dict:
    """The newest Autopilot session's steps after message id `after`: what Viss is doing right now.
    {session, steps: [{id, ts, role, tool, text}], cursor}. Read only."""
    out = {"session": None, "steps": [], "cursor": after}
    if not job_id:
        return out
    db = Path(hermes_home) / "state.db"
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        try:
            row = con.execute("select id from sessions where id like ? order by started_at desc limit 1",
                              (f"cron_{job_id}_%",)).fetchone()
            if not row:
                return out
            out["session"] = row[0]
            rows = con.execute("select id, timestamp, role, content, tool_calls from messages "
                               "where session_id = ? and id > ? order by id limit ?", (row[0], after, limit)).fetchall()
        finally:
            con.close()
    except sqlite3.Error:
        return out
    for mid, ts, role, content, raw in rows:
        out["cursor"] = max(out["cursor"], mid)
        if role == "assistant" and raw:
            try:
                calls = json.loads(raw)
            except ValueError:
                calls = []
            for c in calls if isinstance(calls, list) else []:
                fn = (c or {}).get("function") or {}
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except ValueError:
                    args = {}
                out["steps"].append({"id": mid, "ts": ts, "role": "tool", "tool": fn.get("name") or "?",
                                     "text": _step_summary(fn.get("name") or "", args if isinstance(args, dict) else {})})
        if role == "assistant" and isinstance(content, str) and content.strip():
            out["steps"].append({"id": mid, "ts": ts, "role": "say", "tool": None, "text": " ".join(content.split())[:400]})
    return out


def own_skills(p: dict) -> set[str]:
    """Skills Autopilot created (journaled as a skill entry with op=create)."""
    return {(e.get("data") or {}).get("skill") for e in journal(p, limit=5000)
            if e.get("kind") == "skill" and (e.get("data") or {}).get("op") == "create"} - {None}


def guardian(state_dir: Path, hermes_home: Path, job_id: str | None, watched: list[Path]) -> list[dict]:
    """Audit every Autopilot session not audited yet; journal findings; pause on a severe one."""
    if not job_id:
        return []
    p = paths(state_dir)
    seen_path = p["dir"] / "audited.json"
    try:
        seen = set(json.loads(seen_path.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        seen = set()
    db = Path(hermes_home) / "state.db"
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        try:
            ids = [r[0] for r in con.execute("select id from sessions where id like ? order by started_at", (f"cron_{job_id}_%",))]
        finally:
            con.close()
    except sqlite3.Error:
        return []
    st = Settings(state_dir).get()
    found = []
    owned = own_skills(p)
    for sid in ids:
        if sid in seen:
            continue
        if run_state(p) and sid == ids[-1]:
            continue  # still running
        r = audit_session(hermes_home, sid, Path(st["lab_dir"]), watched, owned)
        seen.add(sid)
        if r["severe"]:
            Settings(state_dir).set({"paused": True})
            log(p, "error", "; ".join(r["severe"])[:1500], title="Guardian: rule broken, Autopilot paused",
                data={"session": sid, **r})
        elif r["warnings"]:
            log(p, "note", "; ".join(r["warnings"])[:1500], title="Guardian: looked outside its lab", data={"session": sid, **r})
        found.append({"session": sid, **r})
    _atomic_write(seen_path, json.dumps(sorted(seen)))
    return found


# ---------------------------------------------------------------------- the cron job

GATE_TEMPLATE = """#!/bin/bash
# Autopilot gate (written by hermes-remote-bridge). Prints {{"wakeAgent": false}} unless a run should start.
exec "{python}" -m hermes_remote_bridge.cli autopilot gate
"""

PROMPT = (
    "You are running as Autopilot: unattended, nobody is watching. Load the `autopilot` skill and "
    "follow it exactly. The gate output above says why you were woken, the limits, and the lab folder."
)


def gate_script_path(hermes_home: Path) -> Path:
    return Path(hermes_home) / "scripts" / GATE_SCRIPT


def write_gate_script(hermes_home: Path, python: str) -> Path:
    path = gate_script_path(hermes_home)
    _atomic_write(path, GATE_TEMPLATE.format(python=python))
    path.chmod(0o755)
    return path


def job_payload(settings: dict) -> dict:
    return {"name": JOB_NAME, "schedule": f"every {settings['every_minutes']}m", "prompt": PROMPT,
            "skills": [SKILL_NAME], "script": GATE_SCRIPT, "deliver": "local"}


def gate(state_dir: Path, hermes_home: Path, *, enabled: bool, fetch_usage, now: float | None = None,
         watched: list[Path] | None = None) -> tuple[bool, str]:
    """What the cron gate prints. Starting a run takes the run lock and journals the start."""
    now = now or time.time()
    p = paths(state_dir)
    st = Settings(state_dir).get()
    st["enabled"] = enabled
    if not enabled:  # off means off: not even a usage poll
        return False, json.dumps({"wakeAgent": False, "reason": "Autopilot is off"})
    job = find_job(hermes_home)
    try:
        guardian(state_dir, hermes_home, job.get("id") if job else None, watched or [Path.home() / "Workspace", Path.home() / "Documents"])
    except Exception:  # noqa: BLE001 - an audit failure must not take the gate down
        pass
    st = Settings(state_dir).get()
    st["enabled"] = enabled
    usage = cached_usage(p, fetch_usage, now) if st["session_max"] < 100 or st["weekly_max"] < 100 else None
    d = decide(st, now=now, last_active=last_activity(hermes_home), usage=usage, running=run_state(p, now) is not None)
    if not d["wake"]:
        return False, json.dumps({"wakeAgent": False, "reason": d["reason"]})
    Path(st["lab_dir"]).mkdir(parents=True, exist_ok=True)
    begin_run(p)
    log(p, "start", d["reason"], title="Woke up", data={"checks": d["checks"]})
    c = d["checks"]
    recent = [f"- {e['kind']}: {e.get('title') or ''} {e['text'][:160]}".rstrip()
              for e in journal(p, limit=40) if e["kind"] in ("build", "learn", "skill", "result")][:15]
    mission = next_mission(p)
    lines = [
        f"Autopilot woke: {d['reason']}.",
        *([f"MISSION FROM THE USER (do this first, it wins over your own ideas): [{mission['id']}] {mission['text']}",
           f"Mark it with `autopilot mission start {mission['id']}`, and when finished "
           f"`autopilot mission done {mission['id']} \"<what you did, where>\"` (or `mission drop` with why). "
           f"The usual rules still apply: if it needs something they forbid, drop it and say why."] if mission else []),
        *([f"Usage guard set by the user: pause above {st['session_max']}% (short window) / {st['weekly_max']}% (weekly)."]
          if st["session_max"] < 100 or st["weekly_max"] < 100 else []),
        f"Work window {st['window_start']}-{st['window_end']}; idle {c['idle_minutes']} min.",
        f"Lab folder (the ONLY place you may create or change files): {st['lab_dir']}",
        f"User's focus wish: {st['focus'] or '(none, choose yourself)'}",
        "Boundaries: never open or read the user's other folders (projects, client/freelance work), never contact "
        "their or their clients' servers, never edit skills you did not create. Public docs and your lab only.",
        "Recent Autopilot work (do not repeat it, build on it):", *(recent or ["- (nothing yet)"]),
    ]
    return True, "\n".join(lines)


def ensure_job_cli(hermes_bin: Path, hermes_home: Path, settings: dict, on: bool) -> str:
    """Create/resume (on) or pause (off) the cron job with the Hermes CLI. Returns what happened."""
    job = find_job(hermes_home)
    if job is None:
        if not on:
            return "no job"
        pl = job_payload(settings)
        r = _hermes(hermes_bin, "cron", "create", "--name", JOB_NAME, "--skill", SKILL_NAME, "--script", GATE_SCRIPT,
                    "--deliver", "local", "--failure-deliver", "local", pl["schedule"], PROMPT, timeout=60)
        if r.returncode != 0:
            raise AutopilotError((r.stderr or r.stdout).strip()[-300:])
        return "created"
    jid = job.get("id")
    action = "resume" if on else "pause"
    if bool(job.get("enabled")) == on:
        return "unchanged"
    r = _hermes(hermes_bin, "cron", action, jid, timeout=60)
    if r.returncode != 0:
        raise AutopilotError((r.stderr or r.stdout).strip()[-300:])
    return action + "d"


def find_job(hermes_home: Path) -> dict | None:
    """Our cron job, read from Hermes' jobs.json (the CLI has no JSON listing)."""
    home = Path(hermes_home)
    try:
        rows = json.loads((home / "cron" / "jobs.json").read_text(encoding="utf-8")).get("jobs", [])
    except (OSError, ValueError, AttributeError):
        return None
    return next((j for j in rows if isinstance(j, dict) and j.get("name") == JOB_NAME), None)
