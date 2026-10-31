"""Memory Library: preflight, enable/disable round trip, routes, and (when a runtime is around) real Postgres.

The real-database tests need an installed Library root (pg0 + an initialised cluster). Point
HR_LIBRARY_TEST_ROOT at one (e.g. made with `hermes-remote-bridge library install`); otherwise they skip.
"""
import json
import os
import shutil
import stat
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge import library as lib
from hermes_remote_bridge import library_store as ls
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

from test_cron import Locator, Tailnet

pytestmark = pytest.mark.skipif(os.name == "nt", reason="the Memory Library is Linux-only for now (why_not)")

FAKE_HERMES = """#!{py}
import json, sys
from pathlib import Path
args = sys.argv[1:]
prof = "default"
if args[:1] == ["-p"]:
    prof, args = args[1], args[2:]
db = Path({db!r})
data = json.loads(db.read_text()) if db.exists() else {{}}
conf = data.setdefault(prof, {{}})
if args[:2] == ["config", "get"]:
    if args[2] not in conf:
        print("Config key not set: " + args[2]); sys.exit(1)
    print(conf[args[2]])
elif args[:2] == ["config", "set"]:
    rest = [a for a in args[2:] if a != "--force"]
    conf[rest[0]] = rest[1] if len(rest) > 1 else ""
    db.write_text(json.dumps(data))
else:
    sys.exit(2)
"""


@pytest.fixture
def hermes(tmp_path):
    db = tmp_path / "hermes-config.json"
    exe = tmp_path / "hermes"
    exe.write_text(FAKE_HERMES.format(py=sys.executable, db=str(db)))
    exe.chmod(exe.stat().st_mode | stat.S_IXUSR)
    home = tmp_path / "home"
    (home / "memories").mkdir(parents=True)
    return exe, home, db


def config_of(db: Path, profile: str = "default") -> dict:
    return json.loads(db.read_text()).get(profile, {}) if db.exists() else {}


def test_tsquery_is_safe_and_bounded():
    q = ls.tsquery("Tolong buatin draft WA ke klien!! ' | & :* ()")
    assert "'" not in q and "&" not in q and "(" not in q
    assert "tolong:*" in q and "wa" in q
    assert len(ls.tsquery(" ".join(f"word{i}" for i in range(100))).split(" | ")) == 32


def test_validation_messages():
    for kw in ({"kind": "story"}, {"severity": 0}, {"severity": True}, {"scope": "project:"},
               {"text": " "}, {"status": "gone"}):
        with pytest.raises(ls.Invalid):
            ls.Store.check(**kw)
    ls.Store.check(kind="rule", severity=5, scope="client:Acme & Co.", text="x", status="archived")


def test_unit_name_differs_per_data_folder(tmp_path):
    a, b = ls.Paths(tmp_path / "a"), ls.Paths(tmp_path / "b")
    assert ls.unit_name(a) != ls.unit_name(b)
    assert ls.unit_name(a).startswith(ls.UNIT + "-")


def test_split_entries_on_section_signs():
    assert lib.split_entries("one\n§\ntwo\nlines\n§\n\n") == ["one", "two\nlines"]


def test_plan_reports_sizes_and_no_network(tmp_path):
    pl = lib.plan(lib.paths(tmp_path / "lib"))
    assert pl["network"].startswith("None")
    assert pl["disk_bytes"] >= pl["download_bytes"]
    assert pl["location"].endswith("lib")


def test_preflight_keeps_a_custom_provider(hermes, tmp_path):
    exe, home, db = hermes
    db.write_text(json.dumps({"default": {"memory.provider": "honcho"}}))
    pre = lib.preflight(exe, home, "default", lib.paths(tmp_path / "lib"))
    assert pre["custom_provider"] == "honcho" and not pre["enabled"]
    with pytest.raises(RuntimeError, match="honcho"):
        lib.enable(exe, home, "default", p=lib.paths(tmp_path / "lib"))
    assert config_of(db)["memory.provider"] == "honcho"  # untouched


def test_enable_refuses_a_foreign_plugin_folder(hermes, tmp_path):
    exe, home, db = hermes
    (home / "plugins" / "library").mkdir(parents=True)
    (home / "plugins" / "library" / "__init__.py").write_text("# someone else's")
    with pytest.raises(RuntimeError, match="different plugin"):
        lib.enable(exe, home, "default", p=lib.paths(tmp_path / "lib"))
    assert (home / "plugins" / "library" / "__init__.py").read_text() == "# someone else's"


