"""Memory Library feature patch: install the pinned runtime, switch Hermes' memory provider on and off
per profile without breaking an existing one, import the built-in memory as a copy, and check it all.

The memory itself lives in Postgres (``library_store``); Hermes reaches it through the ``library``
memory provider plugin this module installs into ``<hermes home>/plugins/library``.

Everything the feature owns sits under ``<state dir>/library``:
``bin/pg0`` (pinned, checksum-verified), ``runtime/`` (Postgres extracted by pg0), ``data/``,
``run/`` (the socket; no TCP port), ``secret``, ``backups/`` and ``state.json``.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

from . import library_store as ls
from .config import STATE_DIR

ROOT = STATE_DIR / "library"
PLUGIN_SRC = Path(__file__).resolve().parent / "library_plugin"
PG0_VERSION = "0.15.2"
# Release assets of vectorize-io/pg0 v0.15.2 (sha256 from the GitHub release).
PG0_ASSETS = {
    ("linux", "x86_64"): ("pg0-linux-x86_64-gnu", 34200744,
                          "06c4678ff7b461dba8ed1f95c5e202ead3b57b70f549a22c17a94985121c0bf5"),
    ("linux", "aarch64"): ("pg0-linux-aarch64-gnu", 33045176,
                           "08bf6f5e04bc456fac2f8e76bfec5705c55f0b079f11b56efa5b1f6869e20f76"),
}
PG0_URL = "https://github.com/vectorize-io/pg0/releases/download/v{v}/{asset}"
EXTRACTED_BYTES = 78_000_000   # measured: Postgres 18.1 + extensions as pg0 unpacks them
DATA_BYTES = 45_000_000        # an empty cluster with one database
MIN_FREE_BYTES = 500_000_000
MIN_GLIBC = (2, 35)
PROVIDER = "library"
AUX_TASK = "memory_library"

_lock = threading.Lock()
_job: dict = {}


def paths(root: Path | None = None) -> ls.Paths:
    return ls.Paths(root or ROOT)


# ---------------------------------------------------------------------- state

def read_state(p: ls.Paths) -> dict:
    try:
        return json.loads(p.state.read_text())
    except (OSError, ValueError):
        return {}


def write_state(p: ls.Paths, state: dict) -> None:
    p.root.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.root, prefix=".state-")
    with os.fdopen(fd, "w") as fh:
        json.dump(state, fh, indent=2)
    os.replace(tmp, p.state)


def _update_state(p: ls.Paths, **changes) -> dict:
    with _lock:
        st = read_state(p)
        st.update(changes)
        write_state(p, st)
        return st


# ---------------------------------------------------------------------- preflight

def platform_asset() -> tuple[str, int, str] | None:
    machine = platform.machine().lower()
    machine = {"amd64": "x86_64", "arm64": "aarch64"}.get(machine, machine)
    return PG0_ASSETS.get((platform.system().lower(), machine))


def glibc_version() -> tuple[int, int] | None:
    lib, ver = platform.libc_ver()
    if lib != "glibc" or not ver:
        return None
    try:
        return tuple(int(x) for x in ver.split(".")[:2])  # type: ignore[return-value]
    except ValueError:
        return None


def why_not() -> str | None:
    """The reason this PC can't run the Library, or None. Only platforms tested end to end pass."""
    if platform_asset() is None:
        return (f"The Memory Library supports Linux (x86_64, ARM64) for now; this PC is "
                f"{platform.system()} {platform.machine()}.")
    g = glibc_version()
    if g is None:
        return "The Memory Library needs a glibc Linux (Debian, Ubuntu, Fedora, Arch...); musl/Alpine is not supported."
    if g < MIN_GLIBC:
        return f"The Memory Library needs glibc {MIN_GLIBC[0]}.{MIN_GLIBC[1]} or newer; this PC has {g[0]}.{g[1]}."
    return None


