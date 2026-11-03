import datetime as dt
import json
import sqlite3
import time

import pytest

from hermes_remote_bridge import autopilot as ap


def settings(**kw):
    return {**ap.DEFAULTS, "enabled": True, **kw}


def at(h, m=0):
    return dt.datetime(2026, 1, 1, h, m)


def test_window_wraps_midnight():
    assert ap.in_window(at(23, 30), "23:00", "06:00")
    assert ap.in_window(at(2), "23:00", "06:00")
    assert not ap.in_window(at(6), "23:00", "06:00")
    assert not ap.in_window(at(14), "23:00", "06:00")
    assert ap.in_window(at(10), "09:00", "17:00") and not ap.in_window(at(18), "09:00", "17:00")


def decide(s, *, hour=14, idle_min=5, usage=None, running=False, now=1_000_000.0):
    return ap.decide(s, now=now, last_active=now - idle_min * 60, usage=usage, running=running, tz_now=at(hour))


def test_off_paused_and_running_never_wake():
    assert not decide({**ap.DEFAULTS}, hour=2)["wake"]
    assert not decide(settings(paused=True), hour=2)["wake"]
    assert decide(settings(), hour=2, running=True)["reason"] == "A run is still going"


def test_window_or_idle_wakes_busy_day_does_not():
    assert decide(settings(), hour=2)["wake"]
    assert decide(settings(), hour=14, idle_min=50)["wake"]
    r = decide(settings(), hour=14, idle_min=10)
    assert not r["wake"] and "In use" in r["reason"]


def test_usage_guard_is_off_by_default_and_wins_when_set():
    assert decide(settings(), hour=2, usage={"session": 99, "weekly": 99})["wake"]  # guard off (100)
    hi5 = {"session": 71, "weekly": 10}
    hiw = {"session": 1, "weekly": 85}
    g = dict(session_max=70, weekly_max=85)
    assert "short window" in decide(settings(**g), hour=2, usage=hi5)["reason"]
    assert "weekly" in decide(settings(**g), hour=2, usage=hiw)["reason"]
    now = 1_000_000.0
    manual = settings(manual_until=now + 600, **g)
    assert not decide(manual, usage=hi5, now=now)["wake"]
    assert decide(manual, usage={"session": 10, "weekly": 10}, now=now)["reason"] == "Run now (manual)"


def test_settings_validation(tmp_path):
    s = ap.Settings(tmp_path)
    assert s.get()["enabled"] is False
    assert s.set({"idle_minutes": 30, "window_start": "1:5"})["window_start"] == "01:05"
    for bad in ({"nope": 1}, {"enabled": 1}, {"session_max": 0}, {"idle_minutes": True},
                {"window_end": "25:00"}, {"lab_dir": "/home"}):
        with pytest.raises(ap.AutopilotError):
            s.set(bad)
    assert ap.Settings(tmp_path).get()["idle_minutes"] == 30


def test_journal_and_stats(tmp_path):
    p = ap.paths(tmp_path)
    ap.log(p, "start", "woke up")
    ap.log(p, "build", "made a thing", title="Thing", files=["a.txt"])
    with pytest.raises(ap.AutopilotError):
        ap.log(p, "bogus", "x")
    rows = ap.journal(p)
    assert [r["kind"] for r in rows] == ["build", "start"] and rows[0]["files"] == ["a.txt"]
    assert ap.journal(p, kinds=["start"])[0]["text"] == "woke up"
    st = ap.stats(p)
    assert st["runs_today"] == 1 and st["built_today"] == 1 and st["last"]["kind"] == "build"


def test_run_lock_goes_stale(tmp_path):
    p = ap.paths(tmp_path)
    assert ap.run_state(p) is None
    ap.begin_run(p, "s1")
    assert ap.run_state(p)["session"] == "s1"
    assert ap.run_state(p, now=time.time() + ap.RUN_STALE_SECONDS + 5) is None
    ap.end_run(p)
    assert ap.run_state(p) is None


