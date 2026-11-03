import json
import time

from fastapi.testclient import TestClient

from hermes_remote_bridge import extras as ex
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.monitor import ClaudeUsage, Monitor

from test_app import PHONE, FakeLocator, FakeTailnet, fake_http


def test_fresh_install_starts_with_everything_off(tmp_path):
    s = ex.ExtrasStore(tmp_path / "extras.json", seed=lambda: {})
    assert s.get() == {k: False for k in ex.EXTRAS}
    assert s.created and json.loads((tmp_path / "extras.json").read_text())["monitor"] is False


def test_update_keeps_features_already_in_use(tmp_path):
    (tmp_path / "bp").mkdir(); (tmp_path / "bp" / "p_1.json").write_text("{}")
    (tmp_path / "lib").mkdir(); (tmp_path / "lib" / "state.json").write_text(json.dumps({"profiles": {"default": {"enabled": True}}}))
    seed = ex.seed_in_use(blueprints_dir=tmp_path / "bp", notes_dir=tmp_path / "notes", library_state=tmp_path / "lib" / "state.json")
    assert seed == {"blueprints": True, "notes": False, "library": True}
    s = ex.ExtrasStore(tmp_path / "extras.json", seed=lambda: seed)
    assert s.on("blueprints") and s.on("library") and not s.on("monitor") and not s.on("notes")
    # Once written, the file wins (a later seed is never consulted again).
    assert ex.ExtrasStore(tmp_path / "extras.json", seed=lambda: {"monitor": True}).on("monitor") is False


def test_set_validates_and_reports_changes(tmp_path):
    s = ex.ExtrasStore(tmp_path / "extras.json")
    state, changed = s.set({"monitor": True, "notes": False})
    assert state["monitor"] and changed == {"monitor": True}
    for bad in ({"nope": True}, {"monitor": 1}):
        try:
            s.set(bad)
            raise AssertionError("accepted " + repr(bad))
        except ValueError:
            pass


def test_features_off_hides_their_me_flags():
    off = ex.features_off({"monitor": False, "notes": True})
    assert {"monitor", "claude_usage", "speedtest", "terminal", "library", "blueprints"} <= off and "notes" not in off


def test_sync_skills_disables_skills_of_off_features_and_keeps_the_users_own(tmp_path, monkeypatch):
    home = tmp_path / "hermes"; (home / "profiles" / "work").mkdir(parents=True)
    (home / "profiles" / "work" / "config.yaml").write_text("")
    conf = {None: ["my-own", "project-blueprint"], "work": []}
    calls = []

    class R:
        def __init__(self, out="", rc=0): self.stdout, self.returncode, self.stderr = out, rc, ""

    def fake(_bin, prof, *args):
        calls.append((prof, args))
        if args[:2] == ("config", "get"):
            return R(json.dumps(conf[prof]))
        conf[prof] = json.loads(args[-1]); return R()
    monkeypatch.setattr(ex, "_hermes", fake)
    assert ex.sync_skills(home / "bin", home, {"blueprints": True, "notes": False, "autopilot": True}) == []
    assert conf[None] == ["my-own", "personal-notes"] and conf["work"] == ["personal-notes"]
    calls.clear()
    assert ex.sync_skills(home / "bin", home, {"blueprints": True, "notes": False, "autopilot": True}) == []
    assert all(a[:2] == ("config", "get") for _, a in calls)  # nothing to change: no writes


def _client(tmp_path, monkeypatch, state):
    (tmp_path / "extras.json").write_text(json.dumps(state))
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", monitor_db=tmp_path / "m.db",
                 extras_file=tmp_path / "extras.json", notes_dir=tmp_path / "notes", blueprints_dir=tmp_path / "bp",
                 hermes_home=tmp_path / "hermes")
    monkeypatch.setattr(ex, "sync_skills", lambda *a: [])
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    mon = Monitor(cfg.monitor_db, ClaudeUsage(tmp_path / "missing.json"))
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com", monitor=mon)
    c = TestClient(app, client=(PHONE, 5555))
    c.headers["Authorization"] = f"Bearer {token}"
    return c, mon


def test_off_features_are_refused_hidden_and_not_sampled(tmp_path, monkeypatch):
    c, mon = _client(tmp_path, monkeypatch, {k: False for k in ex.EXTRAS})
    with c:
        feats = c.get("/v1/me").json()["features"]
        assert not {"monitor", "claude_usage", "speedtest", "terminal", "notes", "blueprints", "library"} & set(feats)
        assert "extras" in feats
        for path, code in (("/v1/monitor/now", "monitor_off"), ("/v1/notes", "notes_off"),
                           ("/v1/blueprints", "blueprints_off"), ("/v1/library", "library_off"), ("/v1/claude/usage", "monitor_off")):
            r = c.get(path)
            assert r.status_code == 404 and r.json()["error"]["code"] == code, path
        assert mon.paused
        time.sleep(0.3)
        assert mon.latest == {}  # the sampler read nothing


def test_switching_on_over_the_api_serves_it_and_resumes_sampling(tmp_path, monkeypatch):
    c, mon = _client(tmp_path, monkeypatch, {k: False for k in ex.EXTRAS})
    with c:
        doc = c.put("/v1/extras", json={"monitor": True, "notes": True}).json()
        assert doc["extras"]["monitor"] == {"on": True, "available": True, "label": "PC monitor"}
        assert not mon.paused
        assert c.get("/v1/monitor/now?live=true").status_code == 200
        assert c.get("/v1/notes").status_code == 200
        assert {"monitor", "notes"} <= set(c.get("/v1/me").json()["features"])
        assert c.put("/v1/extras", json={"bogus": True}).status_code == 400
        c.put("/v1/extras", json={"monitor": False})
        assert mon.paused and c.get("/v1/monitor/now").status_code == 404


def test_cli_refuses_commands_of_an_off_feature_and_switches(tmp_path, monkeypatch, capsys):
    import pytest
    from hermes_remote_bridge import cli

    conf = tmp_path / "config.toml"
    # TOML literal strings ('...'): Windows paths have backslashes.
    conf.write_text("".join(f"{k} = '{tmp_path / v}'\n" for k, v in
                            (("extras_file", "x.json"), ("notes_dir", "n"), ("blueprints_dir", "b"), ("library_dir", "l"), ("hermes_home", "h"))))
    monkeypatch.setattr(ex, "sync_skills", lambda *a: [])
    with pytest.raises(SystemExit) as exc:
        cli.main(["--config", str(conf), "notes", "list"])
    assert "switched off" in str(exc.value)
    cli.main(["--config", str(conf), "extras", "notes=on"])
    cli.main(["--config", str(conf), "extras", "--json"])
    assert json.loads(capsys.readouterr().out.strip().splitlines()[-1])["notes"] is True
    cli.main(["--config", str(conf), "notes", "list"])  # allowed now
