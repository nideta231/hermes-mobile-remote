"""Everything that differs between operating systems lives behind :func:`host`.

The rest of the bridge asks the host object a question ("which interfaces have a default
route?", "what is the Wi-Fi called?") and never checks ``sys.platform`` itself. Supporting a new
OS means adding one module here and one line in :func:`_load`.
"""
from __future__ import annotations

import sys
from functools import lru_cache

from .base import Host


def _load() -> Host:
    if sys.platform == "win32":
        from .windows import WindowsHost
        return WindowsHost()
    if sys.platform == "darwin":
        from .macos import MacHost
        return MacHost()
    from .linux import LinuxHost
    return LinuxHost()


@lru_cache(maxsize=1)
def host() -> Host:
    return _load()


__all__ = ["Host", "host"]
