"""Self-update: version picking, the check, and the launch (all without network or systemd)."""
import subprocess

from hermes_remote_bridge import selfupdate


def test_latest_tag_is_by_version_not_text():
    assert selfupdate.latest_tag(["v1.9.0", "v1.10.0", "v1.28.1", "desktop-v0.3.0", "v1.28.0"]) == "v1.28.1"
    assert selfupdate.latest_tag(["junk"]) is None


def test_check_reports_an_available_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", None)
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.27.0", "v1.29.0"])
    monkeypatch.setattr(selfupdate, "_head", lambda: ("cccc333", "v1.28.1"))
    info = selfupdate.check()
    assert info["current"] == "v1.28.1" and info["latest"] == "v1.29.0"
    assert info["available"] and info["can_update"]


def test_check_never_offers_an_older_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", None)
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.20.0"])
    monkeypatch.setattr(selfupdate, "_head", lambda: ("cccc333", "v1.28.1"))
    assert selfupdate.check()["available"] is False


def test_update_runs_in_its_own_unit_outside_the_bridge():
    argv = selfupdate.launch_argv("/py")
    assert argv[:3] == ["systemd-run", "--user", "--quiet"]
    assert any(a.startswith("--unit=hermes-remote-update-") for a in argv)
    assert argv[-2:] == ["update", "--apply"]


def test_cached_check_reuses_one_network_call():
    calls = []
    cc = selfupdate.CachedCheck(ttl=999, fn=lambda: calls.append(1) or {"available": False})
    cc.get(); cc.get()
    assert len(calls) == 1
    cc.get(force=True)
    assert len(calls) == 2




def test_bridge_running_old_code_reports_a_restart(monkeypatch):
    """git pull without a restart: files are new, the process is not. Report what runs."""
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", "aaaa111")
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", "v1.29.0")
    monkeypatch.setattr(selfupdate, "_head", lambda: ("bbbb222", "v1.31.0"))
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.31.0"])
    info = selfupdate.check()
    assert info["current"] == "v1.29.0" and info["restart_needed"]
    assert info["available"] and info["can_update"] and info["latest"] == "v1.31.0"