def test_last_activity_ignores_cron(tmp_path):
    con = sqlite3.connect(tmp_path / "state.db")
    con.executescript("create table sessions(id text, source text); create table messages(session_id text, timestamp real);"
                      "insert into sessions values ('a','desktop'),('b','cron');"
                      "insert into messages values ('a', 100), ('b', 900);")
    con.commit(); con.close()
    assert ap.last_activity(tmp_path) == 100
    assert ap.last_activity(tmp_path / "missing") is None


def test_usage_cache(tmp_path):
    p = ap.paths(tmp_path)
    calls = []
    fetch = lambda: calls.append(1) or {"ok": True, "session": 5, "weekly": 6}
    assert ap.cached_usage(p, fetch, now=1000)["session"] == 5
    assert ap.cached_usage(p, fetch, now=1100)["session"] == 5 and len(calls) == 1
    def boom(): raise RuntimeError("x")
    assert ap.cached_usage(p, boom, now=99999)["session"] == 5  # stale but known beats nothing


def test_skill_disable_is_journaled_and_reversible(tmp_path, monkeypatch):
    p = ap.paths(tmp_path)
    state = {"disabled": ["users-own"]}
    monkeypatch.setattr("hermes_remote_bridge.extras.disabled_skills", lambda _b, _p: list(state["disabled"]))

    class R:
        returncode, stdout, stderr = 0, "", ""

    def fake(_bin, *args, timeout=60):
        state["disabled"] = json.loads(args[-1])
        return R()
    monkeypatch.setattr(ap, "_hermes", fake)
    with pytest.raises(ap.AutopilotError):
        ap.set_skill(p, ap.cli_toggle(tmp_path), "autopilot", False, "x")
    with pytest.raises(ap.AutopilotError):
        ap.set_skill(p, ap.cli_toggle(tmp_path), "old-skill", False, "  ")
    ap.set_skill(p, ap.cli_toggle(tmp_path), "old-skill", False, "unused for 60 days")
    assert state["disabled"] == ["old-skill", "users-own"]
    assert [d["skill"] for d in ap.disabled_by_autopilot(p)] == ["old-skill"]
    ap.set_skill(p, ap.cli_toggle(tmp_path), "old-skill", True, "", by="phone")
    assert state["disabled"] == ["users-own"] and ap.disabled_by_autopilot(p) == []


def test_lab_projects(tmp_path):
    (tmp_path / "b").mkdir(); (tmp_path / "b" / "README.md").write_text("# Tiny timer\nmore")
    (tmp_path / ".hidden").mkdir()
    rows = ap.lab_projects(tmp_path)
    assert [r["name"] for r in rows] == ["b"] and rows[0]["summary"] == "Tiny timer"


# ---------------------------------------------------------------------- routes

from fastapi.testclient import TestClient  # noqa: E402

from hermes_remote_bridge.app import create_app  # noqa: E402
from hermes_remote_bridge.config import Config  # noqa: E402
from hermes_remote_bridge.devices import DeviceStore  # noqa: E402

from test_app import PHONE, FakeLocator, FakeTailnet, fake_http  # noqa: E402


def make_client(tmp_path, extras_on=True, calls=None):
    home = tmp_path / "hermes"; (home / "cron").mkdir(parents=True)
    (home / "cron" / "jobs.json").write_text(json.dumps({"jobs": [{"id": "j1", "name": "autopilot", "enabled": False}]}))
    (tmp_path / "extras.json").write_text(json.dumps({"autopilot": extras_on}))
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", extras_file=tmp_path / "extras.json",
                 state_dir=tmp_path / "state", hermes_home=home, claude_credentials=tmp_path / "none.json",
                 monitor=False)
    ap.Settings(cfg.state_dir).set({"lab_dir": str(tmp_path / "lab" / "x")})
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(calls=calls), tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = TestClient(app, client=(PHONE, 5555))
    c.headers["Authorization"] = f"Bearer {token}"
    return c, cfg