def plan(p: ls.Paths | None = None) -> dict:
    """What installing would download and use, for the consent screen."""
    p = p or paths()
    asset = platform_asset()
    p_root = p.root if p.root.exists() else next(x for x in [p.root, *p.root.parents] if x.exists())
    free = shutil.disk_usage(p_root).free
    download = asset[1] if asset else 0
    need = download + EXTRACTED_BYTES + DATA_BYTES
    return {
        "supported": why_not() is None, "reason": why_not(),
        "components": [{"name": f"PostgreSQL 18.1 via pg0 {PG0_VERSION}", "download_bytes": download,
                        "disk_bytes": download + EXTRACTED_BYTES, "license": "MIT (pg0), PostgreSQL License"},
                       {"name": "Empty memory database", "download_bytes": 0, "disk_bytes": DATA_BYTES}],
        "download_bytes": download, "disk_bytes": need, "disk_free_bytes": free,
        "enough_disk": free >= max(need, MIN_FREE_BYTES), "location": str(p.root),
        "remove": "Remove deletes the runtime; your memories are deleted only with purge, after a backup dump.",
        "network": "None. The database listens only on a private socket file, never on a network port.",
    }


# ---------------------------------------------------------------------- install

def _progress(step: str, **extra) -> None:
    _job.update(step=step, at=time.time(), **extra)


def job() -> dict:
    return dict(_job)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def download_pg0(p: ls.Paths, url: str | None = None, digest: str | None = None, size: int | None = None) -> None:
    asset = platform_asset()
    if asset is None and url is None:
        raise RuntimeError(why_not() or "unsupported platform")
    name, exp_size, exp_digest = asset or ("pg0", size or 0, digest or "")
    url = url or PG0_URL.format(v=PG0_VERSION, asset=name)
    digest, size = digest or exp_digest, size or exp_size
    p.bin.mkdir(parents=True, exist_ok=True)
    if p.pg0.exists() and _sha256(p.pg0) == digest:
        return
    part = p.pg0.with_suffix(".part")
    have = part.stat().st_size if part.exists() else 0
    req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(req, timeout=60) as r:
        if have and r.status != 206:
            have = 0
        with open(part, "ab" if have else "wb") as fh:
            done = have
            while chunk := r.read(1 << 16):
                fh.write(chunk)
                done += len(chunk)
                _progress("download", done=done, total=size)
    if _sha256(part) != digest:
        part.unlink(missing_ok=True)
        raise RuntimeError("The downloaded runtime failed its checksum check; nothing was installed.")
    part.chmod(0o755)
    os.replace(part, p.pg0)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_runtime(p: ls.Paths) -> None:
    """pg0 unpacks its bundled Postgres on first start: run it once into a throwaway cluster, then drop it."""
    if (p.install / "bin" / "postgres").exists():
        return
    p.runtime.mkdir(parents=True, exist_ok=True)
    p.run.mkdir(parents=True, exist_ok=True, mode=0o700)
    env = {**os.environ, "HOME": str(p.runtime)}
    scratch = p.root / ".extract"
    shutil.rmtree(scratch, ignore_errors=True)
    r = subprocess.run([str(p.pg0), "start", "--name", "extract", "-p", str(_free_port()), "-d", str(scratch),
                        "-c", f"unix_socket_directories={p.run}"], env=env, capture_output=True, text=True, timeout=180)
    pid = _postmaster_pid(scratch)
    subprocess.run([str(p.pg0), "stop", "--name", "extract"], env=env, capture_output=True, timeout=60)
    subprocess.run([str(p.pg0), "drop", "--name", "extract"], env=env, capture_output=True, timeout=60)
    _wait_gone(pid)  # this throwaway server listened on a localhost port: never leave it running
    shutil.rmtree(scratch, ignore_errors=True)
    if not (p.install / "bin" / "postgres").exists():
        raise RuntimeError("Could not unpack the database runtime: " + (r.stderr or r.stdout).strip()[-300:])


