"""Bridge configuration: an optional TOML file with safe defaults.

Secrets never live here. Hermes' session token is read from the running backend at runtime and
device tokens are stored only as SHA-256 hashes in devices.json.
"""
from __future__ import annotations

import logging
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


from .host import host

# Per-OS locations: XDG on Linux, %LOCALAPPDATA% on Windows (see host/).
CONFIG_DIR = host().config_dir()
STATE_DIR = host().state_dir()

# Keys of the 0.x bridge (it drove Hermes' HTTP API server and handed off RDP). Still accepted so
# an existing config.toml loads, but they do nothing now.
RETIRED_KEYS = frozenset({"hermes_url", "hermes_env", "krdp_unit", "krdp_port", "runs_per_minute",
                          "run_buffer_events", "run_retention_seconds"})


@dataclass
class Config:
    port: int = 8650
    # "tailscale" expands to this node's Tailscale IPs at startup.
    listen: list[str] = field(default_factory=lambda: ["127.0.0.1", "tailscale"])
    # Serve the local network too, but only on networks marked trusted (see network.py) and only
    # over HTTPS with a certificate the app pins. A LAN peer has no Tailscale identity, so the
    # device token plus that pin are what authenticate the two ends.
    lan: bool = False
    trust_file: Path = CONFIG_DIR / "networks.json"
    tls_dir: Path = CONFIG_DIR / "tls"
    # Proves a request to /v1/local/* comes from a local client (see local_secret.py).
    local_secret_file: Path = CONFIG_DIR / "local-secret"
    # Advertise the bridge over mDNS on trusted networks so the app finds a changed IP.
    mdns: bool = True
    # Hermes' home (spawn ledger, sessions) and install (to start `hermes serve` when the desktop
    # app is not running).
    hermes_home: Path = host().hermes_home()
    hermes_root: Path = host().hermes_root()
    hermes_python: Path = host().hermes_python()
    # Hermes CLI, used only to read/write approvals.mode (its own validated config writer).
    hermes_bin: Path = host().hermes_bin()
    # Start `hermes serve` when the phone connects and no desktop backend is running.
    start_hermes: bool = True
    backend_log: Path = STATE_DIR / "hermes-serve.log"
    # Tailscale login names allowed to connect. Empty means "the owner of this node".
    allowed_logins: list[str] = field(default_factory=list)
    # Named Hermes profiles the phone may switch to. Empty means all of them; the default
    # profile is always available.
    allowed_profiles: list[str] = field(default_factory=list)
    devices_file: Path = CONFIG_DIR / "devices.json"
    audit_log: Path = STATE_DIR / "audit.log"
    max_body_bytes: int = 1_000_000
    # One WebSocket frame from the phone (a prompt, or an image as base64).
    max_frame_bytes: int = 16_000_000
    # PC monitor (stats, history, processes; was PC Glance) and Claude plan usage read with
    # Claude Code's login on this PC. The terminal gives a paired phone a shell as this user.
    monitor: bool = True
    monitor_db: Path = STATE_DIR / "monitor.db"
    claude_usage: bool = True
    claude_credentials: Path = Path.home() / ".claude" / ".credentials.json"
    terminal: bool = True
    # Command line the phone's Terminal opens, e.g. "cmd.exe" or "/usr/bin/fish -l". Empty = the default.
    terminal_shell: str = ""
    requests_per_minute: int = 240
    # `GET /metrics` (Prometheus text): counts and timings, never content. Still needs a device
    # token, or the local metrics token in this file (mode 600, made by `serve`).
    metrics: bool = True
    metrics_token_file: Path = CONFIG_DIR / "metrics.token"
    # Frames per minute on one live connection (the phone's RPCs, not Hermes' events).
    frames_per_minute: int = 600
    # Seconds the bridge keeps Hermes' socket open once the phone's dropped and none of its sessions
    # has work left (a turn, subagent, /loop, /heartbeat or background process keeps it open), so a
    # return re-attaches to the same session (Hermes reaps a detached one after 20 s). 0 turns it off.
    park_seconds: float = 600
    # Release a parked socket after this long even if its sessions still have work.
    park_max_seconds: float = 86400
    # Let a paired phone update a source-installed bridge (git checkout) to the newest release.
    self_update: bool = True
    # Personal notes (the app's Notes page; Hermes reads and edits the same Markdown files).
    notes: bool = True
    # PC bridge admin from a paired phone: doctor with fixes, restart, networks, devices, logs.
    remote_admin: bool = True
    notes_dir: Path = CONFIG_DIR / "notes"
    # Project blueprints (flow diagram + task board per Hermes project), read and edited by Hermes too.
    blueprints: bool = True
    # Tailscale Funnel (the bridge on the public internet): the phone's PC bridge page may switch it on
    # and allow single phones. Off by default in Tailscale itself; false here refuses it entirely.
    funnel: bool = True
    funnel_file: Path = CONFIG_DIR / "funnel.json"
    blueprints_dir: Path = CONFIG_DIR / "blueprints"

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        path = path or CONFIG_DIR / "config.toml"
        cfg = cls()
        if path.exists():
            data = tomllib.loads(path.read_text())
            for key, value in data.items():
                if key in RETIRED_KEYS:
                    logging.getLogger(__name__).info("config.toml: %s is no longer used; ignoring it", key)
                    continue
                if not hasattr(cfg, key):
                    raise ValueError(f"Unknown config key in {path}: {key}")
                current = getattr(cfg, key)
                setattr(cfg, key, Path(value).expanduser() if isinstance(current, Path) else value)
        return cfg