def test_routes_refuse_when_off(tmp_path):
    c, _ = make_client(tmp_path, extras_on=False)
    assert c.get("/v1/autopilot").json()["error"]["code"] == "autopilot_off"
    assert "autopilot" not in c.get("/v1/me").json()["features"]


def test_status_settings_run_stop(tmp_path):
    calls = []
    c, cfg = make_client(tmp_path, calls=calls)
    assert "autopilot" in c.get("/v1/me").json()["features"]
    doc = c.get("/v1/autopilot").json()
    assert doc["job"]["id"] == "j1" and "decision" in doc and doc["settings"]["enabled"] is True
    r = c.put("/v1/autopilot/settings", json={"idle_minutes": 30, "enabled": False})
    assert r.json()["settings"]["idle_minutes"] == 30
    assert c.put("/v1/autopilot/settings", json={"session_max": 0}).status_code == 400
    c.post("/v1/autopilot/run")
    paths_hit = [(q.method, q.url.path) for q in calls]
    assert ("POST", "/api/cron/jobs/j1/resume") in paths_hit and ("POST", "/api/cron/jobs/j1/trigger") in paths_hit
    assert ap.Settings(cfg.state_dir).get()["manual_until"] > time.time()
    assert c.post("/v1/autopilot/stop").json()["settings"]["paused"] is True


def test_journal_and_skill_toggle(tmp_path):
    calls = []
    c, cfg = make_client(tmp_path, calls=calls)
    p = ap.paths(cfg.state_dir)
    ap.log(p, "skill", "unused", title="Disabled skill old", data={"op": "disable", "skill": "old"})
    assert c.get("/v1/autopilot/journal").json()["entries"][0]["title"] == "Disabled skill old"
    assert c.post("/v1/autopilot/skills/old", json={"enabled": True}).status_code == 200
    assert ("PUT", "/api/skills/toggle") in [(q.method, q.url.path) for q in calls]
    assert ap.disabled_by_autopilot(p) == []
    assert c.post("/v1/autopilot/skills/autopilot", json={"enabled": False, "why": "x"}).status_code == 400


def test_lab_project_stays_inside(tmp_path):
    c, cfg = make_client(tmp_path)
    lab = tmp_path / "lab" / "x" / "tool"; lab.mkdir(parents=True); (lab / "README.md").write_text("# Tool\nhi")
    doc = c.get("/v1/autopilot/lab/tool").json()
    assert doc["readme"].startswith("# Tool") and doc["files"][0]["path"] == "README.md"
    assert c.get("/v1/autopilot/lab/.hidden").status_code == 404
    assert c.get("/v1/autopilot/lab/%2e%2e").status_code == 404
    assert c.get("/v1/autopilot/lab/missing").status_code == 404


def test_gate_off_and_on(tmp_path):
    home = tmp_path / "h"; home.mkdir()
    wake, out = ap.gate(tmp_path, home, enabled=False, fetch_usage=lambda: 1 / 0)
    assert not wake and json.loads(out)["wakeAgent"] is False
    ap.Settings(tmp_path).set({"lab_dir": str(tmp_path / "lab" / "y"), "window_start": "00:00", "window_end": "00:00"})
    wake, out = ap.gate(tmp_path, home, enabled=True, fetch_usage=lambda: {"ok": True, "session": 10, "weekly": 5})
    assert wake and "Lab folder" in out and (tmp_path / "lab" / "y").is_dir()
    assert ap.run_state(ap.paths(tmp_path)) is not None
    wake, out = ap.gate(tmp_path, home, enabled=True, fetch_usage=lambda: {"ok": True, "session": 10, "weekly": 5})
    assert not wake  # a run is going


