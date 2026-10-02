"""macOS: placeholder so the seam is visible. Not implemented yet.

What works today is what needs no platform code: loopback and Tailscale (through its CLI), the
whole API, pairing and the app. What is missing is the local-network (Wi-Fi) path, so ``lan``
stays off: default route, gateway MAC and Wi-Fi name need ``route``/``arp``/``networksetup``, and
the service wants a launchd agent. See docs/ROADMAP.md.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from .base import Host

_APP_CLI = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"


class MacHost(Host):
    name = "macos"

    def config_dir(self) -> Path:
        return Path.home() / "Library" / "Application Support" / "hermes-remote"

    def state_dir(self) -> Path:
        return Path.home() / "Library" / "Application Support" / "hermes-remote" / "state"

    def tailscale_cli(self) -> str | None:
        return shutil.which("tailscale") or (_APP_CLI if Path(_APP_CLI).exists() else None)
