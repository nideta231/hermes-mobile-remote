"""Update a source-installed bridge (a git checkout set up by install.sh) to the newest release.

`check()` is read-only (it runs inside the sandboxed service, where the checkout is read-only):
the release tags on the remote against the tag this checkout is on. `launch()` starts the update
as its own transient systemd unit, outside the bridge's cgroup, because the update ends by
restarting the bridge and a restart kills everything in the service's cgroup. `apply()` is that
unit: fetch, fast-forward to the release, `uv sync`, re-render the unit, move any Hermes still
inside the bridge's cgroup into a scope of its own, restart the bridge. Pairings, TLS keys and
trusted networks live in the config directory, which nothing here touches, so phones reconnect
on their own with the same pairing.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BRIDGE_DIR = REPO / "bridge"
UNIT = "hermes-remote-bridge.service"
_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
CHECK_TTL = 600.0


def version_key(tag: str) -> tuple[int, int, int] | None:
    m = _TAG.match(tag.strip())
    return (int(m[1]), int(m[2]), int(m[3])) if m else None


def latest_tag(tags: list[str]) -> str | None:
    known = [t for t in tags if version_key(t)]
    return max(known, key=version_key) if known else None  # type: ignore[arg-type]


def _git(*args: str, timeout: float = 30) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "--no-optional-locks", "-C", str(REPO), *args], capture_output=True, text=True,
                          timeout=timeout, stdin=subprocess.DEVNULL)


UPSTREAM = "nideta231/hermes-mobile-remote"


def _is_git() -> bool:
    return (REPO / ".git").exists()


def _version_file() -> Path:
    """Desktop-app installs: the release tag of the last successful update, next to the bridge copy."""
    return REPO / "bridge.version"


def _installed_tag() -> str | None:
    try:
        return _version_file().read_text().strip() or None
    except OSError:
        return None


def _api_tags() -> list[str]:
    """Release tags from the GitHub API (desktop-app installs have no git remote to ask)."""
    import urllib.request

    req = urllib.request.Request(f"https://api.github.com/repos/{UPSTREAM}/tags?per_page=100",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "hermes-remote-bridge"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return [t["name"] for t in json.load(r)]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise RuntimeError(str(exc)) from exc


def _fetch_archive(tag: str) -> Path:
    """Download the tag's source archive into a temp file and return its path."""
    import tempfile
    import urllib.request

    url = f"https://github.com/{UPSTREAM}/archive/refs/tags/{tag}.tar.gz"
    fd, name = tempfile.mkstemp(suffix=".tar.gz")
    try:
        with os.fdopen(fd, "wb") as out, urllib.request.urlopen(url, timeout=120) as r:
            shutil.copyfileobj(r, out)
    except OSError as exc:
        Path(name).unlink(missing_ok=True)
        raise RuntimeError(f"Could not download {tag}: {exc}") from exc
    return Path(name)


_SKIP_PARTS = {"tests", ".venv", "__pycache__", ".pytest_cache"}


def _extract_bridge(archive: Path, dest: Path) -> None:
    """Extract only `bridge/` of a source archive (first path component is the repo folder) into [dest].
    Anything with an absolute or `..` path is refused outright; other members are skipped."""
    with tarfile.open(archive) as tf:
        members = tf.getmembers()
        for m in members:
            parts = Path(m.name).parts
            if m.name.startswith("/") or ".." in parts:
                raise RuntimeError(f"The archive has an unsafe path ({m.name}); nothing changed.")
        for m in members:
            parts = Path(m.name).parts[1:]
            if len(parts) < 2 or parts[0] != "bridge" or _SKIP_PARTS & set(parts) or not m.isfile():
                continue
            target = dest.joinpath(*parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            src = tf.extractfile(m)
            if src:
                with src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
                target.chmod(m.mode & 0o777 | 0o600)
    if not dest.exists():
        raise RuntimeError("The archive has no bridge/ folder; nothing changed.")


def _remote_tags(remote: str) -> list[str]:
    if not _is_git():
        return _api_tags()
    r = _git("ls-remote", "--tags", "--refs", remote, "v*.*.*", timeout=20)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or "git ls-remote failed").strip().splitlines()[-1])
    return [line.split("refs/tags/", 1)[1] for line in r.stdout.splitlines() if "refs/tags/" in line]


