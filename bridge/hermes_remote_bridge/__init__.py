def _release() -> str:
    """The one bridge version: the release tag this code was loaded from (git checkout or the desktop
    app's bridge.version), without the "v". Same value the phone's PC bridge page shows."""
    try:
        from .selfupdate import RUNNING_TAG
    except Exception:  # noqa: BLE001 - a version lookup must never stop the bridge
        return "dev"
    return RUNNING_TAG[1:] if RUNNING_TAG and RUNNING_TAG.startswith("v") else (RUNNING_TAG or "dev")


__version__ = _release()