def test_enable_needs_the_runtime(hermes, tmp_path):
    exe, home, db = hermes
    with pytest.raises(RuntimeError, match="Install"):
        lib.enable(exe, home, "default", p=lib.paths(tmp_path / "lib"))
    assert "memory.provider" not in config_of(db)


def test_settings_defaults_and_round_trip(hermes):
    exe, _, db = hermes
    assert lib.settings(exe, "default") == {"smart_recall": False, "session_review": True, "model": "",
                                           "provider": "auto"}
    out = lib.set_settings(exe, "default", smart_recall=True, model="claude-opus-5-5")
    assert out["smart_recall"] is True and out["model"] == "claude-opus-5-5"
    assert config_of(db)["auxiliary.memory_library.model"] == "claude-opus-5-5"


def test_plugin_files_ship_the_store():
    files = lib.plugin_files()
    assert {"__init__.py", "plugin.yaml", "store.py"} <= set(files)
    assert files["store.py"] == Path(ls.__file__).read_bytes()
    assert b"from . import store as ls" in files["__init__.py"]


def test_doctor_without_runtime_says_install(hermes, tmp_path):
    exe, home, _ = hermes
    d = lib.doctor(exe, home, "default", lib.paths(tmp_path / "lib"))
    ids = {c["id"]: c for c in d["checks"]}
    assert ids["runtime"]["fix"] == "install"


# ------------------------------------------------------------------ routes

def client(tmp_path, hermes, **over):
    exe, home, _ = hermes
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log", monitor=False,
                 hermes_bin=exe, hermes_home=home, library_dir=tmp_path / "lib", **over)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, devices=store, tailnet=Tailnet(), locator=Locator(), owner_login="me@x")
    c = TestClient(app, client=("100.64.0.5", 1))
    c.headers["authorization"] = f"Bearer {token}"
    return c


def test_routes_before_install(tmp_path, hermes):
    c = client(tmp_path, hermes)
    st = c.get("/v1/library").json()
    assert st["installed"] is False and st["enabled"] is False and st["provider"] == "built-in"
    assert st["plan"]["download_bytes"] >= 0 and st["settings"]["provider"] == "auto"
    assert c.get("/v1/library/memories").status_code == 409
    assert c.post("/v1/library/install", json={}).json()["error"]["code"] == "consent_needed"
    assert c.post("/v1/library/enable", json={}).status_code == 409
    assert c.put("/v1/library/settings", json={"smart_recall": "yes"}).status_code == 400
    assert c.put("/v1/library/settings", json={"model": "x; rm -rf"}).status_code == 400
    assert c.put("/v1/library/settings", json={"smart_recall": True}).json()["smart_recall"] is True
    assert c.post("/v1/library/fix/nope").status_code == 404


def test_routes_off(tmp_path, hermes):
    c = client(tmp_path, hermes, library=False)
    assert "library" not in c.get("/v1/me").json()["features"]
    assert c.get("/v1/library").status_code == 404


def test_admin_routes_need_remote_admin(tmp_path, hermes):
    c = client(tmp_path, hermes, remote_admin=False)
    assert c.post("/v1/library/install", json={"accept": True}).json()["error"]["code"] == "admin_off"
    assert c.post("/v1/library/disable").json()["error"]["code"] == "admin_off"


# ------------------------------------------------------------------ real Postgres

REAL = os.environ.get("HR_LIBRARY_TEST_ROOT")
real = pytest.mark.skipif(not (REAL and ls.is_installed(ls.Paths(Path(REAL)))),
                          reason="set HR_LIBRARY_TEST_ROOT to an installed Library root")


@pytest.fixture
def real_store():
    p = ls.Paths(Path(REAL))
    ls.ensure_running(p)
    s = ls.Store(p, "pytest_" + str(os.getpid()))
    s.ensure_database()
    yield s
    s._psql(f'drop database if exists "{s.db}" with (force);\n', db="postgres")


