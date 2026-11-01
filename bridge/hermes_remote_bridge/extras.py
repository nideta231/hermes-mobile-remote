"""Additional features: optional parts of the bridge the user switches on (phone Settings > Additional
features, or `hermes-remote-bridge extras`). Off means really off on this PC, not only hidden on the
phone: the routes refuse, the PC monitor stops sampling, the Memory Library is switched off in every
profile (memories kept) and its database stopped, and the Hermes skills that drive a feature are put in
Hermes' `skills.disabled`, so Hermes never uses a feature the user did not turn on.

Stored in `extras.json` next to config.toml. A fresh install starts with everything off; an install
from before this file existed keeps the features it already has data for (boards, notes, an enabled
Memory Library), so updating never takes away something in use.
"""
from __future__ import annotations

import json
import logging
import os
import subprocess
import tempfile
import threading
from pathlib import Path

log = logging.getLogger(__name__)

# key -> (label, Hermes skills that use it)
EXTRAS: dict[str, tuple[str, tuple[str, ...]]] = {
    "library": ("Memory Library", ()),
    "monitor": ("PC monitor", ()),
    "terminal": ("Terminal", ()),
    "notes": ("Notes", ("personal-notes",)),
    "blueprints": ("Blueprints", ("project-blueprint",)),
    "graph": ("Graph", ()),
}
# /v1/me features each extra brings (graph has none: the app draws it from the chat list).
FEATURES: dict[str, tuple[str, ...]] = {
    "library": ("library",), "monitor": ("monitor", "claude_usage", "speedtest"),
    "terminal": ("terminal",), "notes": ("notes",), "blueprints": ("blueprints",), "graph": (),
}


class ExtrasStore:
    def __init__(self, path: Path, *, seed=None) -> None:
        self.path = Path(path)
        self._lock = threading.Lock()
        self._seed = seed  # () -> dict of extras already in use, for an install that predates the file
        self._state: dict[str, bool] | None = None
        self.created = False  # this run wrote the file for the first time (seeded)

    def get(self) -> dict[str, bool]:
        with self._lock:
            if self._state is None:
                self._state = self._load()
            return dict(self._state)

    def on(self, key: str) -> bool:
        return self.get().get(key, False)

    def _load(self) -> dict[str, bool]:
        try:
            saved = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(saved, dict):
                return {k: saved.get(k) is True for k in EXTRAS}
        except FileNotFoundError:
            state = {k: False for k in EXTRAS}
            if self._seed is not None:
                try:
                    state.update({k: True for k, v in (self._seed() or {}).items() if k in EXTRAS and v})
                except Exception:  # noqa: BLE001 - a failed probe only means "start off"
                    log.exception("could not tell which additional features are in use")
            self._write(state)
            self.created = True
            return state
        except (OSError, ValueError):
            pass
        return {k: False for k in EXTRAS}

    def set(self, changes: dict) -> tuple[dict[str, bool], dict[str, bool]]:
        """Apply {key: bool}; unknown keys or non-bools are an error. Returns (new state, what changed)."""
        bad = [k for k, v in changes.items() if k not in EXTRAS or not isinstance(v, bool)]
        if bad:
            raise ValueError(f"Unknown or not true/false: {', '.join(map(str, bad))} (known: {', '.join(EXTRAS)})")
        with self._lock:
            cur = self._state if self._state is not None else self._load()
            changed = {k: v for k, v in changes.items() if cur.get(k) != v}
            self._state = {**cur, **changes}
            self._write(self._state)
            return dict(self._state), changed

    def _write(self, state: dict[str, bool]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.path.parent, prefix=".extras-")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(state, fh, indent=2)
        os.replace(tmp, self.path)


def features_off(state: dict[str, bool]) -> set[str]:
    """/v1/me feature names to leave out because their extra is off."""
    return {f for k, fs in FEATURES.items() if not state.get(k) for f in fs}


def describe(state: dict[str, bool], available: dict[str, bool]) -> dict:
    return {k: {"on": state.get(k, False), "available": available.get(k, True), "label": EXTRAS[k][0]} for k in EXTRAS}


def seed_in_use(*, blueprints_dir: Path, notes_dir: Path, library_state: Path) -> dict[str, bool]:
    """Extras an existing install already uses, so the first start after an update keeps them on."""
    lib = False
    try:
        profiles = json.loads(Path(library_state).read_text()).get("profiles", {})
        lib = any(p.get("enabled") for p in profiles.values())
    except (OSError, ValueError, AttributeError):
        pass
    boards = any(p.suffix == ".json" for p in Path(blueprints_dir).glob("*.json")) if Path(blueprints_dir).is_dir() else False
    notes = any(Path(notes_dir).glob("*.md")) if Path(notes_dir).is_dir() else False
    return {"blueprints": boards, "notes": notes, "library": lib}