def test_lab_file_stays_inside(tmp_path):
    c, cfg = make_client(tmp_path)
    lab = tmp_path / "lab" / "x" / "tool"; (lab / "src").mkdir(parents=True)
    (lab / "src" / "main.py").write_text("print(1)")
    (lab / ".env").write_text("SECRET=1")
    (tmp_path / "lab" / "x" / "other.txt").write_text("nope")
    assert c.get("/v1/autopilot/lab/tool/file", params={"path": "src/main.py"}).json()["text"] == "print(1)"
    for bad in (".env", "../other.txt", "/etc/passwd", "src/../../other.txt", ""):
        assert c.get("/v1/autopilot/lab/tool/file", params={"path": bad}).status_code == 404, bad


def _fake_state(home, sid, calls):
    import sqlite3 as sq
    home.mkdir(parents=True, exist_ok=True)
    con = sq.connect(home / "state.db")
    con.executescript("create table if not exists sessions(id text, source text, started_at real);"
                      "create table if not exists messages(id integer primary key, session_id text, tool_calls text, timestamp real);")
    con.execute("insert into sessions values (?, 'cron', 1)", (sid,))
    con.execute("insert into messages(session_id, tool_calls, timestamp) values (?, ?, 1)", (sid, json.dumps(
        [{"function": {"name": n, "arguments": json.dumps(a)}} for n, a in calls])))
    con.commit(); con.close()


def test_guardian_pauses_on_a_broken_rule_and_notes_peeking(tmp_path):
    home = tmp_path / "h"; lab = tmp_path / "lab"; work = tmp_path / "Workspace"
    ap.Settings(tmp_path).set({"lab_dir": str(lab)})
    _fake_state(home, "cron_j1_a", [("terminal", {"command": f"ls {lab}"}), ("write_file", {"path": str(lab / "x.py")})])
    _fake_state(home, "cron_j1_b", [("terminal", {"command": f"grep -r foo {work}/client/app"})])
    _fake_state(home, "cron_j1_c", [("skill_manage", {"operations": [{"name": "client-skill", "action": "patch"}]}),
                                    ("terminal", {"command": "git push origin main"})])
    found = {f["session"]: f for f in ap.guardian(tmp_path, home, "j1", [work])}
    assert found["cron_j1_a"] == {"session": "cron_j1_a", "severe": [], "warnings": []}
    assert found["cron_j1_b"]["warnings"] and not found["cron_j1_b"]["severe"]
    assert any("client-skill" in s for s in found["cron_j1_c"]["severe"]) and any("git push" in s for s in found["cron_j1_c"]["severe"])
    assert ap.Settings(tmp_path).get()["paused"] is True
    kinds = [e["title"] for e in ap.journal(ap.paths(tmp_path))]
    assert "Guardian: rule broken, Autopilot paused" in kinds and "Guardian: looked outside its lab" in kinds
    assert ap.guardian(tmp_path, home, "j1", [work]) == []  # each run is audited once


def test_recursive_delete_only_flags_targets_outside_the_lab():
    lab = "/home/u/lab"
    assert ap.risky_rm_targets("t build/out", lab) == []
    assert ap.risky_rm_targets('"$T"', lab) == []
    assert ap.risky_rm_targets("/home/u/lab/x /tmp/y", lab) == []
    assert ap.risky_rm_targets("~/Workspace", lab) == ["~/Workspace"]
    assert ap.risky_rm_targets("/home/u/Workspace/client", lab) == ["/home/u/Workspace/client"]
    assert ap.risky_rm_targets("../other $HOME/x", lab) == ["../other", "$HOME/x"]