def test_local_changes_still_allow_a_plain_restart(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", "aaaa111")
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", "v1.29.0")
    monkeypatch.setattr(selfupdate, "_head", lambda: ("bbbb222", "v1.31.0"))
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": "local changes")
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.31.0"])
    assert selfupdate.check()["can_update"]


import io
import os
import tarfile

import pytest


@pytest.fixture
def app_install(tmp_path, monkeypatch):
    """A desktop-app layout: <data>/bridge (no .git) and <data>/venv."""
    (tmp_path / "bridge").mkdir()
    (tmp_path / "bridge" / "old.txt").write_text("old")
    monkeypatch.setattr(selfupdate, "REPO", tmp_path)
    monkeypatch.setattr(selfupdate, "BRIDGE_DIR", tmp_path / "bridge")
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", None)
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    monkeypatch.setattr(selfupdate.shutil, "which", lambda name: "/usr/bin/" + name)
    return tmp_path


def _archive(tmp_path, members):
    path = tmp_path / "src.tar.gz"
    with tarfile.open(path, "w:gz") as tf:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tf.addfile(info, io.BytesIO(data))
    return path


GOOD = {"proj-1.2.0/bridge/new.txt": b"new", "proj-1.2.0/bridge/tests/t.py": b"t",
        "proj-1.2.0/bridge/__pycache__/x.pyc": b"x", "proj-1.2.0/docs/a.md": b"doc"}


def _stub_apply(monkeypatch, archive, sync_rc=(0,), healthy=(True, True)):
    syncs, sync_rcs, health = [], list(sync_rc), list(healthy)
    monkeypatch.setattr(selfupdate, "_api_tags", lambda: ["v1.1.0", "v1.2.0"])
    monkeypatch.setattr(selfupdate, "_fetch_archive", lambda tag: archive)
    monkeypatch.setattr(selfupdate, "_uv", lambda: "/uv")
    monkeypatch.setattr(selfupdate.subprocess, "run", lambda argv, **kw: syncs.append((argv, kw, (selfupdate.BRIDGE_DIR / "new.txt").exists()))
                        or subprocess.CompletedProcess(argv, sync_rcs.pop(0) if sync_rcs else 0, "", "boom"))
    for name in ("_render_unit", "install_skills"):
        monkeypatch.setattr(selfupdate, name, lambda *a, **k: None)
    monkeypatch.setattr(selfupdate, "keep_hermes_alive", lambda: 0)
    monkeypatch.setattr(selfupdate, "_systemctl", lambda *a: subprocess.CompletedProcess(a, 0, "", ""))
    monkeypatch.setattr(selfupdate, "bridge_healthy", lambda *a, **k: health.pop(0))
    monkeypatch.setattr(selfupdate, "_write_status", lambda **k: None)
    return syncs


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
def test_app_install_checks_github_tags(app_install, monkeypatch):
    monkeypatch.setattr(selfupdate, "_api_tags", lambda: ["v1.1.0", "v1.2.0", "junk"])
    info = selfupdate.check()
    assert info["can_update"] and info["available"] and info["latest"] == "v1.2.0" and info["current"] is None

    def offline():
        raise RuntimeError("no network")
    monkeypatch.setattr(selfupdate, "_api_tags", offline)
    info = selfupdate.check()
    assert not info["can_update"] and "reach the update server" in info["reason"]


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
def test_app_install_apply_swaps_dir_and_syncs_existing_venv(app_install, monkeypatch):
    syncs = _stub_apply(monkeypatch, _archive(app_install, GOOD))
    assert selfupdate.apply(log=lambda m: None) == "v1.2.0"
    bridge = app_install / "bridge"
    assert (bridge / "new.txt").read_text() == "new" and not (bridge / "old.txt").exists()
    assert not (bridge / "tests").exists() and not (bridge / "__pycache__").exists()
    assert not (bridge / "docs").exists() and not (app_install / "docs").exists()
    assert (app_install / "bridge.old" / "old.txt").read_text() == "old"
    assert selfupdate._installed_tag() == "v1.2.0"
    argv, kw, new_in_place = syncs[0]
    assert argv[:2] == ["/uv", "sync"] and "--frozen" in argv and "--no-dev" in argv and new_in_place
    assert kw["env"]["UV_PROJECT_ENVIRONMENT"] == str(app_install / "venv")


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
@pytest.mark.parametrize("sync_rc,healthy", [((1, 0), (True,)), ((0, 0), (False, True))])
def test_app_install_failure_restores_old_dir_and_resyncs(app_install, monkeypatch, sync_rc, healthy):
    syncs = _stub_apply(monkeypatch, _archive(app_install, GOOD), sync_rc=sync_rc, healthy=healthy)
    with pytest.raises(RuntimeError):
        selfupdate.apply(log=lambda m: None)
    bridge = app_install / "bridge"
    assert (bridge / "old.txt").exists() and not (bridge / "new.txt").exists()
    assert len(syncs) == 2 and not syncs[1][2]
    assert selfupdate._installed_tag() is None


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
def test_app_install_error_after_the_swap_restores_old_dir_and_resyncs(app_install, monkeypatch):
    syncs = _stub_apply(monkeypatch, _archive(app_install, GOOD))

    def boom(log=print):
        raise OSError("skills broke")
    monkeypatch.setattr(selfupdate, "install_skills", boom)
    with pytest.raises(RuntimeError, match="skills broke"):
        selfupdate.apply(log=lambda m: None)
    bridge = app_install / "bridge"
    assert (bridge / "old.txt").exists() and not (bridge / "new.txt").exists()
    assert len(syncs) == 2 and not syncs[1][2]
    assert selfupdate._installed_tag() is None


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
def test_app_install_failed_second_rename_leaves_old_dir_in_place(app_install, monkeypatch):
    syncs = _stub_apply(monkeypatch, _archive(app_install, GOOD))
    real = selfupdate.Path.rename

    def rename(self, target):
        if self.name == "bridge.new":
            raise OSError("rename failed")
        return real(self, target)
    monkeypatch.setattr(selfupdate.Path, "rename", rename)
    with pytest.raises(RuntimeError, match="rename failed"):
        selfupdate.apply(log=lambda m: None)
    assert (app_install / "bridge" / "old.txt").exists() and not (app_install / "bridge" / "new.txt").exists()
    assert selfupdate._installed_tag() is None


@pytest.mark.skipif(os.name == "nt", reason="desktop-app self-update is Linux only; Windows updates with the app")
def test_app_install_refuses_unsafe_archive_members(app_install, monkeypatch):
    evil = _archive(app_install, {"proj/bridge/../../evil.txt": b"x", "proj/bridge/ok.txt": b"ok"})
    _stub_apply(monkeypatch, evil)
    with pytest.raises(RuntimeError, match="unsafe"):
        selfupdate.apply(log=lambda m: None)
    assert not (app_install / "evil.txt").exists() and (app_install / "bridge" / "old.txt").exists()


def test_install_skills_copies_missing_only(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.setattr("hermes_remote_bridge.config.Config.load", classmethod(lambda cls, p=None: (_ for _ in ()).throw(OSError())))
    mine = tmp_path / "skills" / "productivity" / "personal-notes" / "SKILL.md"
    mine.parent.mkdir(parents=True)
    mine.write_text("my edits")
    assert selfupdate.install_skills(log=lambda m: None) == ["project-blueprint"]
    assert mine.read_text() == "my edits"
    assert (tmp_path / "skills" / "software-development" / "project-blueprint" / "SKILL.md").exists()
    assert selfupdate.install_skills(log=lambda m: None) == []


def test_app_install_leaves_the_desktop_apps_unit_alone(app_install, tmp_path, monkeypatch):
    unit_dir = tmp_path / "xdg" / "systemd" / "user"
    unit_dir.mkdir(parents=True)
    unit = unit_dir / selfupdate.UNIT
    unit.write_text("[Service]\nEnvironment=HERMES_REMOTE_NO_TRUST_PROMPT=1\n")
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    calls = []
    monkeypatch.setattr(selfupdate, "_systemctl", lambda *a: calls.append(a))
    selfupdate._render_unit()
    assert unit.read_text() == "[Service]\nEnvironment=HERMES_REMOTE_NO_TRUST_PROMPT=1\n" and calls == []
