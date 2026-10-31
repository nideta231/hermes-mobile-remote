import json
import os
import sys
import time

import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.monitor import ClaudeUsage, Monitor, parse_usage
from hermes_remote_bridge.terminal import Screen, Shell

from test_app import PHONE, FakeLocator, FakeTailnet, fake_http


def test_sample_stores_rows_and_history_buckets(tmp_path):
    m = Monitor(tmp_path / "m.db")
    first = m.sample()
    assert 0 <= first["cpu"] <= 100 and first["cores"] >= 1
    m.sample()
    h = m.history("1h")
    assert h["range"] == "1h" and len(h["ts"]) >= 1
    assert set(["cpu", "mem", "rx", "cpu_max"]) <= set(h)
    m.rollup()  # no error on an almost empty db


def test_tracking_off_skips_reading_and_persists(tmp_path):
    m = Monitor(tmp_path / "m.db")
    assert all(m.tracking.values())
    m.set_tracking({"network": False, "temp": False, "processes": False})
    m.sample()
    s = m.sample()
    assert s["rx"] is None and s["tx"] is None and s["temp"] is None and s["cpu"] is not None
    assert m.now()["top"] == [] and s["tracking"]["network"] is False
    with pytest.raises(ValueError):
        m.set_tracking({"gpu": False})
    again = Monitor(tmp_path / "m.db")  # saved next to the database
    assert again.tracking["network"] is False and again.tracking["cpu"] is True
    again.set_tracking({"cpu": False, "memory": False, "disk": False})
    again.sample()
    s = again.sample()
    assert s["cpu"] is None and s["mem"] is None and s["disk"] is None and s["cores"] >= 1
    assert again.history("1h")["range"] == "1h"  # gaps, not errors


def test_processes_sort_filter_and_self_protection(tmp_path):
    m = Monitor(tmp_path / "m.db")
    out = m.processes("mem", 500)
    assert out["total"] >= 1
    mems = [p["mem_mb"] for p in out["processes"]]
    assert mems == sorted(mems, reverse=True)
    mine = m.processes("cpu", 500, str(os.getpid()))["processes"]
    assert any(p["pid"] == os.getpid() for p in mine)
    with pytest.raises(PermissionError):
        m.signal(os.getpid(), False)
    with pytest.raises(PermissionError):
        m.signal(1, True)


def test_signal_stops_a_child(tmp_path):
    import subprocess

    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
    try:
        r = Monitor(tmp_path / "m.db").signal(child.pid, False)
        assert r["stopped"] is True and r["pid"] == child.pid
    finally:
        child.kill()


def test_parse_usage_limits_and_legacy_shape():
    new = parse_usage({"five_hour": {"utilization": 42, "resets_at": "2026-10-26T18:00:00Z"},
                       "seven_day": {"utilization": 17, "resets_at": "2026-10-30T00:00:00Z"},
                       "limits": [{"kind": "session", "percent": 42, "is_active": True},
                                  {"group": "weekly", "kind": "model_weekly", "percent": 60,
                                   "scope": {"model": {"display_name": "Opus"}}}]}, "max")
    assert new["ok"] and new["plan"] == "max" and new["session"] == 42 and new["weekly"] == 17
    assert [l["label"] for l in new["limits"]] == ["Session (5h)", "Weekly · Opus"]
    old = parse_usage({"five_hour": {"utilization": 5}, "seven_day": {"utilization": 9, "resets_at": "x"}}, None)
    assert [l["label"] for l in old["limits"]] == ["Session (5h)", "Weekly"] and old["weekly_resets_at"] == "x"


def test_claude_usage_reads_token_never_refreshes(tmp_path):
    cred = tmp_path / "c.json"
    seen = []
    usage = ClaudeUsage(cred, opener=lambda req: (seen.append(req), b'{"five_hour":{"utilization":3}}')[1])
    assert usage.poll()["ok"] is False and "not signed in" in usage.latest["error"]
    cred.write_text(json.dumps({"claudeAiOauth": {"accessToken": "a", "expiresAt": (time.time() - 10) * 1000}}))
    assert "expired" in usage.poll()["error"] and not seen
    cred.write_text(json.dumps({"claudeAiOauth": {"accessToken": "a", "expiresAt": (time.time() + 999) * 1000,
                                                  "subscriptionType": "pro"}}))
    r = usage.poll()
    assert r["ok"] and r["session"] == 3 and r["plan"] == "pro"
    assert seen[0].get_header("Authorization") == "Bearer a"


