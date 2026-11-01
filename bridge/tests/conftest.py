"""Keep every test away from the user's real config and state: point the XDG dirs (and Windows'
LOCALAPPDATA) at a throwaway folder before any bridge module computes its paths at import."""
import os
import tempfile

_ROOT = tempfile.mkdtemp(prefix="hermes-remote-tests-")
for _k, _sub in (("XDG_CONFIG_HOME", "config"), ("XDG_STATE_HOME", "state"), ("XDG_DATA_HOME", "data"),
                 ("LOCALAPPDATA", "localappdata")):
    os.environ[_k] = os.path.join(_ROOT, _sub)

os.environ["HERMES_HOME"] = os.path.join(_ROOT, "hermes")

# Tests predate the Additional features switches: start with every extra on (test_extras covers off).
import json as _json  # noqa: E402

os.makedirs(os.path.join(_ROOT, "config", "hermes-remote"), exist_ok=True)
with open(os.path.join(_ROOT, "config", "hermes-remote", "extras.json"), "w") as _fh:
    _json.dump({k: True for k in ("library", "monitor", "terminal", "notes", "blueprints", "graph")}, _fh)
