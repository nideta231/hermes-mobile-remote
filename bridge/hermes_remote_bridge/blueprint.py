"""Project blueprints: a flow diagram (like draw.io) and a task board (like Trello) per Hermes project.

One JSON file per project in the blueprints directory, shared by the phone and Hermes. Every change
is an *op* (``card.move``, ``node.add``...) applied under a file lock to the newest copy on disk, so
the phone and Hermes editing at the same time never overwrite each other's work; each applied batch
bumps ``version``. Removing a blueprint moves the file to ``.trash/`` where it can be restored.

    {
      "project": {"id": "...", "name": "Shop app", "path": "/home/me/shop"},
      "version": 12, "created": 1793200000.0, "updated": 1793200500.0,
      "control": "shared",                        # or "hermes": Hermes drives, the phone mostly reads
      "instructions": "Never deploy to live unless asked",   # the user's standing rules for agents
      "settings": {"new_cards": "bottom", "aging_days": 0},   # board settings (see SETTINGS)
      "diagram": {"nodes": [{"id": "n1", "kind": "start", "label": "Start", "x": 0, "y": 0, ...}],
                  "edges": [{"id": "e1", "from": "n1", "to": "n2", "label": ""}]},
      "board": {"columns": [{"id": "todo", "name": "Todo", "color": "blue", "limit": 3}],   # limit: WIP cap or absent
                "cards": [{"id": "c1", "column": "todo", "title": "...", "tags": [], "checklist": [...],
                           "agent": "Hermes",           # who is actively working on it right now (or null)
                           "comments": [{"id": "m1", "at": 0.0, "by": "hermes", "kind": "question",
                                         "text": "Deploy now?", "resolved": false}]}]},
      "log": [{"at": 1793200500.0, "by": "hermes", "text": "Moved Login to Done"}]
    }
"""
from __future__ import annotations

import contextlib
import copy
import json
import os
import re
import secrets
import tempfile
import time
from pathlib import Path

