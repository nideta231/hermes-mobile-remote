"""Bridge configuration: an optional TOML file with safe defaults.

Secrets never live here. The Hermes API key is read from Hermes' own .env at
runtime, and device tokens are stored only as SHA-256 hashes in devices.json.
"""
from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path


from .host import host

# Per-OS locations: XDG on Linux, %LOCALAPPDATA% on Windows (see host/).
CONFIG_DIR = host().config_dir()
STATE_DIR = host().state_dir()


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
    # Advertise the bridge over mDNS on trusted networks so the app finds a changed IP.
    mdns: bool = True
    hermes_url: str = "http://127.0.0.1:8642"
    hermes_env: Path = host().hermes_home() / ".env"
    # Hermes CLI, used only to read/write approvals.mode (its own validated config writer).
    hermes_bin: Path = host().hermes_bin()
    # Hermes source checkout and its Python, used to run slash commands in Hermes' TUI gateway.
    hermes_root: Path = host().hermes_root()
    hermes_python: Path = host().hermes_python()
    # Tailscale login names allowed to connect. Empty means "the owner of this node".
    allowed_logins: list[str] = field(default_factory=list)
    krdp_unit: str = "app-org.kde.krdpserver.service"
    krdp_port: int = 3389
    devices_file: Path = CONFIG_DIR / "devices.json"
    audit_log: Path = STATE_DIR / "audit.log"
    max_body_bytes: int = 1_000_000
    requests_per_minute: int = 240
    runs_per_minute: int = 20
    run_buffer_events: int = 20_000
    run_retention_seconds: int = 1800

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        path = path or CONFIG_DIR / "config.toml"
        cfg = cls()
        if path.exists():
            data = tomllib.loads(path.read_text())
            for key, value in data.items():
                if not hasattr(cfg, key):
                    raise ValueError(f"Unknown config key in {path}: {key}")
                current = getattr(cfg, key)
                setattr(cfg, key, Path(value).expanduser() if isinstance(current, Path) else value)
        return cfg


def read_hermes_api_key(env_path: Path) -> str:
    """Return the last API_SERVER_KEY assignment from Hermes' .env (never logged)."""
    key = ""
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[7:].lstrip()
        if line.startswith("API_SERVER_KEY="):
            key = line.split("=", 1)[1].strip().strip("'\"")
    if not key:
        raise RuntimeError(f"API_SERVER_KEY not set in {env_path}")
    return key