def _head() -> tuple[str | None, str | None]:
    """(commit, release tag) the checkout is on now."""
    if not _is_git():
        return None, _installed_tag()
    try:
        return (_git("rev-parse", "HEAD").stdout.strip() or None,
                _git("describe", "--tags", "--abbrev=0", "--match", "v*.*.*").stdout.strip() or None)
    except (OSError, subprocess.SubprocessError):
        return None, None


# What the running bridge loaded (this module is imported at startup). The checkout can move on
# under it (git pull, an update whose restart failed): then the files are new but the code isn't.
RUNNING_COMMIT, RUNNING_TAG = _head()


def why_not(remote: str = "origin") -> str | None:
    """Why this install can't update itself, or None when it can."""
    if os.name == "nt":
        return "Windows: update with the Hermes Remote desktop app."
    if _is_git() and not shutil.which("git"):
        return "git is not installed."
    if not shutil.which("systemd-run"):
        return "systemd is required to update in the background."
    if not _is_git():
        return None
    if _git("remote", "get-url", remote).returncode != 0:
        return f"The checkout has no '{remote}' remote."
    dirty = _git("status", "--porcelain", "--untracked-files=no")
    if dirty.returncode == 0 and dirty.stdout.strip():
        return "The checkout has local changes; commit or stash them, then run install.sh."
    return None


def check(remote: str = "origin") -> dict:
    """Current and newest release, read-only. Network: one `git ls-remote`."""
    out: dict = {"current": None, "commit": None, "latest": None, "available": False, "can_update": False,
                 "reason": None, "restart_needed": False, "status": read_status()}
    reason = why_not(remote)
    head_commit, head_tag = _head()
    # Report what is RUNNING, not what is on disk: a checkout updated without a restart serves old code.
    out["current"] = RUNNING_TAG or head_tag
    out["commit"] = (RUNNING_COMMIT or head_commit or "")[:7] or None
    out["restart_needed"] = bool(RUNNING_COMMIT and head_commit and RUNNING_COMMIT != head_commit)
    if reason and not _is_git():
        out["reason"] = reason
        return out
    try:
        out["latest"] = latest_tag(_remote_tags(remote))
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        out["reason"] = f"Can't reach the update server: {exc}"
        return out
    cur_key = version_key(out["current"] or "")
    new_key = version_key(out["latest"] or "")
    newer = bool(new_key and (cur_key is None or new_key > cur_key))
    disk_key = version_key(head_tag or "")
    # Nothing on the remote beyond what is already on disk: only a restart is needed.
    beyond_disk = bool(new_key and (disk_key is None or new_key > disk_key))
    if out["restart_needed"] and not beyond_disk:
        out["latest"] = head_tag or out["latest"]
    out["available"] = newer or out["restart_needed"]
    out["reason"] = reason
    # Loading code that is already on disk needs only a restart: local changes don't block that.
    out["can_update"] = out["available"] and (reason is None or (out["restart_needed"] and not beyond_disk))
    return out


# ------------------------------------------------------------------ status file

def _state_dir() -> Path:
    from .config import STATE_DIR
    return STATE_DIR


def status_file() -> Path:
    return _state_dir() / "update.json"


def log_file() -> Path:
    return _state_dir() / "update.log"


def read_status() -> dict | None:
    try:
        st = json.loads(status_file().read_text())
    except (OSError, ValueError):
        return None
    # The update unit died without writing an outcome (killed, PC rebooted): don't spin forever.
    if st.get("state") == "running" and time.time() - st.get("at", 0) > 20:
        unit = st.get("unit")
        gone = unit and subprocess.run(["systemctl", "--user", "is-active", "--quiet", unit], capture_output=True,
                                       timeout=10).returncode != 0
        if gone or time.time() - st.get("at", 0) > 900:
            st = {**st, "state": "failed", "error": "The update stopped before finishing; the bridge kept running."}
    return st


def _write_status(**fields) -> None:
    status_file().parent.mkdir(parents=True, exist_ok=True)
    unit = os.environ.get("HERMES_REMOTE_UPDATE_UNIT") or fields.pop("unit", None)
    status_file().write_text(json.dumps({**fields, **({"unit": unit} if unit else {}), "at": time.time()}))


# ------------------------------------------------------------------ launch

def _uv() -> str | None:
    for c in (shutil.which("uv"), str(Path.home() / ".local/bin/uv"), str(Path.home() / ".cargo/bin/uv")):
        if c and Path(c).exists():
            return c
    return None


