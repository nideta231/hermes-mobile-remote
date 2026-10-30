"""One bridge version: the release tag, the same everywhere the phone shows it."""
import hermes_remote_bridge
from hermes_remote_bridge import selfupdate


def test_version_is_the_release_tag(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", "v1.40.0")
    assert hermes_remote_bridge._release() == "1.40.0"
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    assert hermes_remote_bridge._release() == "dev"


def test_running_version_is_never_the_old_placeholder():
    assert hermes_remote_bridge.__version__ != "1.0.0"
    tag = selfupdate.RUNNING_TAG
    assert hermes_remote_bridge.__version__ == (tag[1:] if tag else "dev")