NODE_KINDS = ("start", "end", "feature", "flow", "decision", "note")
COLORS = ("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "gray")
PRIORITIES = (None, "low", "medium", "high")
ASSIGNEES = (None, "me", "hermes")
CONTROL = ("shared", "hermes")
COMMENT_KINDS = ("comment", "question", "review")  # question/review = waiting for the user until resolved
MAX_COMMENTS = 200
# Board settings, shared by the phone and Hermes. new_cards: where a card added (or moved into another
# column) without an explicit position lands. aging_days: fade cards untouched that long (0 = off).
SETTINGS = {"new_cards": ("bottom", "top"), "aging_days": (0, 3, 7, 14, 30), "sticky_add": (False, True)}
MAX_COMMENT_FILES = 10
MAX_CARD_FILES = 30
MAX_UPLOAD = 50 * 1024 * 1024  # one file the phone attaches to a card or comment
DEFAULT_COLUMNS = (("docs", "Docs", "purple"), ("todo", "Todo", "blue"), ("working", "Working", "orange"), ("done", "Done", "green"))
NODE_SIZE = {"start": (120, 56), "end": (120, 56), "decision": (150, 90), "feature": (180, 64), "flow": (180, 56), "note": (170, 70)}
MAX_LOG = 300
# Length limits; longer text is refused (not cut) so neither side silently loses words.
MAX_TITLE, MAX_LABEL, MAX_COLUMN = 600, 400, 120
MAX_TEXT = 20_000
_PID = re.compile(r"^[A-Za-z0-9_.:-]{1,80}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class BlueprintError(ValueError):
    """An op that can't apply (unknown id, bad value). The whole batch is rejected."""


def _id(prefix: str) -> str:
    return prefix + secrets.token_hex(3)


def _text(v, limit: int = 300) -> str:
    return str(v if v is not None else "").strip()[:limit]


def _checked(v, limit: int, what: str) -> str:
    """Like _text, but text over [limit] is an error instead of being cut off."""
    t = str(v if v is not None else "").strip()
    if len(t) > limit:
        raise BlueprintError(f"{what} is {len(t)} characters; the limit is {limit}")
    return t


def new_doc(project_id: str, name: str = "", path: str | None = None) -> dict:
    now = time.time()
    start, end = _id("n"), _id("n")
    return {
        "project": {"id": project_id, "name": name or project_id, "path": path or None},
        "version": 1, "created": now, "updated": now, "control": "shared",
        "diagram": {"nodes": [_node({"id": start, "kind": "start", "label": "Start", "x": 0, "y": 0}),
                              _node({"id": end, "kind": "end", "label": "End", "x": 0, "y": 220})],
                    "edges": [{"id": _id("e"), "from": start, "to": end, "label": ""}]},
        "board": {"columns": [{"id": i, "name": n, "color": c} for i, n, c in DEFAULT_COLUMNS], "cards": []},
        "log": [{"at": now, "by": "system", "text": "Blueprint created"}],
    }


def _node(f: dict) -> dict:
    kind = str(f.get("kind")) if f.get("kind") in NODE_KINDS else "feature"
    w, h = NODE_SIZE[kind]
    return {"id": f.get("id") or _id("n"), "kind": kind, "label": _checked(f.get("label"), MAX_LABEL, "A node label"),
            "x": float(f.get("x") or 0), "y": float(f.get("y") or 0),
            "w": float(f.get("w") or w), "h": float(f.get("h") or h),
            "color": f.get("color") if f.get("color") in COLORS else "default", "note": _text(f.get("note"), 2000)}


# ------------------------------------------------------------------ lookups

def _find(items: list[dict], key, what: str) -> dict:
    key = str(key or "")
    for it in items:
        if it["id"] == key:
            return it
    raise BlueprintError(f"No {what} '{key}'")


def _column(doc: dict, ref) -> dict:
    """A column by id or (case-insensitive) name."""
    ref = str(ref or "").strip()
    cols = doc["board"]["columns"]
    for c in cols:
        if c["id"] == ref:
            return c
    for c in cols:
        if c["name"].lower() == ref.lower():
            return c
    raise BlueprintError(f"No column '{ref}' (columns: {', '.join(c['name'] for c in cols)})")


def _card_ref(doc: dict, ref) -> dict:
    """A card by id, or by a unique case-insensitive title match (what Hermes usually knows)."""
    ref = str(ref or "").strip()
    cards = doc["board"]["cards"]
    for c in cards:
        if c["id"] == ref:
            return c
    exact = [c for c in cards if c["title"].lower() == ref.lower()]
    hits = exact or [c for c in cards if ref.lower() in c["title"].lower()]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise BlueprintError(f"No card '{ref}'")
    raise BlueprintError(f"'{ref}' matches {len(hits)} cards; use the id: " + ", ".join(f"{c['id']} {c['title']}" for c in hits[:6]))


def _node_ref(doc: dict, ref) -> dict:
    ref = str(ref or "").strip()
    nodes = doc["diagram"]["nodes"]
    for n in nodes:
        if n["id"] == ref:
            return n
    hits = [n for n in nodes if n["label"].lower() == ref.lower()] or [n for n in nodes if ref.lower() in n["label"].lower()]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise BlueprintError(f"No diagram node '{ref}'")
    raise BlueprintError(f"'{ref}' matches {len(hits)} nodes; use the id: " + ", ".join(f"{n['id']} {n['label']}" for n in hits[:6]))


def _checklist(v) -> list[dict]:
    out = []
    for it in v or []:
        if isinstance(it, str):
            it = {"text": it}
        if isinstance(it, dict) and _text(it.get("text")):
            out.append({"text": _text(it.get("text")), "done": bool(it.get("done"))})
    return out[:100]


def _tags(v) -> list[str]:
    if isinstance(v, str):
        v = [v]
    return list(dict.fromkeys(_text(t, 40).lstrip("#") for t in (v or []) if _text(t, 40).lstrip("#")))[:20]


def _card_fields(doc: dict, card: dict, f: dict) -> None:
    if "title" in f:
        card["title"] = _checked(f["title"], MAX_TITLE, "A card title") or card.get("title") or "Untitled"
    if "body" in f:
        card["body"] = str(f["body"] or "")[:MAX_TEXT]
    if "tags" in f:
        card["tags"] = _tags(f["tags"])
    if "checklist" in f:
        card["checklist"] = _checklist(f["checklist"])
    if "priority" in f:
        if f["priority"] not in PRIORITIES:
            raise BlueprintError(f"priority must be one of low, medium, high or null")
        card["priority"] = f["priority"]
    if "due" in f:
        if f["due"] not in (None, "") and not _DATE.match(str(f["due"])):
            raise BlueprintError("due must be YYYY-MM-DD or null")
        card["due"] = f["due"] or None
    if "assignee" in f:
        if f["assignee"] not in ASSIGNEES:
            raise BlueprintError("assignee must be me, hermes or null")
        card["assignee"] = f["assignee"]
    if "node" in f:
        card["node"] = _node_ref(doc, f["node"])["id"] if f["node"] else None
    if "color" in f:
        card["color"] = f["color"] if f["color"] in COLORS else "default"
    if "agent" in f:
        card["agent"] = _text(f["agent"], 40) or None
    if "files" in f:  # files attached to the description (paths on the PC, like comment files)
        card["files"] = [str(x).strip()[:1000] for x in (f["files"] or []) if str(x).strip()][:MAX_CARD_FILES]
    if "session" in f:  # the Hermes chat working on it: the phone's "Tell Hermes I replied" goes there
        card["session"] = _text(f["session"], 80) or None


def open_asks(card: dict) -> list[dict]:
    """The card's questions and review requests nobody has resolved yet."""
    return [m for m in card.get("comments") or [] if m.get("kind") in ("question", "review") and not m.get("resolved")]


def _last_column(doc: dict) -> str:
    return doc["board"]["columns"][-1]["id"]


def _limit(v) -> int | None:
    try:
        n = int(v or 0)
    except (TypeError, ValueError):
        raise BlueprintError("limit is a number (0 or null for none)") from None
    if n < 0 or n > 999:
        raise BlueprintError("limit is between 0 and 999")
    return n or None


def _allowed(k: str, v) -> bool:
    # bool is an int in Python: keep True/False out of the number settings and numbers out of the flags
    flag = isinstance(SETTINGS[k][0], bool)
    return (isinstance(v, bool) == flag) and v in SETTINGS[k]


def clean_settings(v) -> dict:
    """Only known keys with allowed values (a board's own overrides, or the global defaults)."""
    v = v if isinstance(v, dict) else {}
    return {k: v[k] for k in SETTINGS if k in v and _allowed(k, v[k])}


def settings(doc: dict, defaults: dict | None = None) -> dict:
    """The board's settings: its own choice, else the global default, else the built-in one."""
    own, glob = clean_settings(doc.get("settings")), clean_settings(defaults)
    return {k: own.get(k, glob.get(k, allowed[0])) for k, allowed in SETTINGS.items()}


def _default_index(doc: dict) -> int | None:
    return 0 if settings(doc, doc.get("_defaults"))["new_cards"] == "top" else None


def _order_after(cards: list[dict], column: str, index: int | None) -> float:
    same = sorted((c for c in cards if c["column"] == column), key=lambda c: c["order"])
    if index is None or index >= len(same):
        return (same[-1]["order"] + 1) if same else 1.0
    index = max(0, index)
    before = same[index - 1]["order"] if index > 0 else same[0]["order"] - 2
    return (before + same[index]["order"]) / 2


def _place(doc: dict, node: dict, near: str | None = None) -> None:
    """A node without coordinates goes below [near] (or below everything), so Hermes can add nodes blind."""
    nodes = [n for n in doc["diagram"]["nodes"] if n["id"] != node["id"]]
    if not nodes:
        return
    anchor = next((n for n in nodes if n["id"] == near), None)
    if anchor:
        node["x"] = anchor["x"] + (anchor["w"] - node["w"]) / 2
        node["y"] = anchor["y"] + anchor["h"] + 70
        while any(abs(n["x"] - node["x"]) < 40 and abs(n["y"] - node["y"]) < 40 for n in nodes):
            node["x"] += node["w"] + 40
    else:
        bottom = max(nodes, key=lambda n: n["y"] + n["h"])
        node["x"], node["y"] = bottom["x"], bottom["y"] + bottom["h"] + 70


def layout(doc: dict) -> None:
    """Tidy the diagram top to bottom: rank = longest path from a start, side by side within a rank."""
    nodes = doc["diagram"]["nodes"]
    ids = {n["id"] for n in nodes}
    out: dict[str, list[str]] = {i: [] for i in ids}
    indeg = {i: 0 for i in ids}
    for e in doc["diagram"]["edges"]:
        if e["from"] in ids and e["to"] in ids and e["from"] != e["to"]:
            out[e["from"]].append(e["to"])
            indeg[e["to"]] += 1
    rank = {i: 0 for i in ids}
    queue = [n["id"] for n in nodes if indeg[n["id"]] == 0]
    seen: set[str] = set()
    while queue:  # Kahn; a cycle's leftovers keep the rank they reached
        cur = queue.pop(0)
        seen.add(cur)
        for nxt in out[cur]:
            rank[nxt] = max(rank[nxt], rank[cur] + 1)
            indeg[nxt] -= 1
            if indeg[nxt] == 0:
                queue.append(nxt)
    for n in nodes:  # cycles: put unvisited nodes after their deepest visited parent
        if n["id"] not in seen:
            rank[n["id"]] = max([rank[i] + 1 for i in ids if n["id"] in out[i] and i in seen] or [0])
    ends = [n for n in nodes if n["kind"] == "end"]
    deepest = max(rank.values(), default=0)
    for n in ends:
        rank[n["id"]] = max(rank[n["id"]], deepest)
    rows: dict[int, list[dict]] = {}
    for n in nodes:
        rows.setdefault(rank[n["id"]], []).append(n)
    y = 0.0
    for r in sorted(rows):
        row = sorted(rows[r], key=lambda n: n["x"])
        width = sum(n["w"] for n in row) + 60 * (len(row) - 1)
        x = -width / 2
        for n in row:
            n["x"], n["y"] = x, y
            x += n["w"] + 60
        y += max(n["h"] for n in row) + 80


# ------------------------------------------------------------------ ops

def apply_op(doc: dict, op: dict, by: str = "phone") -> str | None:
    """Apply one op in place. Returns a short log line (None for ops not worth logging)."""
    kind = op.get("op")
    d, b = doc["diagram"], doc["board"]
    if kind == "node.add":
        n = _node(op)
        if any(x["id"] == n["id"] for x in d["nodes"]):
            raise BlueprintError(f"Node id '{n['id']}' exists")
        if "x" not in op or "y" not in op:
            d["nodes"].append(n)
            _place(doc, n, _node_ref(doc, op["after"])["id"] if op.get("after") else None)
        else:
            d["nodes"].append(n)
        if op.get("after"):  # connect from the node it follows; a single arrow out of it is spliced through
            prev = _node_ref(doc, op["after"])["id"]
            outs = [e for e in d["edges"] if e["from"] == prev]
            if len(outs) == 1 and op.get("splice", True):
                outs[0]["from"] = n["id"]
            d["edges"].append({"id": _id("e"), "from": prev, "to": n["id"], "label": ""})
        op["id"] = n["id"]
        return f"Added {n['kind']} '{n['label']}'"
    if kind == "node.update":
        n = _node_ref(doc, op.get("id"))
        for k in ("label", "note"):
            if k in op:
                n[k] = _checked(op[k], MAX_LABEL, "A node label") if k == "label" else _text(op[k], 2000)
        for k in ("x", "y", "w", "h"):
            if k in op:
                n[k] = float(op[k])
        if "kind" in op and op["kind"] in NODE_KINDS:
            n["kind"] = op["kind"]
        if "color" in op:
            n["color"] = op["color"] if op["color"] in COLORS else "default"
        moved_only = set(op) <= {"op", "id", "x", "y"}
        return None if moved_only else f"Edited node '{n['label']}'"
    if kind == "node.remove":
        n = _node_ref(doc, op.get("id"))
        d["nodes"].remove(n)
        d["edges"] = [e for e in d["edges"] if n["id"] not in (e["from"], e["to"])]
        for c in b["cards"]:
            if c.get("node") == n["id"]:
                c["node"] = None
        return f"Removed node '{n['label']}'"
    if kind == "edge.add":
        a, z = _node_ref(doc, op.get("from"))["id"], _node_ref(doc, op.get("to"))["id"]
        if a == z:
            raise BlueprintError("An arrow needs two different nodes")
        if any(e["from"] == a and e["to"] == z for e in d["edges"]):
            return None
        e = {"id": op.get("id") or _id("e"), "from": a, "to": z, "label": _text(op.get("label"), 80)}
        d["edges"].append(e)
        op["id"] = e["id"]
        return "Connected two nodes"
    if kind == "edge.update":
        e = _find(d["edges"], op.get("id"), "arrow")
        e["label"] = _text(op.get("label"), 80)
        return None
    if kind == "edge.remove":
        e = _find(d["edges"], op.get("id"), "arrow")
        d["edges"].remove(e)
        return "Removed an arrow"
    if kind == "diagram.layout":
        layout(doc)
        return "Tidied the diagram"
    if kind == "column.add":
        name = _checked(op.get("name"), MAX_COLUMN, "A column name")
        if not name:
            raise BlueprintError("A column needs a name")
        cid = op.get("id") or re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or _id("col")
        if any(c["id"] == cid for c in b["columns"]):
            cid = _id("col")
        b["columns"].append({"id": cid, "name": name, "color": op.get("color") if op.get("color") in COLORS else "default"})
        if op.get("limit"):
            b["columns"][-1]["limit"] = _limit(op["limit"])
        op["id"] = cid
        return f"Added column '{name}'"
    if kind == "column.update":
        c = _column(doc, op.get("id"))
        if _checked(op.get("name"), MAX_COLUMN, "A column name"):
            c["name"] = _checked(op["name"], MAX_COLUMN, "A column name")
        if "color" in op:
            c["color"] = op["color"] if op["color"] in COLORS else "default"
        if "limit" in op:  # WIP cap: a positive number, or 0/null for none
            c.pop("limit", None)
            if _limit(op["limit"]):
                c["limit"] = _limit(op["limit"])
                return f"Set the card limit of '{c['name']}' to {c['limit']}"
            return f"Removed the card limit of '{c['name']}'"
        return f"Edited column '{c['name']}'"
    if kind == "column.move":
        c = _column(doc, op.get("id"))
        b["columns"].remove(c)
        b["columns"].insert(max(0, min(int(op.get("index", 0)), len(b["columns"]))), c)
        return None
    if kind == "column.remove":
        c = _column(doc, op.get("id"))
        if len(b["columns"]) == 1:
            raise BlueprintError("A board keeps at least one column")
        b["columns"].remove(c)
        dest = _column(doc, op["move_to"])["id"] if op.get("move_to") else b["columns"][0]["id"]
        for card in b["cards"]:
            if card["column"] == c["id"]:
                card["column"], card["order"] = dest, _order_after(b["cards"], dest, None)
        return f"Removed column '{c['name']}'"
    if kind == "card.add":
        col = _column(doc, op.get("column") or b["columns"][min(1, len(b["columns"]) - 1)]["id"])
        now = time.time()
        card = {"id": op.get("id") or _id("c"), "column": col["id"], "title": "", "body": "", "tags": [], "checklist": [],
                "priority": None, "due": None, "assignee": None, "node": None, "color": "default",
                "order": _order_after(b["cards"], col["id"], op["index"] if "index" in op else _default_index(doc)),
                "created": now, "updated": now}
        _card_fields(doc, card, {"title": op.get("title") or "Untitled", **{k: v for k, v in op.items() if k not in ("op", "id", "column", "index", "title")}})
        b["cards"].append(card)
        op["id"] = card["id"]
        return f"Added card '{card['title']}' to {col['name']}"
    if kind == "card.update":
        card = _card_ref(doc, op.get("id"))
        _card_fields(doc, card, {k: v for k, v in op.items() if k not in ("op", "id")})
        card["updated"] = time.time()
        return f"Edited card '{card['title']}'"
    if kind == "card.check":  # tick a checklist item by index or by text
        card = _card_ref(doc, op.get("id"))
        items, item = card["checklist"], None
        if isinstance(op.get("index"), int) and 0 <= op["index"] < len(items):
            item = items[op["index"]]
        else:
            needle = _text(op.get("text")).lower()
            item = next((i for i in items if needle and needle in i["text"].lower() and i["done"] != op.get("done", True)), None)
        if item is None:
            raise BlueprintError(f"No checklist item '{op.get('text', op.get('index'))}' on '{card['title']}'")
        item["done"] = bool(op.get("done", True))
        card["updated"] = time.time()
        return f"{'Ticked' if item['done'] else 'Unticked'} '{item['text']}' on '{card['title']}'"
    if kind == "card.move":
        card = _card_ref(doc, op.get("id"))
        col = _column(doc, op.get("column") or card["column"])
        others = [c for c in b["cards"] if c is not card]
        # No position: into another column it lands where the settings say; within its column, at the end.
        index = op["index"] if "index" in op else (_default_index(doc) if col["id"] != card["column"] else None)
        card["column"], card["order"] = col["id"], _order_after(others, col["id"], index)
        if col["id"] == _last_column(doc):  # finished: nobody is working on it any more
            card["agent"] = None
        card["updated"] = time.time()
        return f"Moved '{card['title']}' to {col['name']}"
    if kind == "card.comment":  # a note on a card; question/review = needs the user's answer
        card = _card_ref(doc, op.get("id"))
        text = str(op.get("text") or "").strip()[:MAX_TEXT]
        ck = op.get("kind") or "comment"
        if ck not in COMMENT_KINDS:
            raise BlueprintError("comment kind is comment, question or review")
        files = [str(f).strip() for f in (op.get("files") or []) if str(f).strip()][:MAX_COMMENT_FILES]
        if not text and not files:
            raise BlueprintError("A comment needs text or a file")
        m = {"id": op.get("comment_id") or _id("m"), "at": time.time(), "by": by, "kind": ck, "text": text, "resolved": False, "files": files}
        if _text(op.get("session"), 80):  # the Hermes chat that wrote it
            m["session"] = _text(op["session"], 80)
            card["session"] = m["session"]
        card["comments"] = ((card.get("comments") or []) + [m])[-MAX_COMMENTS:]
        if op.get("resolves"):  # an answer closes what it answers
            for q in open_asks(card):
                if q is not m:
                    q["resolved"] = True
        card["updated"] = time.time()
        op["comment_id"] = m["id"]
        word = {"comment": "Commented on", "question": "Asked about", "review": "Asked for a review of"}[ck]
        return f"{word} '{card['title']}': {text[:120] or ', '.join(os.path.basename(f) for f in files)}"
    if kind == "comment.resolve":  # resolve (or reopen) one comment, or every open ask on the card
        card = _card_ref(doc, op.get("id"))
        done = bool(op.get("resolved", True))
        cid = op.get("comment_id")
        targets = [m for m in card.get("comments") or [] if m["id"] == cid] if cid else open_asks(card)
        if cid and not targets:
            raise BlueprintError(f"No comment '{cid}' on '{card['title']}'")
        for m in targets:
            m["resolved"] = done
        card["updated"] = time.time()
        return f"{'Resolved' if done else 'Reopened'} {len(targets)} on '{card['title']}'" if targets else None
    if kind == "comment.remove":
        card = _card_ref(doc, op.get("id"))
        before = len(card.get("comments") or [])
        card["comments"] = [m for m in card.get("comments") or [] if m["id"] != op.get("comment_id")]
        if len(card["comments"]) == before:
            raise BlueprintError(f"No comment '{op.get('comment_id')}'")
        return None
    if kind == "instructions.set":  # the user's standing rules for any agent working on this project
        doc["instructions"] = str(op.get("text") or "").strip()[:MAX_TEXT]
        return "Updated the agent instructions" if doc["instructions"] else "Cleared the agent instructions"
    if kind == "settings.set":  # this board's settings: any of SETTINGS' keys; null/"default" = follow the global default
        cur = clean_settings(doc.get("settings"))
        for k, v in op.items():
            if k == "op":
                continue
            if k not in SETTINGS:
                raise BlueprintError(f"Unknown setting '{k}' (known: {', '.join(SETTINGS)})")
            if v in (None, "default", "global"):
                cur.pop(k, None)
                continue
            if not _allowed(k, v):
                raise BlueprintError(f"{k} must be one of {', '.join(map(str, SETTINGS[k]))} or default")
            cur[k] = v
        doc["settings"] = cur
        return "Changed the board settings"
    if kind == "card.remove":
        card = _card_ref(doc, op.get("id"))
        b["cards"].remove(card)
        return f"Removed card '{card['title']}'"
    if kind == "control.set":
        mode = op.get("mode")
        if mode not in CONTROL:
            raise BlueprintError("control mode is shared or hermes")
        doc["control"] = mode
        return "Hermes is in control of this blueprint" if mode == "hermes" else "Control is shared again"
    if kind == "project.update":
        if _text(op.get("name")):
            doc["project"]["name"] = _text(op["name"])
        if "path" in op:
            doc["project"]["path"] = op["path"] or None
        return None
    if kind == "log":
        return _text(op.get("text"), 500) or None
    raise BlueprintError(f"Unknown op '{kind}'")


def apply_ops(doc: dict, ops: list[dict], by: str = "phone", defaults: dict | None = None) -> tuple[dict, list[dict]]:
    """Apply a batch to a copy: all ops land, or none (the first error is raised). [defaults]: global board settings."""
    if not isinstance(ops, list) or not ops:
        raise BlueprintError("ops must be a non-empty list")
    new = copy.deepcopy(doc)
    new["_defaults"] = clean_settings(defaults)  # read by ops while applying; never saved
    now = time.time()
    applied = []
    for op in ops:
        if not isinstance(op, dict):
            raise BlueprintError("each op is an object")
        op = dict(op)
        line = apply_op(new, op, by)
        applied.append(op)
        if line:
            new["log"].append({"at": now, "by": by, "text": line})
    new.pop("_defaults", None)
    new["log"] = new["log"][-MAX_LOG:]
    new["version"] = int(doc.get("version", 0)) + 1
    new["updated"] = now
    return new, applied


def latest_comment(doc: dict) -> dict | None:
    """The newest comment not written from the phone (Hermes or another agent), for unread badges and alerts."""
    best = None
    for c in doc["board"]["cards"]:
        for m in c.get("comments") or []:
            if m.get("by") != "phone" and (best is None or m["at"] > best["at"]):
                best = {"card": c["id"], "title": c["title"], "at": m["at"], "by": m["by"], "kind": m["kind"], "text": m["text"][:300]}
    return best


def summary(doc: dict) -> dict:
    cards = doc["board"]["cards"]
    cols = doc["board"]["columns"]
    return {"latest_comment": latest_comment(doc),"project": doc["project"], "version": doc["version"], "updated": doc["updated"], "control": doc.get("control", "shared"),
            "nodes": len(doc["diagram"]["nodes"]), "cards": len(cards),
            "needs_you": sum(1 for c in cards if open_asks(c)), "working": sum(1 for c in cards if c.get("agent")),
            "columns": [{"id": c["id"], "name": c["name"], "count": sum(1 for x in cards if x["column"] == c["id"]), "limit": c.get("limit")}
                        for c in cols]}


def instructions(doc: dict, global_text: str = "") -> str:
    """The rules that apply on this board: its own, else the global instructions every board follows."""
    return (doc.get("instructions") or "").strip() or (global_text or "").strip()


def to_markdown(doc: dict, defaults: dict | None = None, global_instructions: str = "") -> str:
    """What Hermes reads before working on the project: the flow, then the board, then recent activity."""
    p = doc["project"]
    d, b = doc["diagram"], doc["board"]
    by_id = {n["id"]: n for n in d["nodes"]}
    lines = [f"# Blueprint: {p['name']}", f"project id {p['id']}" + (f", folder {p['path']}" if p.get("path") else "")
             + f", version {doc['version']}, control: {doc.get('control', 'shared')}", ""]
    rules = instructions(doc, global_instructions)
    if rules:
        lines += ["## Instructions from the user (always follow these on this project)"
                  + ("" if (doc.get("instructions") or "").strip() else " (global, every board)"), rules, ""]
    asks = [(c, m) for c in b["cards"] for m in open_asks(c)]
    if asks:
        lines += ["## Waiting for the user (open questions and reviews)"]
        lines += [f"- {c['title']}  `{c['id']}`: {m['kind']} by {m['by']}: {m['text'][:200]}" for c, m in asks]
        lines.append("")
    lines.append("## Flow")
    cards_by_node = {c["node"]: c for c in b["cards"] if c.get("node")}
    cols = {c["id"]: c["name"] for c in b["columns"]}
    for n in sorted(d["nodes"], key=lambda n: (n["y"], n["x"])):
        nxt = [f"{by_id[e['to']]['label'] or e['to']}" + (f" ({e['label']})" if e["label"] else "")
               for e in d["edges"] if e["from"] == n["id"] and e["to"] in by_id]
        card = cards_by_node.get(n["id"])
        lines.append(f"- [{n['kind']}] {n['label'] or '(no label)'}  `{n['id']}`"
                     + (f"  -> {', '.join(nxt)}" if nxt else "")
                     + (f"  [card: {card['title']} in {cols.get(card['column'], '?')}]" if card else "")
                     + (f"\n  note: {n['note']}" if n.get("note") else ""))
    st = settings(doc, defaults)
    lines += ["", "## Board", f"settings: new and moved cards go to the {st['new_cards']} of a column"
              + (f", cards untouched for {st['aging_days']} days fade" if st["aging_days"] else "")]
    for col in b["columns"]:
        cards = sorted((c for c in b["cards"] if c["column"] == col["id"]), key=lambda c: c["order"])
        lim = col.get("limit")
        lines.append(f"### {col['name']} ({len(cards)}" + (f" of max {lim}" + (", OVER THE LIMIT: finish or move cards out before adding more" if len(cards) > lim else "") if lim else "") + ")")
        for c in cards:
            meta = [f"#{t}" for t in c["tags"]]
            if c.get("priority"):
                meta.append(f"priority {c['priority']}")
            if c.get("due"):
                meta.append(f"due {c['due']}")
            if c.get("assignee"):
                meta.append(f"@{c['assignee']}")
            if c.get("agent"):
                meta.append(f"{c['agent']} working")
            if open_asks(c):
                meta.append("NEEDS USER")
            if c.get("node") and c["node"] in by_id:
                meta.append(f"node: {by_id[c['node']]['label']}")
            done = sum(1 for i in c["checklist"] if i["done"])
            if c["checklist"]:
                meta.append(f"{done}/{len(c['checklist'])} done")
            lines.append(f"- {c['title']}  `{c['id']}`" + (f"  ({', '.join(meta)})" if meta else ""))
            if c["body"].strip():
                lines += ["  " + l for l in c["body"].strip().splitlines()[:12]]
            if c.get("files"):
                lines.append("  attached: " + ", ".join(c["files"]))
            lines += [f"  - [{'x' if i['done'] else ' '}] {i['text']}" for i in c["checklist"]]
            for m in (c.get("comments") or [])[-6:]:
                state = "" if m["kind"] == "comment" else (" (resolved)" if m.get("resolved") else " (OPEN)")
                lines.append(f"  > {m['by']} {m['kind']}{state}: " + " ".join(m["text"].split())[:300]
                             + "".join(f" [file: {f}]" for f in m.get("files") or []))
    if doc["log"]:
        lines += ["", "## Recent activity"]
        lines += [f"- {time.strftime('%Y-%m-%d %H:%M', time.localtime(l['at']))} {l['by']}: {l['text']}" for l in doc["log"][-8:]]
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------------ storage

class BlueprintStore:
    def __init__(self, root: Path):
        self.root = Path(root).expanduser()
        self.trash_dir = self.root / ".trash"

    def _path(self, project_id: str) -> Path:
        if not _PID.match(project_id or ""):
            raise KeyError(project_id)
        return self.root / f"{project_id}.json"

    def _write(self, path: Path, doc: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        if os.name != "nt":
            os.chmod(self.root, 0o700)
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)

    @contextlib.contextmanager
    def _lock(self):
        """One writer at a time across processes (the bridge and Hermes' CLI)."""
        self.root.mkdir(parents=True, exist_ok=True)
        with open(self.root / ".lock", "a+") as fh:
            if os.name == "nt":
                import msvcrt

                fh.seek(0)
                while True:
                    try:
                        msvcrt.locking(fh.fileno(), msvcrt.LK_LOCK, 1)
                        break
                    except OSError:  # LK_LOCK gives up after ~10 s; keep waiting
                        time.sleep(0.05)
                try:
                    yield
                finally:
                    fh.seek(0)
                    msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(fh, fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.flock(fh, fcntl.LOCK_UN)

    def exists(self, project_id: str) -> bool:
        try:
            return self._path(project_id).exists()
        except KeyError:
            return False

    def get(self, project_id: str) -> dict:
        p = self._path(project_id)
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except FileNotFoundError:
            raise KeyError(project_id) from None

    def all(self) -> list[dict]:
        if not self.root.is_dir():
            return []
        out = []
        for p in sorted(self.root.glob("*.json")):
            try:
                out.append(json.loads(p.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                continue
        return out

    def create(self, project_id: str, name: str = "", path: str | None = None) -> dict:
        with self._lock():
            p = self._path(project_id)
            if p.exists():
                raise BlueprintError("This project already has a blueprint")
            doc = new_doc(project_id, name, path)
            self._write(p, doc)
            return doc

    # Global board settings (every board without its own choice uses them). Not *.json: all() skips it.
    def defaults(self) -> dict:
        try:
            return clean_settings(json.loads((self.root / "defaults.settings").read_text(encoding="utf-8")))
        except (OSError, ValueError):
            return {}

    def set_defaults(self, changes: dict) -> dict:
        cur = self.defaults()
        for k, v in (changes or {}).items():
            if k not in SETTINGS:
                raise BlueprintError(f"Unknown setting '{k}' (known: {', '.join(SETTINGS)})")
            if v in (None, "default"):
                cur.pop(k, None)
            elif _allowed(k, v):
                cur[k] = v
            else:
                raise BlueprintError(f"{k} must be one of {', '.join(map(str, SETTINGS[k]))}")
        with self._lock():
            self._write(self.root / "defaults.settings", cur)
        return cur

    # Global agent instructions: boards with no instructions of their own follow them.
    def default_instructions(self) -> str:
        try:
            return (self.root / "defaults.instructions").read_text(encoding="utf-8").strip()
        except OSError:
            return ""

    def set_default_instructions(self, text: str) -> str:
        text = str(text or "").strip()
        if len(text) > MAX_TEXT:
            raise BlueprintError(f"Instructions are at most {MAX_TEXT} characters")
        with self._lock():
            self.root.mkdir(parents=True, exist_ok=True)
            f = self.root / "defaults.instructions"
            tmp = f.with_suffix(".tmp")
            tmp.write_text(text, encoding="utf-8")
            os.replace(tmp, f)
        return text

    def apply(self, project_id: str, ops: list[dict], by: str = "phone", base: int | None = None) -> tuple[dict, list[dict]]:
        defaults = self.defaults()
        with self._lock():
            doc = self.get(project_id)
            new, applied = apply_ops(doc, ops, by, defaults)
            self._write(self._path(project_id), new)
            return new, applied

    def upload(self, project_id: str, name: str, data: bytes) -> str:
        """Store a file the phone attaches (card description or comment); returns its absolute path."""
        self._path(project_id)  # validates the id
        if len(data) > MAX_UPLOAD:
            raise BlueprintError(f"File too large (max {MAX_UPLOAD // (1024 * 1024)} MB)")
        d = self.root / "files" / project_id
        d.mkdir(parents=True, exist_ok=True)
        stem, ext = os.path.splitext(safe_name(name))
        target = d / f"{stem}{ext}"
        n = 1
        while target.exists():
            n += 1
            target = d / f"{stem}-{n}{ext}"
        tmp = target.with_name(f".tmp-{target.name}")
        tmp.write_bytes(data)
        os.replace(tmp, target)
        return str(target)

    def trash(self, project_id: str) -> str:
        with self._lock():
            p = self._path(project_id)
            if not p.exists():
                raise KeyError(project_id)
            tid = f"{project_id}--{int(time.time())}"
            self.trash_dir.mkdir(parents=True, exist_ok=True)
            os.replace(p, self.trash_dir / f"{tid}.json")
            return tid

    def trashed(self) -> list[dict]:
        if not self.trash_dir.is_dir():
            return []
        out = []
        for f in self.trash_dir.glob("*.json"):
            try:
                doc = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            out.append({"id": f.stem, "removed_at": f.stat().st_mtime, **summary(doc)})
        return sorted(out, key=lambda x: -x["removed_at"])

    def _trash_path(self, trash_id: str) -> Path:
        if not re.match(r"^[A-Za-z0-9_.:-]{1,80}--\d+$", trash_id or ""):
            raise KeyError(trash_id)
        f = self.trash_dir / f"{trash_id}.json"
        if not f.exists():
            raise KeyError(trash_id)
        return f

    def restore(self, trash_id: str) -> dict:
        with self._lock():
            f = self._trash_path(trash_id)
            pid = trash_id.rsplit("--", 1)[0]
            dest = self._path(pid)
            if dest.exists():
                raise BlueprintError("The project has a new blueprint; remove it first to restore this one")
            os.replace(f, dest)
            return json.loads(dest.read_text(encoding="utf-8"))

    def purge(self, trash_id: str) -> None:
        with self._lock():
            self._trash_path(trash_id).unlink()


def comment_file(doc: dict, path: str) -> str | None:
    """Real path of [path] when a comment on this blueprint attaches it (the phone may fetch only these)."""
    root = (doc.get("project") or {}).get("path")

    def real(p: str) -> str | None:
        p = os.path.expanduser(p)
        if not os.path.isabs(p):
            if not root:
                return None
            p = os.path.join(root, p)
        return os.path.realpath(p)

    wanted = real(path)
    if wanted is None:
        return None
    for c in doc["board"]["cards"]:
        if any(real(f) == wanted for f in c.get("files") or []):
            return wanted
        for m in c.get("comments") or []:
            if any(real(f) == wanted for f in m.get("files") or []):
                return wanted
    return None


def safe_name(name: str) -> str:
    """A file name safe to store: no folders, no odd characters, never empty or hidden."""
    base = os.path.basename(str(name or "").replace("\\", "/")).strip()
    base = re.sub(r"[^A-Za-z0-9._ ()+-]", "_", base).strip(" .")[:120]
    return base or "file"


def hermes_projects(hermes_home: Path) -> list[dict]:
    """Saved Hermes projects (id, name, folder), read-only from projects.db."""
    import sqlite3

    db = Path(hermes_home) / "projects.db"
    if not db.exists():
        return []
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=3)
        try:
            rows = con.execute("select id, name, primary_path from projects where coalesce(archived, 0) = 0").fetchall()
        finally:
            con.close()
    except sqlite3.Error:
        return []
    return [{"id": r[0], "name": r[1], "path": r[2] or None} for r in rows]


def resolve(store: BlueprintStore, ref: str | None, hermes_home: Path | None = None, cwd: str | None = None) -> dict:
    """A project by id, name or folder (a path inside the project folder counts). Blueprints win over
    Hermes projects without one; returns {"id", "name", "path", "exists"}."""
    docs = store.all()
    known = [{**d["project"], "exists": True} for d in docs]
    ids = {k["id"] for k in known}
    if hermes_home:
        known += [{**p, "exists": False} for p in hermes_projects(hermes_home) if p["id"] not in ids]
    if ref:
        r = ref.strip()
        for k in known:
            if k["id"] == r:
                return k
        hits = [k for k in known if (k.get("name") or "").lower() == r.lower()]
        hits = hits or [k for k in known if r.lower() in (k.get("name") or "").lower()]
        if len(hits) == 1:
            return hits[0]
        if len(hits) > 1:
            raise BlueprintError(f"'{r}' matches several projects: " + ", ".join(f"{h['name']} ({h['id']})" for h in hits[:6]))
        cwd = cwd or (r if os.sep in r else None)
        if not cwd:
            raise BlueprintError(f"No project '{r}'")
    if cwd:
        here = os.path.realpath(os.path.expanduser(cwd))
        best = None
        for k in known:
            p = k.get("path")
            if not p:
                continue
            root = os.path.realpath(os.path.expanduser(p))
            if here == root or here.startswith(root.rstrip(os.sep) + os.sep):
                if best is None or len(root) > len(best[0]):
                    best = (root, k)
        if best:
            return best[1]
        raise BlueprintError(f"No project owns the folder {cwd}")
    raise BlueprintError("Name a project (id, name or folder)")