def launch_argv(python: str = sys.executable, action: str = "--apply") -> list[str]:
    """The transient unit that runs [apply]: its own cgroup, so the bridge restart can't end it."""
    path = os.pathsep.join(dict.fromkeys([str(Path.home() / ".local/bin"), str(Path.home() / ".cargo/bin"),
                                          *os.environ.get("PATH", "/usr/bin:/bin").split(os.pathsep)]))
    unit = f"hermes-remote-update-{secrets.token_hex(3)}"
    return ["systemd-run", "--user", "--quiet", "--collect", f"--unit={unit}",
            "--description=Hermes Remote bridge update", f"--working-directory={REPO}", "-E", f"PATH={path}",
            "-E", f"HERMES_REMOTE_UPDATE_UNIT={unit}.service",
            python, "-m", "hermes_remote_bridge.cli", "update", action]


def launch() -> None:
    reason = why_not()
    if reason and not check().get("can_update"):
        raise RuntimeError(reason)
    st = read_status()
    if st and st.get("state") == "running" and time.time() - st.get("at", 0) < 600:
        raise RuntimeError("An update is already running.")
    argv = launch_argv()
    unit = next(a.split("=", 1)[1] for a in argv if a.startswith("--unit=")) + ".service"
    _write_status(state="running", step="starting", unit=unit)
    r = subprocess.run(argv, capture_output=True, text=True, timeout=20, stdin=subprocess.DEVNULL)
    if r.returncode != 0:
        _write_status(state="failed", error=(r.stderr or "systemd-run failed").strip()[-300:])
        raise RuntimeError((r.stderr or "systemd-run failed").strip())


# ------------------------------------------------------------------ apply (runs outside the bridge)

def _systemctl(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["systemctl", "--user", *args], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)


def bridge_cgroup_pids() -> tuple[list[int], int]:
    """Processes in the bridge service's cgroup other than the bridge itself, and its main pid."""
    cg = _systemctl("show", "-p", "ControlGroup", "--value", UNIT).stdout.strip()
    main = int(_systemctl("show", "-p", "MainPID", "--value", UNIT).stdout.strip() or 0)
    if not cg:
        return [], main
    try:
        pids = [int(p) for p in Path("/sys/fs/cgroup" + cg, "cgroup.procs").read_text().split()]
    except (OSError, ValueError):
        return [], main
    return [p for p in pids if p != main and p != os.getpid()], main


def _scope(pids: list[int], name: str) -> bool:
    args = ["busctl", "--user", "call", "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
            "org.freedesktop.systemd1.Manager", "StartTransientUnit", "ssa(sv)a(sa(sv))", name, "fail", "2",
            "PIDs", "au", str(len(pids)), *map(str, pids), "Description", "s", "Hermes (kept running across a bridge restart)", "0"]
    return subprocess.run(args, capture_output=True, timeout=20, stdin=subprocess.DEVNULL).returncode == 0


def keep_hermes_alive() -> int:
    """Move what runs inside the bridge's cgroup (a Hermes an older bridge started, and its tools) into
    scopes of their own, so restarting the bridge leaves them running. Returns how many moved."""
    pids, _ = bridge_cgroup_pids()
    if not pids:
        return 0
    if _scope(pids, f"hermes-remote-serve-{secrets.token_hex(4)}.scope"):
        return len(pids)
    moved = 0  # one pid gone meanwhile fails the whole call: move them one by one
    for p in pids:
        if Path(f"/proc/{p}").exists() and _scope([p], f"hermes-remote-serve-{secrets.token_hex(4)}.scope"):
            moved += 1
    return moved


def _render_unit() -> None:
    """Refresh the installed unit from the checkout's template (as install.sh does), when it changed.

    Only for git installs: the desktop app writes its own unit (with its own environment), so an app
    install's unit is left as the app wrote it."""
    if not _is_git():
        return
    unit = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "systemd/user" / UNIT
    template = BRIDGE_DIR / "systemd" / UNIT
    if not unit.exists() or unit.is_symlink() or not template.exists():
        return
    text = template.read_text().replace("@BIN@", str(BRIDGE_DIR / ".venv/bin/hermes-remote-bridge")).replace("@REPO@", str(REPO))
    if unit.read_text() != text:
        unit.write_text(text)
        _systemctl("daemon-reload")


def _restarts() -> int:
    try:
        return int(_systemctl("show", "-p", "NRestarts", "--value", UNIT).stdout.strip() or 0)
    except ValueError:
        return 0