# ---------------------------------------------------------------------- Hermes skills

def profile_names(hermes_home: Path) -> list[str | None]:
    """None = the default profile, then every named profile that has a config."""
    names: list[str | None] = [None]
    d = Path(hermes_home) / "profiles"
    if d.is_dir():
        names += sorted(p.name for p in d.iterdir() if (p / "config.yaml").exists())
    return names


def _hermes(hermes_bin: Path, profile: str | None, *args: str) -> subprocess.CompletedProcess:
    argv = [str(hermes_bin), *(("-p", profile) if profile else ()), *args]
    return subprocess.run(argv, capture_output=True, text=True, timeout=30, stdin=subprocess.DEVNULL)


def disabled_skills(hermes_bin: Path, profile: str | None) -> list[str]:
    r = _hermes(hermes_bin, profile, "config", "get", "--json", "skills.disabled")
    if r.returncode != 0:
        return []  # not set
    try:
        value = json.loads(r.stdout.strip().splitlines()[0])
    except (ValueError, IndexError):
        return []
    return [str(s) for s in value] if isinstance(value, list) else []


def sync_skills(hermes_bin: Path, hermes_home: Path, state: dict[str, bool]) -> list[str]:
    """Put the skills of every off extra in `skills.disabled` (every profile) and take the on ones out.
    Other entries the user disabled stay as they are. Returns problems, one line each."""
    off = {s for k, (_, skills) in EXTRAS.items() if not state.get(k) for s in skills}
    ours = {s for _, skills in EXTRAS.values() for s in skills}
    problems = []
    for prof in profile_names(hermes_home):
        try:
            cur = disabled_skills(hermes_bin, prof)
            new = sorted((set(cur) - ours) | off)
            if sorted(cur) == new:
                continue
            r = _hermes(hermes_bin, prof, "config", "set", "--force", "skills.disabled", json.dumps(new))
            if r.returncode != 0:
                problems.append(f"{prof or 'default'}: {(r.stderr or r.stdout).strip()[-200:]}")
        except (OSError, subprocess.SubprocessError) as exc:
            problems.append(f"{prof or 'default'}: {exc}")
    return problems


# ---------------------------------------------------------------------- applying a change

def pause_library(hermes_bin: Path, hermes_home: Path, library_dir: Path) -> list[str]:
    """Memory Library off: switch it off in every profile that uses it (memories kept, the previous
    provider restored), remember those profiles, stop its database."""
    from . import library as lib
    from . import library_store as ls

    p = lib.paths(library_dir)
    st = lib.read_state(p)
    on = [name for name, info in st.get("profiles", {}).items() if info.get("enabled")]
    problems = []
    for name in on:
        try:
            lib.disable(hermes_bin, hermes_home, name, p)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"Memory Library, {name}: {exc}")
    if on:
        lib._update_state(p, paused_profiles=sorted(set(st.get("paused_profiles", [])) | set(on)))
    if ls.is_installed(p):
        try:
            ls.stop(p)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"Memory Library database: {exc}")
    return problems


def resume_library(hermes_bin: Path, hermes_home: Path, library_dir: Path) -> list[str]:
    """Memory Library back on: switch it on again in the profiles [pause_library] switched off."""
    from . import library as lib

    p = lib.paths(library_dir)
    names = lib.read_state(p).get("paused_profiles", [])
    problems = []
    for name in names:
        try:
            lib.enable(hermes_bin, hermes_home, name, import_memory=False, p=p)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"Memory Library, {name}: {exc}")
    if names:
        lib._update_state(p, paused_profiles=[])
    return problems


def apply(cfg, state: dict[str, bool], changed: dict[str, bool], monitor=None) -> list[str]:
    """Make the PC follow [state]: monitor sampling, Memory Library, Hermes skills. Returns problems."""
    if monitor is not None:
        monitor.paused = not state.get("monitor")
    problems = []
    if "library" in changed and cfg.library:
        fn = resume_library if changed["library"] else pause_library
        problems += fn(cfg.hermes_bin, cfg.hermes_home, cfg.library_dir)
    if any(EXTRAS[k][1] for k in changed) or not changed:
        problems += sync_skills(cfg.hermes_bin, cfg.hermes_home, state)
    return problems


def store_for(cfg) -> ExtrasStore:
    from .library import paths as lib_paths

    return ExtrasStore(cfg.extras_file, seed=lambda: seed_in_use(
        blueprints_dir=cfg.blueprints_dir, notes_dir=cfg.notes_dir, library_state=lib_paths(cfg.library_dir).state))