def _postmaster_pid(data_dir: Path) -> int | None:
    try:
        return int((data_dir / "postmaster.pid").read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return None


def _wait_gone(pid: int | None, timeout: float = 15) -> None:
    """Wait for a Postgres server to exit; stop it (fast shutdown, then kill) when it doesn't."""
    if not pid:
        return
    import signal

    def alive() -> bool:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    for sig, wait in ((None, timeout), (signal.SIGINT, 10), (signal.SIGKILL, 5)):
        if sig is not None and alive():
            try:
                os.kill(pid, sig)
            except OSError:
                return
        deadline = time.monotonic() + wait
        while alive() and time.monotonic() < deadline:
            time.sleep(0.2)
        if not alive():
            return


def init_cluster(p: ls.Paths) -> None:
    if (p.data / "PG_VERSION").exists():
        return
    if not p.secret.exists():
        fd = os.open(p.secret, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            fh.write(secrets.token_urlsafe(32))
    env = ls.pg_env(p)
    with tempfile.NamedTemporaryFile("w", dir=p.root, delete=False) as pw:
        pw.write(p.secret.read_text().strip())
    try:
        r = subprocess.run([str(p.install / "bin" / "initdb"), "-D", str(p.data), "-U", "hermes",
                            "--auth=scram-sha-256", f"--pwfile={pw.name}", "-E", "UTF8", "--locale=C.UTF-8"],
                           env=env, capture_output=True, text=True, timeout=180)
    finally:
        os.unlink(pw.name)
    if r.returncode != 0:
        shutil.rmtree(p.data, ignore_errors=True)
        raise RuntimeError("Could not create the memory database: " + (r.stderr or r.stdout).strip()[-300:])


def benchmark(p: ls.Paths) -> dict:
    store = ls.Store(p, "default")
    start = time.monotonic()
    for _ in range(10):
        store._psql("select 1;\n", db="postgres")
    per_query = (time.monotonic() - start) / 10 * 1000
    return {"query_ms": round(per_query, 1), "ok": per_query < 250}


def install(p: ls.Paths | None = None, *, accept: bool = False) -> dict:
    p = p or paths()
    if not accept:
        raise RuntimeError("Installing downloads the database runtime; accept the plan first (--yes).")
    if reason := why_not():
        raise RuntimeError(reason)
    pl = plan(p)
    if not pl["enough_disk"]:
        raise RuntimeError(f"Not enough free disk at {p.root}: need about {pl['disk_bytes'] // 1_000_000} MB "
                           f"plus headroom, have {pl['disk_free_bytes'] // 1_000_000} MB.")
    p.root.mkdir(parents=True, exist_ok=True, mode=0o700)
    _progress("download", done=0, total=pl["download_bytes"])
    download_pg0(p)
    _progress("unpack")
    extract_runtime(p)
    _progress("database")
    init_cluster(p)
    ls.ensure_running(p)
    _progress("benchmark")
    bench = benchmark(p)
    _update_state(p, installed_at=time.time(), pg0=PG0_VERSION, postgres=ls.PG_VERSION, benchmark=bench)
    _progress("done", benchmark=bench)
    return {"installed": True, "benchmark": bench}


def install_async(p: ls.Paths | None = None) -> None:
    if _job.get("state") == "running":
        raise RuntimeError("An install is already running.")
    _job.clear()
    _job.update(state="running", step="starting", at=time.time())

    def run():
        try:
            install(p, accept=True)
            _job.update(state="done")
        except Exception as exc:  # noqa: BLE001 - reported to the phone
            _job.update(state="failed", error=str(exc)[:400])

    threading.Thread(target=run, name="library-install", daemon=True).start()


# ---------------------------------------------------------------------- Hermes config (per profile)

def _hermes(hermes_bin: Path, profile: str | None, *args: str, timeout: float = 30) -> str:
    argv = [str(hermes_bin), *(("-p", profile) if profile and profile != "default" else ()), *args]
    r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    if r.returncode != 0 and args[:2] == ("config", "get") and "not set" in (r.stdout + r.stderr).lower():
        return ""  # an unset key: `hermes config get` exits 1 and says so
    if r.returncode != 0:
        raise RuntimeError(f"hermes {' '.join(args[:3])} failed: {(r.stderr or r.stdout).strip()[-300:]}")
    return r.stdout


def current_provider(hermes_bin: Path, profile: str | None) -> str:
    return _hermes(hermes_bin, profile, "config", "get", "memory.provider").strip().strip('"').strip("'")


def profile_home(hermes_home: Path, profile: str | None) -> Path:
    return hermes_home if not profile or profile == "default" else hermes_home / "profiles" / profile


def plugin_hash(directory: Path) -> str:
    h = hashlib.sha256()
    for f in sorted(directory.glob("*.py")) + sorted(directory.glob("*.yaml")):
        h.update(f.name.encode() + b"\0" + f.read_bytes())
    return h.hexdigest()


def plugin_files() -> dict[str, bytes]:
    """The plugin as installed: its own files plus the shared store module (one source of truth)."""
    files = {f.name: f.read_bytes() for f in PLUGIN_SRC.iterdir() if f.suffix in (".py", ".yaml")}
    files["store.py"] = Path(ls.__file__).read_bytes()
    return files


def install_plugin(home: Path, p: ls.Paths) -> Path:
    dst = home / "plugins" / PROVIDER
    dst.mkdir(parents=True, exist_ok=True)
    for name, data in plugin_files().items():
        (dst / name).write_bytes(data)
    (dst / "library.json").write_text(json.dumps({"root": str(p.root)}))
    return dst


def preflight(hermes_bin: Path, hermes_home: Path, profile: str | None, p: ls.Paths | None = None) -> dict:
    """Everything the phone shows before enabling: existing provider, built-in memory, runtime."""
    p = p or paths()
    home = profile_home(hermes_home, profile)
    provider = current_provider(hermes_bin, profile)
    custom = "" if ls_is_core(provider) or provider == PROVIDER else provider
    builtin = {}
    for name in ("MEMORY.md", "USER.md"):
        f = home / "memories" / name
        builtin[name] = {"exists": f.exists(), "entries": len(split_entries(f.read_text())) if f.exists() else 0}
    other = home / "plugins" / PROVIDER
    foreign = other.exists() and not (other / "library.json").exists()
    return {"profile": profile or "default", "provider": provider or "built-in", "enabled": provider == PROVIDER,
            "custom_provider": custom or None, "plugin_dir_conflict": foreign, "builtin_memory": builtin,
            "installed": ls.is_installed(p), "running": ls.is_running(p) if ls.is_installed(p) else False,
            "plan": plan(p)}


def ls_is_core(name: str) -> bool:
    return (name or "").strip().lower() in ("", "default", "builtin", "built-in", "none")


def split_entries(text: str) -> list[str]:
    return [e.strip() for e in re.split(r"^\s*§\s*$", text, flags=re.M) if e.strip()]


def import_builtin(store: ls.Store, home: Path, p: ls.Paths) -> dict:
    """Copy MEMORY.md / USER.md into the Library (originals untouched, backed up first). Entries are
    flagged for review: the user (or the memory model) sets kind, severity and scope later."""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = p.backups / f"{store.profile}-{stamp}"
    added = skipped = 0
    for name, kind in (("MEMORY.md", "fact"), ("USER.md", "preference")):
        f = home / "memories" / name
        if not f.exists():
            continue
        backup.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, backup / name)
        for entry in split_entries(f.read_text()):
            for chunk in [entry[i:i + ls.MAX_TEXT] for i in range(0, len(entry), ls.MAX_TEXT)]:
                row = store.add(chunk, kind=kind, severity=3, source=f"import:{name}", review=True, by="import")
                skipped += bool(row.get("duplicate"))
                added += not row.get("duplicate")
    return {"added": added, "skipped": skipped, "backup": str(backup) if backup.exists() else None}


def enable(hermes_bin: Path, hermes_home: Path, profile: str | None, *, replace_custom: bool = False,
           import_memory: bool = True, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    profile = profile or "default"
    pre = preflight(hermes_bin, hermes_home, profile, p)
    if pre["custom_provider"] and not replace_custom:
        raise RuntimeError(f"This profile already uses the '{pre['custom_provider']}' memory provider. "
                           "It was left as is; choose 'switch' to replace it (it can be switched back on disable).")
    if pre["plugin_dir_conflict"]:
        raise RuntimeError("A different plugin named 'library' is already installed in this profile; not touching it.")
    if not ls.is_installed(p):
        raise RuntimeError("Install the Memory Library runtime first.")
    ls.ensure_running(p)
    store = ls.Store(p, profile)
    store.ensure_database()
    home = profile_home(hermes_home, profile)
    st = read_state(p)
    prof = st.get("profiles", {}).get(profile, {})
    imported = None
    if import_memory and not prof.get("imported_at"):
        imported = import_builtin(store, home, p)
    plugin = install_plugin(home, p)
    previous = prof.get("previous_provider") if pre["enabled"] else ("" if ls_is_core(pre["provider"]) else pre["provider"])
    if pre["provider"] == "built-in":
        previous = ""
    _hermes(hermes_bin, profile, "config", "set", "memory.provider", PROVIDER)
    if current_provider(hermes_bin, profile) != PROVIDER:
        raise RuntimeError("Hermes did not accept memory.provider=library; nothing else was changed.")
    prof.update(enabled=True, enabled_at=time.time(), previous_provider=previous or "",
                plugin_hash=plugin_hash(plugin), home=str(home))
    if imported:
        prof.update(imported_at=time.time(), import_backup=imported["backup"])
    profiles = st.get("profiles", {})
    profiles[profile] = prof
    _update_state(p, profiles=profiles)
    return {"enabled": True, "profile": profile, "previous_provider": previous or "built-in", "imported": imported,
            "note": "Takes effect in new chats (Hermes loads the memory provider when a chat starts)."}


def disable(hermes_bin: Path, hermes_home: Path, profile: str | None, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    profile = profile or "default"
    st = read_state(p)
    prof = st.get("profiles", {}).get(profile, {})
    now = current_provider(hermes_bin, profile)
    restored = now
    if now == PROVIDER:
        restored = prof.get("previous_provider", "")
        _hermes(hermes_bin, profile, "config", "set", "memory.provider", restored)
    plugin = profile_home(hermes_home, profile) / "plugins" / PROVIDER
    if (plugin / "library.json").exists():
        shutil.rmtree(plugin, ignore_errors=True)
    prof.update(enabled=False, disabled_at=time.time())
    profiles = st.get("profiles", {})
    profiles[profile] = prof
    _update_state(p, profiles=profiles)
    return {"enabled": False, "profile": profile, "provider": restored or "built-in",
            "note": "Your memories are kept; enabling again picks them up."}


def remove(hermes_bin: Path, hermes_home: Path, *, purge: bool = False, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    for prof, info in read_state(p).get("profiles", {}).items():
        if info.get("enabled"):
            disable(hermes_bin, hermes_home, prof, p)
    dump = None
    if purge and ls.is_installed(p):
        dump = backup_dump(p)
    ls.stop(p)
    for d in (p.bin, p.runtime, p.run):
        shutil.rmtree(d, ignore_errors=True)
    if purge:
        shutil.rmtree(p.data, ignore_errors=True)
        p.secret.unlink(missing_ok=True)
        st = read_state(p)
        st.pop("profiles", None)
        st.pop("installed_at", None)
        write_state(p, st)
    else:
        _update_state(p, installed_at=None)
    return {"removed": True, "purged": purge, "dump": dump}


def backup_dump(p: ls.Paths) -> str:
    ls.ensure_running(p)
    p.backups.mkdir(parents=True, exist_ok=True)
    out = p.backups / f"all-{time.strftime('%Y%m%d-%H%M%S')}.sql"
    with open(out, "w") as fh:
        r = subprocess.run([str(p.install / "bin" / "pg_dumpall"), "-h", str(p.run), "-p", str(ls.PORT), "-U", "hermes"],
                           stdout=fh, stderr=subprocess.PIPE, text=True, env=ls.pg_env(p), timeout=600)
    if r.returncode != 0:
        raise RuntimeError("Backup dump failed: " + r.stderr.strip()[-300:])
    out.chmod(0o600)
    return str(out)


# ---------------------------------------------------------------------- doctor

OK, WARN, FAIL = "ok", "warn", "fail"


def doctor(hermes_bin: Path, hermes_home: Path, profile: str | None, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    profile = profile or "default"
    checks: list[dict] = []

    def add(cid: str, status: str, text: str, fix: str | None = None):
        checks.append({"id": cid, "status": status, "text": text, "fix": fix})

    reason = why_not()
    add("platform", FAIL if reason else OK, reason or f"{platform.system()} {platform.machine()}, glibc "
        f"{'.'.join(map(str, glibc_version() or ()))} is supported")
    if not ls.is_installed(p):
        add("runtime", WARN, "The database runtime is not installed", "install")
        return {"profile": profile, "checks": checks}
    pg0_ok = p.pg0.exists() and platform_asset() is not None and _sha256(p.pg0) == platform_asset()[2]  # type: ignore[index]
    add("runtime", OK if pg0_ok else WARN, "Pinned runtime present and checksum matches" if pg0_ok
        else "Runtime binary missing or changed (the database still runs; reinstall to repair)", None if pg0_ok else "install")
    running = ls.is_running(p)
    if not running:
        try:
            ls.ensure_running(p)
            running = True
        except ls.StoreError:
            pass
    add("server", OK if running else FAIL, "Database is running (socket only, no network port)" if running
        else f"Database is not running; log: {p.log}", None if running else "start")
    st = read_state(p)
    prof = st.get("profiles", {}).get(profile, {})
    try:
        provider = current_provider(hermes_bin, profile)
    except Exception as exc:  # noqa: BLE001
        provider = None
        add("provider", FAIL, f"Could not read Hermes config: {exc}")
    if provider is not None:
        if prof.get("enabled") and provider != PROVIDER:
            add("provider", WARN, f"Enabled here, but memory.provider is now '{provider or 'built-in'}' "
                "(changed by hand?)", "enable")
        elif not prof.get("enabled") and provider == PROVIDER:
            add("provider", WARN, "memory.provider says library but the feature is off for this profile", "disable")
        else:
            add("provider", OK, "Memory Library is the active provider" if provider == PROVIDER
                else f"Off; provider stays '{provider or 'built-in'}'")
    plugin = profile_home(hermes_home, profile) / "plugins" / PROVIDER
    if prof.get("enabled"):
        expected = plugin_hash_of_files(plugin_files())
        if not plugin.exists():
            add("plugin", FAIL, "Plugin folder is missing", "enable")
        elif plugin_hash(plugin) != expected:
            add("plugin", WARN, "Plugin files differ from this bridge version (edited, or the bridge was updated)",
                "enable")
        else:
            add("plugin", OK, "Plugin files intact")
    if running and prof.get("enabled"):
        store = ls.Store(p, profile)
        try:
            h = store.health()
            sv = h["schema_version"]
            add("schema", OK if sv == ls.SCHEMA_VERSION else FAIL, f"Schema version {sv}"
                + ("" if sv == ls.SCHEMA_VERSION else f", expected {ls.SCHEMA_VERSION}"), None if sv == ls.SCHEMA_VERSION
                else "migrate")
            add("duplicates", WARN if h["duplicates"] else OK,
                f"{len(h['duplicates'])} near-duplicate pair(s): " + ", ".join(f"#{d['a']}/#{d['b']}" for d in h["duplicates"][:6])
                if h["duplicates"] else "No near-duplicates", "merge" if h["duplicates"] else None)
            add("keywords", WARN if h["missing_keywords"] else OK,
                f"{h['missing_keywords']} memories have no keywords (weaker recall)" if h["missing_keywords"]
                else "Every memory has keywords")
            heavy = [x for x in h["pinned"] if (x["chars"] or 0) > ls.PINNED_MAX_CHARS]
            add("pinned", WARN if heavy else OK,
                "Too many critical memories in " + ", ".join(x["scope"] for x in heavy) + "; only the first ones fit"
                if heavy else "Critical memories fit the always-recall budget")
            add("orphans", OK if not h["orphan_history"] else WARN, f"{h['orphan_history']} orphan history rows")
        except ls.StoreError as exc:
            add("schema", FAIL, f"Database query failed: {exc}", "migrate")
    if prof.get("import_backup"):  # only when the import had files to copy
        b = Path(prof["import_backup"])
        ok = any((b / n).is_file() for n in ("MEMORY.md", "USER.md"))
        add("backup", OK if ok else WARN, f"Built-in memory backup: {b}" if ok
            else "The backup of MEMORY.md / USER.md made at import is missing")
    return {"profile": profile, "checks": checks, "benchmark": st.get("benchmark")}


def plugin_hash_of_files(files: dict[str, bytes]) -> str:
    h = hashlib.sha256()
    names = sorted(n for n in files if n.endswith(".py")) + sorted(n for n in files if n.endswith(".yaml"))
    for n in names:
        h.update(n.encode() + b"\0" + files[n])
    return h.hexdigest()


def fix(hermes_bin: Path, hermes_home: Path, profile: str | None, action: str, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    if action == "start":
        ls.ensure_running(p)
    elif action == "enable":
        return enable(hermes_bin, hermes_home, profile, replace_custom=False, import_memory=False, p=p)
    elif action == "disable":
        return disable(hermes_bin, hermes_home, profile, p)
    elif action == "migrate":
        ls.ensure_running(p)
        ls.Store(p, profile).ensure_database()
    elif action == "merge":
        store = ls.Store(p, profile)
        merged = []
        for d in store.health()["duplicates"]:
            try:
                store.merge(d["a"], d["b"], by="doctor")
                merged.append([d["a"], d["b"]])
            except ls.Invalid:
                continue
        return {"merged": merged}
    elif action == "install":
        install(p, accept=True)
    else:
        raise RuntimeError(f"Unknown fix: {action}")
    return {"ok": True}


# ---------------------------------------------------------------------- settings Hermes reads

SETTINGS = {"smart_recall": False, "session_review": True}


def settings(hermes_bin: Path, profile: str | None) -> dict:
    out = {}
    for key, default in SETTINGS.items():
        raw = _hermes(hermes_bin, profile, "config", "get", f"memory.library.{key}").strip().lower()
        out[key] = default if raw in ("", "none", "null") else raw in ("true", "1", "yes", "on")
    model = _hermes(hermes_bin, profile, "config", "get", f"auxiliary.{AUX_TASK}.model").strip().strip('"')
    prov = _hermes(hermes_bin, profile, "config", "get", f"auxiliary.{AUX_TASK}.provider").strip().strip('"')
    out["model"] = "" if model.lower() in ("", "none", "null", "auto") else model
    out["provider"] = "auto" if prov.lower() in ("", "none", "null") else prov
    return out


def set_settings(hermes_bin: Path, profile: str | None, **values) -> dict:
    for key in SETTINGS:
        if values.get(key) is not None:
            _hermes(hermes_bin, profile, "config", "set", "--force", f"memory.library.{key}",
                    "true" if values[key] else "false")
    if values.get("model") is not None:
        _hermes(hermes_bin, profile, "config", "set", "--force", f"auxiliary.{AUX_TASK}.model", values["model"] or "")
    if values.get("provider") is not None:
        _hermes(hermes_bin, profile, "config", "set", "--force", f"auxiliary.{AUX_TASK}.provider",
                values["provider"] or "auto")
    return settings(hermes_bin, profile)


def status(hermes_bin: Path, hermes_home: Path, profile: str | None, p: ls.Paths | None = None) -> dict:
    p = p or paths()
    st = read_state(p)
    prof = st.get("profiles", {}).get(profile or "default", {})
    installed = ls.is_installed(p)
    return {"supported": why_not() is None, "reason": why_not(), "installed": installed,
            "running": installed and ls.is_running(p), "enabled": bool(prof.get("enabled")),
            "profile": profile or "default", "job": job(), "benchmark": st.get("benchmark"),
            "previous_provider": prof.get("previous_provider") if prof else None}