def test_missions_queue_and_gate(tmp_path):
    p = ap.paths(tmp_path)
    with pytest.raises(ap.AutopilotError):
        ap.add_mission(p, "  ")
    a = ap.add_mission(p, "Write a tiny markdown linter")
    b = ap.add_mission(p, "Second thing")
    assert ap.next_mission(p)["id"] == a["id"]
    ap.set_mission(p, b["id"], "active")
    assert ap.next_mission(p)["id"] == b["id"]  # an active one wins
    ap.set_mission(p, b["id"], "done", "Built it in lab/x")
    assert ap.next_mission(p)["id"] == a["id"]
    assert ap.journal(p)[0]["title"].startswith("Mission done")
    ap.Settings(tmp_path).set({"lab_dir": str(tmp_path / "lab" / "z"), "window_start": "00:00", "window_end": "00:00"})
    home = tmp_path / "h"; home.mkdir()
    wake, out = ap.gate(tmp_path, home, enabled=True, fetch_usage=lambda: {"ok": True, "session": 1, "weekly": 1})
    assert wake and "MISSION FROM THE USER" in out and a["id"] in out


def test_mission_routes(tmp_path):
    c, cfg = make_client(tmp_path)
    m = c.post("/v1/autopilot/missions", json={"text": "Research X"}).json()["mission"]
    assert c.get("/v1/autopilot/missions").json()["missions"][0]["id"] == m["id"]
    assert c.post(f"/v1/autopilot/missions/{m['id']}/drop").json()["mission"]["state"] == "dropped"
    assert c.post(f"/v1/autopilot/missions/{m['id']}/requeue").json()["mission"]["state"] == "queued"
    assert c.post("/v1/autopilot/missions/nope/drop").status_code == 404
    assert c.post("/v1/autopilot/missions", json={"text": ""}).status_code == 400


def test_journal_prose_is_not_a_command():
    s = ap.strip_journal_text
    assert "git push" not in s('B=x; $B autopilot log --kind build --title "Checker" "stops a bad git push"; ls')
    assert "git push" not in s('hermes-remote-bridge autopilot end "no git push done"')
    assert "git push" in s('$B autopilot log "ok"; git push origin main')
    assert "git push" in s('bash -c "git push origin main"')
    assert "sudo" in s("$B autopilot log 'x' && sudo rm /x")


def test_live_steps_and_long_poll(tmp_path):
    c, cfg = make_client(tmp_path)
    home = cfg.hermes_home
    con = sqlite3.connect(home / "state.db")
    con.executescript("create table sessions(id text, source text, started_at real);"
                      "create table messages(id integer primary key, session_id text, role text, content text, tool_calls text, timestamp real);")
    con.execute("insert into sessions values ('cron_j1_old', 'cron', 1), ('cron_j1_new', 'cron', 2)")
    con.execute("insert into messages(session_id, role, content, tool_calls, timestamp) values ('cron_j1_old', 'assistant', 'old', null, 1)")
    con.execute("insert into messages(session_id, role, content, tool_calls, timestamp) values (?, 'assistant', 'Building it', ?, 2)",
                ("cron_j1_new", json.dumps([{"function": {"name": "terminal", "arguments": json.dumps({"command": "ls  -la\n/lab"})}}])))
    con.execute("insert into messages(session_id, role, content, tool_calls, timestamp) values ('cron_j1_new', 'tool', 'secret output', null, 3)")
    con.commit(); con.close()
    r = c.get("/v1/autopilot/live").json()
    assert r["session"] == "cron_j1_new"
    assert [(s["role"], s["tool"], s["text"]) for s in r["steps"]] == [("tool", "terminal", "ls -la /lab"), ("say", None, "Building it")]
    assert r["cursor"] == 3 and all("secret" not in s["text"] for s in r["steps"])
    t0 = time.monotonic()
    r = c.get("/v1/autopilot/live", params={"after": 3, "wait": 1.5}).json()
    assert r["steps"] == [] and r["entries"] == [] and time.monotonic() - t0 >= 1.0
    ap.log(ap.paths(cfg.state_dir), "note", "hi", title="fresh")
    r = c.get("/v1/autopilot/live", params={"after": 3, "since": 1, "wait": 10}).json()
    assert r["entries"][0]["title"] == "fresh"


def test_live_off(tmp_path):
    c, _ = make_client(tmp_path, extras_on=False)
    assert c.get("/v1/autopilot/live").json()["error"]["code"] == "autopilot_off"