@real
def test_recall_pinned_scope_and_ranking(real_store):
    s = real_store
    no_pak = s.add("Never call the user Pak or Bapak", kind="rule", severity=5, keywords="honorific sapaan pak bapak")
    wa = s.add("Client drafts: casual WhatsApp style, short lines, no dashes", kind="preference", severity=3,
               scope="project:p_shop", keywords="draft pesan wa whatsapp klien client message")
    other = s.add("Odoo invoices need the tax line", kind="fact", severity=2, scope="project:p_other",
                  keywords="odoo invoice pajak")
    r = s.recall("tolong buatin draft pesan WA ke klien", scopes=["project:p_shop"], explain=True)
    assert [m["id"] for m in r["pinned"]] == [no_pak["id"]]
    assert wa["id"] in [m["id"] for m in r["relevant"]]
    assert other["id"] not in [m["id"] for m in r["pinned"] + r["relevant"]]
    assert {"ft", "sim", "score"} <= set(r["relevant"][0])
    text = ls.Store.format_recall(r, {"p_shop": "Shop"})
    assert "Always apply" in text and "project Shop" in text
    assert s.get(no_pak["id"])["hits"] == 1


@real
def test_duplicates_bump_merge_and_injection(real_store):
    s = real_store
    a = s.add("Git author is always the repo owner", kind="rule", severity=3)
    assert s.add("git author is always the repo owner", kind="rule", severity=3)["duplicate"] is True
    s.bump(a["id"])
    b2 = s.bump(a["id"])
    assert (b2["repeats"], b2["severity"]) == (2, 4)  # second repeat climbs one step
    evil = s.add("x'); drop table memories; --", kind="fact", keywords="'; select pg_sleep(5); --")
    assert s.get(evil["id"])["text"].startswith("x');")
    c = s.add("Use the repo owner as git author on every commit", kind="rule", severity=2, keywords="git commit author")
    merged = s.merge(a["id"], c["id"])
    assert merged["severity"] == 4 and s.get(c["id"])["status"] == "superseded"
    assert any(l["rel"] == "supersedes" for l in merged["links"])
    g = s.graph(chats=False)
    assert any(n["type"] == "global" for n in g["nodes"])
    assert {e["rel"] for e in g["edges"]} >= {"applies_to"}
    h = s.health()
    assert h["schema_version"] == ls.SCHEMA_VERSION


@real
def test_enable_disable_round_trip_on_real_runtime(hermes, tmp_path):
    exe, hermes_home, db = hermes
    p = ls.Paths(Path(REAL))
    profile = "pytest_rt" + str(os.getpid())
    home = hermes_home / "profiles" / profile  # a non-default profile: everything lands in its own home
    (home / "memories").mkdir(parents=True)
    (home / "memories" / "MEMORY.md").write_text("Desktop is Wayland: X11 tools are blind\n§\nUser runs a small shop\n")
    (home / "memories" / "USER.md").write_text("Prefers short answers\n")
    state_before = p.state.read_text() if p.state.exists() else None
    try:
        out = lib.enable(exe, hermes_home, profile, p=p)
        assert out["imported"]["added"] == 3 and Path(out["imported"]["backup"]).joinpath("MEMORY.md").exists()
        assert config_of(db, profile)["memory.provider"] == "library"
        assert "memory.provider" not in config_of(db)  # the default profile is untouched
        plugin = home / "plugins" / "library"
        assert json.loads((plugin / "library.json").read_text())["root"] == str(p.root)
        assert (home / "memories" / "MEMORY.md").exists()  # copy, never move
        d = {c["id"]: c["status"] for c in lib.doctor(exe, hermes_home, profile, p)["checks"]}
        assert d["provider"] == d["plugin"] == d["schema"] == d["backup"] == "ok"
        items = ls.Store(p, profile).list(review=True)["items"]
        assert len(items) == 3 and all(m["review"] for m in items)
        off = lib.disable(exe, hermes_home, profile, p)
        assert off["provider"] == "built-in" and config_of(db, profile)["memory.provider"] == ""
        assert not plugin.exists()
        again = lib.enable(exe, hermes_home, profile, p=p)
        assert again["imported"] is None  # imported once only
    finally:
        s = ls.Store(p, profile)
        s._psql(f'drop database if exists "{s.db}" with (force);\n', db="postgres")
        if state_before is None:
            p.state.unlink(missing_ok=True)
        else:
            p.state.write_text(state_before)
        shutil.rmtree(p.backups, ignore_errors=True) if state_before is None else None