def _health_url() -> str | None:
    """The bridge's loopback /healthz, when it listens on loopback (the default)."""
    try:
        from .config import Config
        cfg = Config.load()
    except Exception:  # noqa: BLE001 - a broken config is exactly what the health check must catch
        return None
    host = next((h for h in cfg.listen if h in ("127.0.0.1", "::1", "localhost")), None)
    if not host:
        return None
    return f"http://{'[::1]' if host == '::1' else host}:{cfg.port}/healthz"


def bridge_healthy(timeout: float = 45.0, settle: float = 6.0) -> bool:
    """True once the restarted bridge answers /healthz (or, without loopback, stays up without
    restarting for [settle] s). A bridge that crash-loops on the new code is unhealthy."""
    import urllib.request

    url = _health_url()
    start_restarts = _restarts()
    deadline = time.monotonic() + timeout
    up_since = None
    while time.monotonic() < deadline:
        active = _systemctl("is-active", "--quiet", UNIT).returncode == 0
        if active and url:
            try:
                with urllib.request.urlopen(url, timeout=3) as r:
                    if r.status == 200 and _restarts() == start_restarts:
                        return True
            except OSError:
                pass
        elif active:
            up_since = up_since or time.monotonic()
            if time.monotonic() - up_since >= settle and _restarts() == start_restarts:
                return True
        else:
            up_since = None
        time.sleep(1.5)
    return False


def _ensure_running(log) -> None:
    """Whatever happened, leave the bridge running: phones can only reconnect to a running bridge."""
    if _systemctl("is-active", "--quiet", UNIT).returncode != 0:
        log("The bridge was not running; starting it.")
        _systemctl("reset-failed", UNIT)
        _systemctl("start", UNIT)


def _swap_back(backup: Path) -> None:
    shutil.rmtree(BRIDGE_DIR, ignore_errors=True)
    backup.rename(BRIDGE_DIR)


def _apply_archive(log) -> str:
    """Desktop-app install: swap the bridge copy for the newest release's `bridge/`, keeping the old one
    as `bridge.old`; any failure puts it back, re-syncs and restarts."""
    uv = _uv()
    old_tag = _installed_tag() or "the installed version"
    try:
        log("Looking up the newest release…")
        _write_status(state="running", step="fetching")
        target = latest_tag(_api_tags())
        if not target:
            raise RuntimeError("No release tags found.")
        if not uv:
            raise RuntimeError("uv not found; nothing changed.")
        new, backup = REPO / "bridge.new", REPO / "bridge.old"
        shutil.rmtree(new, ignore_errors=True)
        archive = _fetch_archive(target)
        try:
            _extract_bridge(archive, new)
        except BaseException:
            shutil.rmtree(new, ignore_errors=True)
            raise
        finally:
            archive.unlink(missing_ok=True)
        log(f"Moving to {target}…")
        _write_status(state="running", step="checkout", to=target)
        shutil.rmtree(backup, ignore_errors=True)
        BRIDGE_DIR.rename(backup)
        try:
            new.rename(BRIDGE_DIR)
            log("Installing dependencies (uv sync)…")
            _write_status(state="running", step="sync", to=target)
            s = _sync(uv, "--frozen", env={"UV_PROJECT_ENVIRONMENT": str(REPO / "venv")})
            if s.returncode != 0:
                raise RuntimeError(f"uv sync failed: {s.stderr.strip()[-300:]}")
            _render_unit()
            install_skills(log)
            moved = keep_hermes_alive()
            log("Restarting the bridge…")
            _write_status(state="running", step="restart", to=target)
            _systemctl("reset-failed", UNIT)
            _systemctl("restart", UNIT)
            _write_status(state="running", step="verify", to=target)
            if not bridge_healthy():
                raise RuntimeError(f"{target} did not start")
        except Exception as exc:
            log("Rolling back to the previous version…")
            _swap_back(backup)
            _sync(uv, "--frozen", env={"UV_PROJECT_ENVIRONMENT": str(REPO / "venv")})
            _render_unit()
            keep_hermes_alive()
            _systemctl("reset-failed", UNIT)
            _systemctl("restart", UNIT)
            ok = bridge_healthy()
            raise RuntimeError(f"{exc}; rolled back to {old_tag}" + ("" if ok else " (check `journalctl --user -u hermes-remote-bridge`)")) from exc
        _version_file().write_text(target + "\n")
        _write_status(state="done", to=target, moved=moved)
        log(f"Updated to {target}. Paired devices are unchanged; phones reconnect on their own.")
        return target
    finally:
        _ensure_running(log)