@pytest.fixture
def mon_env(tmp_path):
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log", monitor_db=tmp_path / "m.db")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    mon = Monitor(cfg.monitor_db, ClaudeUsage(tmp_path / "missing.json"))
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com", monitor=mon,
                     # /bin/sh, not the user's shell: fish drops input typed before its prompt.
                     shell_factory=lambda **kw: Shell(["/bin/sh"], **kw))
    c = TestClient(app, client=(PHONE, 5555))
    c.headers["Authorization"] = f"Bearer {token}"
    return c, cfg


def test_monitor_routes(mon_env):
    c, _ = mon_env
    with c:
        now = c.get("/v1/monitor/now?live=true").json()
        assert "cpu" in now["stats"] and isinstance(now["top"], list) and "claude" in now
        assert c.get("/v1/monitor/history?range=6h").json()["range"] == "6h"
        assert c.get("/v1/monitor/history?range=2h").status_code == 400
        procs = c.get("/v1/monitor/processes?sort=name&limit=5").json()
        assert len(procs["processes"]) <= 5
        assert c.post(f"/v1/monitor/processes/{os.getpid()}/stop").json()["error"]["code"] == "forbidden_process"
        assert c.post("/v1/monitor/processes/999999999/stop").status_code in (403, 404)
        assert c.get("/v1/claude/usage?refresh=true").json()["ok"] is False
        assert c.get("/v1/me").json()["features"] == ["monitor", "claude_usage", "terminal", "speedtest", "folders", "file_info", "bridge_update", "notes", "blueprints", "admin", "parked", "library"]


def test_monitor_requires_token(mon_env):
    c, _ = mon_env
    del c.headers["Authorization"]
    assert c.get("/v1/monitor/now").status_code == 401


def test_monitor_off(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", monitor=False, terminal=False)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = TestClient(app, client=(PHONE, 5555))
    c.headers["Authorization"] = f"Bearer {token}"
    assert c.get("/v1/monitor/now").json()["error"]["code"] == "monitor_off"
    assert c.get("/v1/me").json()["features"] == ["folders", "file_info", "bridge_update", "notes", "blueprints", "admin", "parked", "library"]


@pytest.mark.skipif(os.name == "nt", reason="PTY shell is POSIX only")
def test_terminal_runs_commands(mon_env):
    c, _ = mon_env
    with c.websocket_connect("/v1/terminal?cols=100&rows=30") as ws:
        # `expr` works in bash, zsh and fish alike (the test runs the user's $SHELL).
        ws.send_text(json.dumps({"type": "input", "data": "echo hr-`expr 40 + 2`\r"}))
        rows: dict = {}
        first = None
        deadline = time.time() + 15
        while time.time() < deadline:
            msg = json.loads(ws.receive_text())
            assert msg["type"] == "screen"
            first = first or msg
            rows.update(msg["lines"])
            for line in msg.get("scrolled", []):
                rows[f"s{len(rows)}"] = line
            if any("".join(r[0] for r in runs).strip() == "hr-42" for runs in rows.values()):
                break
        else:
            raise AssertionError(f"no hr-42 in {rows}")
        assert first["cols"] == 100 and first["rows"] == 30 and first["full"] is True
        ws.send_text(json.dumps({"type": "input", "data": "exit\r"}))
        while True:
            msg = json.loads(ws.receive_text())
            if msg["type"] == "exit":
                break


def test_screen_answers_queries_and_keeps_scrollback():
    replies = []
    sc = Screen(20, 3, reply=replies.append)
    sc.feed(b"\x1b[0c\x1b[6n")
    assert replies == ["\x1b[?6c", "\x1b[1;1R"]  # fish waits for these before its prompt
    sc.frame()
    sc.feed(b"one\r\ntwo\r\nthree\r\n\x1b[31mfour\x1b[m")
    f = sc.frame()
    assert ["".join(r[0] for r in line) for line in f["scrolled"]] == ["one"] or \
        ["".join(r[0] for r in line) for line in f["scrolled"]][:1] == ["one"]
    assert f["lines"]["2"][0] == ["four", "red", "default", 0]
    sc.resize(30, 5)
    assert sc.frame()["full"] is True


def test_terminal_refuses_without_token(mon_env):
    c, _ = mon_env
    del c.headers["Authorization"]
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect) as e:
        with c.websocket_connect("/v1/terminal") as ws:
            ws.receive_text()
    assert e.value.code == 4401


