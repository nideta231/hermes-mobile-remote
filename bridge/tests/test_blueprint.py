"""Project blueprints: ops, merging, trash, Hermes' Markdown view, routes."""
import httpx
import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge import blueprint as bp
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

from test_cron import Locator, Tailnet


def test_new_blueprint_has_start_end_and_default_columns(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    doc = s.create("p1", "Shop", "/x/shop")
    assert [n["kind"] for n in doc["diagram"]["nodes"]] == ["start", "end"]
    assert [c["name"] for c in doc["board"]["columns"]] == ["Docs", "Todo", "Working", "Done"]
    with pytest.raises(bp.BlueprintError):
        s.create("p1")


def test_ops_merge_and_resolve_by_title(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    doc, applied = s.apply("p1", [{"op": "card.add", "title": "Login page", "column": "todo", "tags": ["#auth"],
                                   "checklist": ["form", "api"], "priority": "high"}], by="hermes")
    cid = applied[0]["id"]
    # the phone applies to the newest copy: Hermes' card is kept
    doc, _ = s.apply("p1", [{"op": "card.add", "title": "Docs", "column": "Docs"}], by="phone")
    assert len(doc["board"]["cards"]) == 2
    doc, _ = s.apply("p1", [{"op": "card.move", "id": "login", "column": "Working"}, {"op": "card.check", "id": cid, "text": "form"}])
    c = next(c for c in doc["board"]["cards"] if c["id"] == cid)
    assert c["column"] == "working" and c["checklist"][0]["done"] and c["tags"] == ["auth"]
    assert doc["version"] == 4
    assert any("Moved 'Login page' to Working" in l["text"] for l in doc["log"])


def test_a_bad_op_rejects_the_whole_batch(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1")
    with pytest.raises(bp.BlueprintError):
        s.apply("p1", [{"op": "card.add", "title": "a"}, {"op": "card.move", "id": "nope", "column": "done"}])
    assert s.get("p1")["board"]["cards"] == [] and s.get("p1")["version"] == 1


def test_node_after_connects_and_places_and_layout(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    doc = s.create("p1")
    start = doc["diagram"]["nodes"][0]["id"]
    doc, a = s.apply("p1", [{"op": "node.add", "kind": "feature", "label": "Feature", "after": start},
                            {"op": "node.add", "kind": "flow", "label": "Flow", "after": "Feature"},
                            {"op": "diagram.layout"}])
    n = {x["label"]: x for x in doc["diagram"]["nodes"]}
    assert n["Start"]["y"] < n["Feature"]["y"] < n["Flow"]["y"] <= n["End"]["y"]
    pairs = {(e["from"], e["to"]) for e in doc["diagram"]["edges"]}
    assert pairs == {(n["Start"]["id"], n["Feature"]["id"]), (n["Feature"]["id"], n["Flow"]["id"]), (n["Flow"]["id"], n["End"]["id"])}
    doc, _ = s.apply("p1", [{"op": "node.remove", "id": "Feature"}])
    assert all(n["Feature"]["id"] not in (e["from"], e["to"]) for e in doc["diagram"]["edges"])


def test_trash_restore_purge(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    tid = s.trash("p1")
    assert not s.exists("p1") and s.trashed()[0]["id"] == tid
    s.restore(tid)
    assert s.exists("p1") and s.trashed() == []
    s.purge(s.trash("p1"))
    assert s.trashed() == []


def test_markdown_and_resolve_by_folder(tmp_path):
    s = bp.BlueprintStore(tmp_path / "b")
    s.create("p1", "Shop", str(tmp_path / "shop"))
    s.apply("p1", [{"op": "card.add", "title": "Pay", "column": "todo", "checklist": ["stripe"]}])
    md = bp.to_markdown(s.get("p1"))
    assert "# Blueprint: Shop" in md and "### Todo (1)" in md and "- [ ] stripe" in md
    (tmp_path / "shop" / "src").mkdir(parents=True)
    assert bp.resolve(s, None, cwd=str(tmp_path / "shop" / "src"))["id"] == "p1"
    assert bp.resolve(s, "shop")["id"] == "p1"


def test_comments_questions_and_resolving(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    doc, ap = s.apply("p1", [{"op": "card.add", "title": "Deploy", "column": "Working"}])
    cid = ap[0]["id"]
    doc, ap = s.apply("p1", [{"op": "card.comment", "id": "deploy", "kind": "question", "text": "Deploy to live now?"}], by="hermes")
    q = ap[0]["comment_id"]
    card = doc["board"]["cards"][0]
    assert card["comments"][0]["by"] == "hermes" and bp.open_asks(card) and bp.summary(doc)["needs_you"] == 1
    md = bp.to_markdown(doc)
    assert "## Waiting for the user" in md and "NEEDS USER" in md and "question (OPEN): Deploy to live now?" in md
    # the user's answer resolves the open question
    doc, _ = s.apply("p1", [{"op": "card.comment", "id": cid, "text": "Yes, go", "resolves": True}], by="phone")
    card = doc["board"]["cards"][0]
    assert not bp.open_asks(card) and [m["kind"] for m in card["comments"]] == ["question", "comment"]
    doc, _ = s.apply("p1", [{"op": "comment.resolve", "id": cid, "comment_id": q, "resolved": False}])
    assert bp.open_asks(doc["board"]["cards"][0])
    doc, _ = s.apply("p1", [{"op": "comment.resolve", "id": cid}])
    assert bp.summary(doc)["needs_you"] == 0
    with pytest.raises(bp.BlueprintError):
        s.apply("p1", [{"op": "card.comment", "id": cid, "kind": "shout", "text": "x"}])
    with pytest.raises(bp.BlueprintError):
        s.apply("p1", [{"op": "card.comment", "id": cid, "text": "  "}])  # no text and no file
    doc, _ = s.apply("p1", [{"op": "comment.remove", "id": cid, "comment_id": q}])
    assert len(doc["board"]["cards"][0]["comments"]) == 1


def test_agent_badge_clears_when_done_and_instructions_reach_hermes(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    doc, ap = s.apply("p1", [{"op": "card.add", "title": "Login", "column": "Todo"}])
    cid = ap[0]["id"]
    doc, _ = s.apply("p1", [{"op": "card.move", "id": cid, "column": "Working"}, {"op": "card.update", "id": cid, "agent": "Hermes"}], by="hermes")
    assert doc["board"]["cards"][0]["agent"] == "Hermes" and "Hermes working" in bp.to_markdown(doc)
    doc, _ = s.apply("p1", [{"op": "card.move", "id": cid, "column": "Done"}], by="hermes")
    assert doc["board"]["cards"][0]["agent"] is None
    doc, _ = s.apply("p1", [{"op": "instructions.set", "text": "Never deploy to LIVE unless asked.\nRead docs/ first."}])
    md = bp.to_markdown(doc)
    assert md.index("## Instructions from the user") < md.index("## Flow") and "Read docs/ first." in md
    doc, _ = s.apply("p1", [{"op": "instructions.set", "text": ""}])
    assert "## Instructions" not in bp.to_markdown(doc)


def test_board_settings_new_cards_on_top_and_column_limits(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    titles = lambda doc, col: [c["title"] for c in sorted(doc["board"]["cards"], key=lambda c: c["order"]) if c["column"] == col]
    doc, _ = s.apply("p1", [{"op": "card.add", "title": "A", "column": "Todo"}, {"op": "card.add", "title": "B", "column": "Todo"}])
    assert titles(doc, "todo") == ["A", "B"] and bp.settings(doc) == {"new_cards": "bottom", "aging_days": 0, "sticky_add": False}
    doc, _ = s.apply("p1", [{"op": "settings.set", "new_cards": "top", "aging_days": 7}])
    doc, _ = s.apply("p1", [{"op": "card.add", "title": "C", "column": "Todo"}, {"op": "card.add", "title": "D", "column": "Todo", "index": 9}])
    assert titles(doc, "todo") == ["C", "A", "B", "D"]  # top by default; an explicit index still wins
    doc, _ = s.apply("p1", [{"op": "card.move", "id": "A", "column": "Working"}, {"op": "card.move", "id": "B", "column": "Working"}])
    assert titles(doc, "working") == ["B", "A"]
    doc, _ = s.apply("p1", [{"op": "card.move", "id": "C", "column": "Todo"}])  # same column, no index: to the end
    assert titles(doc, "todo") == ["D", "C"]
    doc, _ = s.apply("p1", [{"op": "settings.set", "sticky_add": True}])
    assert bp.settings(doc)["sticky_add"] is True
    for bad in ({"new_cards": "middle"}, {"aging_days": 5}, {"colour": "x"}, {"sticky_add": 1}, {"aging_days": True}):
        with pytest.raises(bp.BlueprintError):
            s.apply("p1", [{"op": "settings.set", **bad}])
    doc, _ = s.apply("p1", [{"op": "column.update", "id": "Working", "limit": 1}])
    md = bp.to_markdown(doc)
    assert "### Working (2 of max 1, OVER THE LIMIT" in md and "go to the top of a column, cards untouched for 7 days fade" in md
    assert next(c for c in bp.summary(doc)["columns"] if c["id"] == "working")["limit"] == 1
    doc, _ = s.apply("p1", [{"op": "column.update", "id": "Working", "limit": 0}])
    assert "limit" not in doc["board"]["columns"][2] and "### Working (2)" in bp.to_markdown(doc)
    with pytest.raises(bp.BlueprintError):
        s.apply("p1", [{"op": "column.update", "id": "Working", "limit": "lots"}])


def test_comments_and_start_remember_the_hermes_chat(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    doc, ap = s.apply("p1", [{"op": "card.add", "title": "Login", "column": "Todo"}])
    cid = ap[0]["id"]
    doc, _ = s.apply("p1", [{"op": "card.update", "id": cid, "agent": "Hermes", "session": "20261029_1"}], by="hermes")
    assert doc["board"]["cards"][0]["session"] == "20261029_1"
    doc, _ = s.apply("p1", [{"op": "card.comment", "id": cid, "kind": "question", "text": "Stripe?", "session": "20261029_2"}], by="hermes")
    card = doc["board"]["cards"][0]
    assert card["session"] == "20261029_2" and card["comments"][-1]["session"] == "20261029_2"
    doc, _ = s.apply("p1", [{"op": "card.comment", "id": cid, "text": "yes", "resolves": True}])
    assert "session" not in doc["board"]["cards"][0]["comments"][-1] and doc["board"]["cards"][0]["session"] == "20261029_2"
    latest = bp.summary(doc)["latest_comment"]  # the phone's own reply never counts as unread
    assert latest["card"] == cid and latest["text"] == "Stripe?" and latest["by"] == "hermes"


def test_global_board_settings_apply_where_a_board_has_no_choice(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    s.create("p2", "Blog")
    s.apply("p2", [{"op": "settings.set", "new_cards": "bottom"}])  # p2 chose for itself
    assert s.set_defaults({"new_cards": "top", "aging_days": 14}) == {"new_cards": "top", "aging_days": 14}
    assert [d["project"]["id"] for d in s.all()] == ["p1", "p2"]  # the defaults file is not a blueprint
    titles = lambda doc: [c["title"] for c in sorted(doc["board"]["cards"], key=lambda c: c["order"])]
    doc1, _ = s.apply("p1", [{"op": "card.add", "title": "A", "column": "Todo"}, {"op": "card.add", "title": "B", "column": "Todo"}])
    doc2, _ = s.apply("p2", [{"op": "card.add", "title": "A", "column": "Todo"}, {"op": "card.add", "title": "B", "column": "Todo"}])
    assert titles(doc1) == ["B", "A"] and titles(doc2) == ["A", "B"]
    assert "_defaults" not in doc1 and "_defaults" not in s.get("p1")
    assert bp.settings(doc2, s.defaults()) == {"new_cards": "bottom", "aging_days": 14, "sticky_add": False}
    doc2, _ = s.apply("p2", [{"op": "settings.set", "new_cards": "default"}])  # back to following the global one
    assert bp.settings(doc2, s.defaults())["new_cards"] == "top"
    with pytest.raises(bp.BlueprintError):
        s.set_defaults({"aging_days": 5})


def test_long_titles_are_refused_not_cut(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    doc, ap = s.apply("p1", [{"op": "card.add", "title": "x" * 600, "column": "Todo"}])
    assert len(doc["board"]["cards"][0]["title"]) == 600
    for op in ({"op": "card.add", "title": "x" * 601}, {"op": "card.update", "id": ap[0]["id"], "title": "y" * 601},
               {"op": "column.add", "name": "c" * 121}, {"op": "node.add", "label": "n" * 401}):
        with pytest.raises(bp.BlueprintError, match="the limit is"):
            s.apply("p1", [op])
    assert s.get("p1")["board"]["cards"][0]["title"] == "x" * 600


def test_routes(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", trust_file=tmp_path / "n.json",
                 blueprints_dir=tmp_path / "bp")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(404))),
                     tailnet=Tailnet(), devices=store, owner_login="me@x")
    c, h = TestClient(app, client=("100.64.0.10", 50000)), {"Authorization": f"Bearer {token}"}
    assert c.post("/v1/blueprints/p1", json={"name": "Shop"}, headers=h).json()["version"] == 1
    r = c.post("/v1/blueprints/p1/ops", json={"ops": [{"op": "card.add", "title": "A"}]}, headers=h).json()
    assert r["doc"]["version"] == 2
    assert c.get("/v1/blueprints/p1?since=2", headers=h).json() == {"version": 2, "unchanged": True}
    bad = c.post("/v1/blueprints/p1/ops", json={"ops": [{"op": "card.move", "id": "zzz", "column": "x"}]}, headers=h)
    assert bad.status_code == 400 and bad.json()["error"]["doc"]["version"] == 2
    tid = c.delete("/v1/blueprints/p1", headers=h).json()["trashed"]
    assert c.get("/v1/blueprints", headers=h).json()["trash"][0]["id"] == tid
    assert c.post(f"/v1/blueprints-trash/{tid}/restore", headers=h).json()["project"]["name"] == "Shop"
    r = c.put("/v1/blueprints-settings", json={"instructions": "Read the docs first.", "new_cards": "top"}, headers=h).json()
    assert r == {"defaults": {"new_cards": "top"}, "default_instructions": "Read the docs first."}
    assert c.get("/v1/blueprints", headers=h).json()["default_instructions"] == "Read the docs first."
    assert c.get("/v1/blueprints/p1", headers=h).json()["default_instructions"] == "Read the docs first."
    # The phone attaches a picture: stored under blueprints_dir/files/<pid>, served once a card names it
    up = c.post("/v1/blueprints/p1/upload?name=../../etc/shot%20one.png", content=b"\x89PNG data", headers=h).json()
    assert up["name"] == "shot one.png" and up["path"].startswith(str(cfg.blueprints_dir / "files" / "p1"))
    assert c.get("/v1/blueprints/p1/file", params={"path": up["path"]}, headers=h).status_code == 403  # not attached yet
    cid = c.get("/v1/blueprints/p1", headers=h).json()["board"]["cards"][0]["id"]
    c.post("/v1/blueprints/p1/ops", json={"ops": [{"op": "card.update", "id": cid, "files": [up["path"]]}]}, headers=h)
    assert c.get("/v1/blueprints/p1/file", params={"path": up["path"]}, headers=h).content == b"\x89PNG data"
    up2 = c.post("/v1/blueprints/p1/upload?name=shot%20one.png", content=b"x", headers=h).json()
    assert up2["name"] == "shot one-2.png"
    assert "attached: " in bp.to_markdown(bp.BlueprintStore(cfg.blueprints_dir).get("p1"))
    # Up to MAX_UPLOAD passes the bridge's small body cap; over it is refused with the size and the limit
    big = c.post("/v1/blueprints/p1/upload?name=clip.mp4", content=b"x" * (2 * 1024 * 1024), headers=h)
    assert big.status_code == 200 and big.json()["size"] == 2 * 1024 * 1024
    over = c.post("/v1/blueprints/p1/upload?name=clip.mp4", content=b"x" * (bp.MAX_UPLOAD + 1), headers=h)
    assert over.status_code == 413 and "up to 10 MB" in over.json()["error"]["message"]
    assert "clip.mp4 is 10.0 MB" in over.json()["error"]["message"]
    assert c.post("/v1/notes", content=b"x" * (2 * 1024 * 1024), headers=h).status_code == 413  # others keep the cap


def test_comment_files_are_the_only_files_served(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", trust_file=tmp_path / "n.json",
                 blueprints_dir=tmp_path / "bp")
    proj = tmp_path / "shop"
    proj.mkdir()
    (proj / "result.csv").write_bytes(b"a,b\n1,2\n")
    (proj / "secret.env").write_text("no")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(404))),
                     tailnet=Tailnet(), devices=store, owner_login="me@x")
    c, h = TestClient(app, client=("100.64.0.10", 50000)), {"Authorization": f"Bearer {token}"}
    s = bp.BlueprintStore(cfg.blueprints_dir)
    s.create("p1", "Shop", str(proj))
    s.apply("p1", [{"op": "card.add", "title": "Export", "column": "Working"},
                   {"op": "card.comment", "id": "export", "kind": "review", "text": "", "files": [str(proj / "result.csv")]}], by="hermes")
    get = lambda p, **kw: c.get("/v1/blueprints/p1/file", params={"path": p}, **kw)
    r = get(str(proj / "result.csv"), headers=h)
    assert r.status_code == 200 and r.content == b"a,b\n1,2\n"
    assert get("result.csv", headers=h).status_code == 200  # relative to the project folder
    assert get(str(proj / "secret.env"), headers=h).status_code == 403
    assert get(str(proj / ".." / "shop" / "secret.env"), headers=h).status_code == 403
    assert get(str(proj / "result.csv")).status_code == 401
    info = c.get("/v1/blueprints/p1/file/info", params={"path": "result.csv"}, headers=h).json()
    assert info["size"] == 8 and info["name"] == "result.csv"
    assert "[file: " in bp.to_markdown(s.get("p1"))
    (proj / "result.csv").unlink()
    assert get("result.csv", headers=h).status_code == 404


def test_global_instructions_apply_to_boards_without_their_own(tmp_path):
    s = bp.BlueprintStore(tmp_path)
    s.create("p1", "Shop")
    assert s.default_instructions() == ""
    s.set_default_instructions("  Read the docs first.  ")
    assert s.default_instructions() == "Read the docs first."
    assert [d["project"]["id"] for d in s.all()] == ["p1"]  # not listed as a blueprint
    md = bp.to_markdown(s.get("p1"), s.defaults(), s.default_instructions())
    assert "(global, every board)" in md and "Read the docs first." in md
    doc, _ = s.apply("p1", [{"op": "instructions.set", "text": "Never touch LIVE."}])
    md = bp.to_markdown(doc, s.defaults(), s.default_instructions())
    assert "Never touch LIVE." in md and "Read the docs first." not in md and "(global" not in md
    # Reset to global = clear the board's own text
    doc, _ = s.apply("p1", [{"op": "instructions.set", "text": ""}])
    assert bp.instructions(doc, s.default_instructions()) == "Read the docs first."
    with pytest.raises(bp.BlueprintError):
        s.set_default_instructions("x" * (bp.MAX_TEXT + 1))