_GIT_SYNC = ("--quiet", "--inexact")


def _sync(uv: str, *flags: str, env: dict | None = None) -> subprocess.CompletedProcess:
    """`uv sync` in the bridge directory; [flags] and [env] differ per kind of install."""
    return subprocess.run([uv, "sync", "--no-dev", *flags], cwd=BRIDGE_DIR, capture_output=True, text=True, timeout=600,
                          stdin=subprocess.DEVNULL, env={**os.environ, **env} if env else None)


def _rollback(old: str, uv: str | None, log) -> None:
    log("Rolling back to the previous version…")
    _git("reset", "--keep", old)
    if uv:
        _sync(uv, *_GIT_SYNC)
    _render_unit()


def _restart_only(log, reason: str) -> str:
    """The checkout can't be moved (local changes...), but what is on disk may still be newer than
    what runs: restart to load it, health-checked like an update."""
    head_tag = _head()[1] or "the installed version"
    try:
        log(f"Not fetching ({reason}); restarting to load {head_tag}…")
        install_skills(log)
        moved = keep_hermes_alive()
        _write_status(state="running", step="restart", to=head_tag)
        _systemctl("reset-failed", UNIT)
        _systemctl("restart", UNIT)
        _write_status(state="running", step="verify", to=head_tag)
        if not bridge_healthy():
            raise RuntimeError(f"{head_tag} did not start (check `journalctl --user -u hermes-remote-bridge`)")
        _write_status(state="done", to=head_tag, moved=moved)
        log(f"Restarted on {head_tag}.")
        return head_tag
    finally:
        _ensure_running(log)


def apply(remote: str = "origin", log=print) -> str:
    """Fetch, move to the newest release, sync the venv, restart the bridge and check it answers.

    Every path ends with a running bridge: a failure before the restart changes nothing (or is
    rolled back), and a new version that doesn't come up healthy is rolled back and restarted.
    """
    reason = why_not(remote)
    if not _is_git():
        if reason:
            raise RuntimeError(reason)
        return _apply_archive(log)
    if reason:
        info = check(remote)
        if info["restart_needed"] and info["can_update"]:
            return _restart_only(log, reason)
        raise RuntimeError(reason)
    old = _git("rev-parse", "HEAD").stdout.strip()
    old_tag = _git("describe", "--tags", "--abbrev=0", "--match", "v*.*.*").stdout.strip() or old[:7]
    uv = _uv()
    try:
        log(f"Fetching {remote}…")
        _write_status(state="running", step="fetching")
        f = _git("fetch", "--tags", "--force", remote, timeout=180)
        if f.returncode != 0:
            raise RuntimeError(f"git fetch failed: {f.stderr.strip()}")
        target = latest_tag(_git("tag", "--list", "v*.*.*").stdout.split())
        if not target:
            raise RuntimeError("No release tags found.")
        if not uv:
            raise RuntimeError("uv not found; nothing changed.")
        branch = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
        log(f"Moving to {target}…")
        _write_status(state="running", step="checkout", to=target)
        step = _git("checkout", "--quiet", target) if branch == "HEAD" else _git("merge", "--ff-only", "--quiet", target)
        if step.returncode != 0:
            raise RuntimeError(f"Could not move to {target}: {step.stderr.strip() or step.stdout.strip()}")
        log("Installing dependencies (uv sync)…")
        _write_status(state="running", step="sync", to=target)
        s = _sync(uv, *_GIT_SYNC)
        if s.returncode != 0:
            _rollback(old, uv, log)
            raise RuntimeError(f"uv sync failed, rolled back: {s.stderr.strip()[-300:]}")
        _render_unit()
        install_skills(log)
        moved = keep_hermes_alive()
        if moved:
            log(f"Kept {moved} Hermes process(es) running outside the bridge.")
        log("Restarting the bridge…")
        _write_status(state="running", step="restart", to=target)
        _systemctl("reset-failed", UNIT)
        _systemctl("restart", UNIT)
        log("Checking the new bridge answers…")
        _write_status(state="running", step="verify", to=target)
        if not bridge_healthy():
            _rollback(old, uv, log)
            keep_hermes_alive()
            _systemctl("reset-failed", UNIT)
            _systemctl("restart", UNIT)
            ok = bridge_healthy()
            raise RuntimeError(f"{target} did not start; rolled back to {old_tag}" + ("" if ok else " (check `journalctl --user -u hermes-remote-bridge`)"))
        _write_status(state="done", to=target, moved=moved)
        log(f"Updated to {target}. Paired devices are unchanged; phones reconnect on their own.")
        return target
    finally:
        _ensure_running(log)