def test_repair_replaces_the_old_token(tmp_path):
    store = DeviceStore(tmp_path / "d.json")
    old, old_token = store.pair("phone")
    with pytest.raises(ValueError, match="--replace"):
        store.pair("phone")
    new, new_token = store.pair("phone", replace=True)
    assert new.id != old.id
    assert store.authenticate(old_token) is None
    assert store.authenticate(new_token).id == new.id


def test_parse_usage_details():
    data = {"five_hour": {"utilization": 29, "resets_at": "a", "locked_reason": None},
            "seven_day_opus": {"utilization": 12, "resets_at": "b"},
            "limits": [{"kind": "session", "group": "session", "percent": 29, "is_active": True}],
            "extra_usage": {"is_enabled": True, "spend_limit_reached": False},
            "spend": {"used": {"amount_minor": 1250, "currency": "USD", "exponent": 2},
                      "limit": {"amount_minor": 5000, "currency": "USD", "exponent": 2}, "percent": 25, "enabled": True}}
    r = parse_usage(data, "max", "default_claude_max_5x")
    assert r["tier"] == "default_claude_max_5x"
    assert [l["label"] for l in r["limits"]] == ["Session (5h)", "Weekly · Opus"]
    assert r["extra"] == {"enabled": True, "currency": "USD", "used": 12.5, "limit": 50.0, "percent": 25,
                          "limit_reached": False, "disabled_reason": None}


def test_speed_test_stores_and_refuses_overlap(tmp_path):
    m = Monitor(tmp_path / "m.db")
    fake = {"ts": int(time.time()), "ping_ms": 12.0, "jitter_ms": 1.0, "down_mbps": 80.0, "up_mbps": 40.0}
    assert m.speed_test(lambda: fake)["down_mbps"] == 80.0
    h = m.speed_history()
    assert h["running"] is False and h["tests"][0]["up_mbps"] == 40.0
    m._speed_lock.acquire()
    with pytest.raises(RuntimeError):
        m.speed_test(lambda: fake)


def test_speedtest_math():
    import io
    from hermes_remote_bridge import speedtest

    class Body(io.BytesIO):
        def __enter__(self):
            return self

    r = speedtest.run(opener=lambda url, t: Body(b"x" * (1000 if "bytes=0" not in url else 0)), poster=lambda d: None)
    assert r["down_bytes"] == 1000 and r["ping_ms"] >= 0 and r["up_bytes"] > 0


def test_folder_routes(mon_env, tmp_path):
    c, _ = mon_env
    (tmp_path / "proj" / ".git").mkdir(parents=True)
    (tmp_path / ".hidden").mkdir()
    (tmp_path / "file.txt").write_text("x")
    out = c.get("/v1/folders", params={"path": str(tmp_path)}).json()
    names = [d["name"] for d in out["dirs"]]
    assert "proj" in names and ".hidden" not in names and "file.txt" not in names
    assert next(d for d in out["dirs"] if d["name"] == "proj")["git"] is True
    assert out["parent"] == str(tmp_path.parent)
    assert c.get("/v1/folders", params={"path": str(tmp_path / "nope")}).status_code == 404
    made = c.post("/v1/folders", json={"path": str(tmp_path / "new")}).json()
    assert made["path"] == str(tmp_path / "new") and (tmp_path / "new").is_dir()
    assert c.post("/v1/folders", json={"path": str(tmp_path / "new")}).status_code == 409
    assert c.get("/v1/folders").json()["path"] == os.path.expanduser("~")