# Hermes skills the bridge ships: (folder in bridge/skills, category under $HERMES_HOME/skills).
SKILLS = (("personal-notes", "productivity"), ("project-blueprint", "software-development"))


def install_skills(log=print) -> list[str]:
    """Copy shipped Hermes skills that are missing (never overwrite one the user changed). Runs from the
    update/restart job, outside the service sandbox that makes ~/.hermes read-only."""
    try:
        from .config import Config
        home = Path(Config.load().hermes_home)
    except Exception:  # noqa: BLE001
        home = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
    done = []
    for name, category in SKILLS:
        src = BRIDGE_DIR / "skills" / name / "SKILL.md"
        dst = home / "skills" / category / name / "SKILL.md"
        if src.exists() and not dst.exists() and not any((home / "skills").glob(f"*/{name}/SKILL.md")):
            try:
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dst)
                done.append(name)
            except OSError as exc:
                log(f"Could not install the {name} skill: {exc}")
    if done:
        log("Installed Hermes skills: " + ", ".join(done))
    return done


def restart(log=print) -> None:
    """Restart the bridge, health-checked; the bridge is left running whatever happens."""
    try:
        install_skills(log)
        moved = keep_hermes_alive()
        if moved:
            log(f"Kept {moved} Hermes process(es) running outside the bridge.")
        _write_status(state="running", step="restart", to=RUNNING_TAG or _head()[1])
        _systemctl("reset-failed", UNIT)
        _systemctl("restart", UNIT)
        _write_status(state="running", step="verify")
        if not bridge_healthy():
            raise RuntimeError("The bridge did not answer after the restart (check `journalctl --user -u hermes-remote-bridge`)")
        _write_status(state="done", to=_head()[1], moved=moved, restarted=True)
        log("Bridge restarted.")
    finally:
        _ensure_running(log)


def launch_restart() -> None:
    """Restart from inside the bridge: run it as its own unit, or the restart would kill it halfway."""
    if os.name == "nt" or not shutil.which("systemd-run"):
        raise RuntimeError("Restarting from the phone needs systemd on the PC.")
    st = read_status()
    if st and st.get("state") == "running" and time.time() - st.get("at", 0) < 120:
        raise RuntimeError("An update or restart is already running.")
    argv = launch_argv(action="--restart")
    unit = next(a.split("=", 1)[1] for a in argv if a.startswith("--unit=")) + ".service"
    _write_status(state="running", step="starting", unit=unit)
    r = subprocess.run(argv, capture_output=True, text=True, timeout=20, stdin=subprocess.DEVNULL)
    if r.returncode != 0:
        _write_status(state="failed", error=(r.stderr or "systemd-run failed").strip()[-300:])
        raise RuntimeError((r.stderr or "systemd-run failed").strip())


def run_restart() -> int:
    log_file().parent.mkdir(parents=True, exist_ok=True)
    with open(log_file(), "a") as fh:
        def log(msg: str) -> None:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n"); fh.flush()
        try:
            restart(log=log)
            return 0
        except Exception as exc:  # noqa: BLE001
            log(f"Restart failed: {exc}")
            _write_status(state="failed", error=str(exc)[:500])
            return 1


def run_apply() -> int:
    """`hermes-remote-bridge update --apply`: the transient unit's entry point; logs to update.log."""
    log_file().parent.mkdir(parents=True, exist_ok=True)
    with open(log_file(), "a") as fh:
        def log(msg: str) -> None:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n"); fh.flush()
        try:
            apply(log=log)
            return 0
        except Exception as exc:  # noqa: BLE001 - every failure must land in the status file
            log(f"Update failed: {exc}")
            _write_status(state="failed", error=str(exc)[:500])
            return 1


class CachedCheck:
    """`check()` is a network call: the phone's Settings page reuses one for [CHECK_TTL] seconds."""

    def __init__(self, ttl: float = CHECK_TTL, fn=check):
        self.ttl, self.fn, self._at, self._value = ttl, fn, 0.0, None

    def get(self, force: bool = False) -> dict:
        if force or self._value is None or time.monotonic() - self._at > self.ttl:
            self._value, self._at = self.fn(), time.monotonic()
        return {**self._value, "status": read_status()}
