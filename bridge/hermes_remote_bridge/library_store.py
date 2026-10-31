"""Memory Library store: the Postgres side, shared by the bridge and the Hermes memory plugin.

Standard library only: the Hermes plugin copy of this file (``store.py``) runs inside Hermes'
own Python, which has no Postgres driver. Every query goes through the pinned ``psql`` that the
Library runtime ships, with values passed as psql variables (``:'name'``, quoted by psql itself),
so user text never becomes SQL.

One Postgres cluster per PC (owned by the feature, never the user's own Postgres), one database
per Hermes profile.
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = 1
KINDS = ("mistake", "rule", "preference", "workflow", "fact", "decision")
STATUSES = ("active", "superseded", "archived")
RELS = ("supersedes", "causes", "related")
MAX_TEXT = 2000
MAX_KEYWORDS = 600
# psql socket port number: only names the socket file, nothing listens on TCP.
PORT = 54329
PG_VERSION = "18.1.0"
SCOPE_RE = re.compile(r"^(global|project:[A-Za-z0-9_.-]{1,64}|client:[A-Za-z0-9 _.&'-]{1,64})$")
# Repeats at which a bumped mistake/rule climbs one severity step.
ESCALATE_AT = (2, 4, 7)
PINNED_MIN_SEVERITY = 4
PINNED_MAX = 10
PINNED_MAX_CHARS = 1800


class StoreError(RuntimeError):
    """A query failed, or the Library is not reachable."""


class Invalid(ValueError):
    """Bad input from a caller (the phone, a tool call): shown to them as is."""


@dataclass(frozen=True)
class Paths:
    """Where the Library keeps its runtime and data (everything under one folder)."""

    root: Path

    @property
    def bin(self) -> Path: return self.root / "bin"
    @property
    def pg0(self) -> Path: return self.bin / ("pg0.exe" if os.name == "nt" else "pg0")
    @property
    def runtime(self) -> Path: return self.root / "runtime"
    @property
    def install(self) -> Path: return self.runtime / ".pg0" / "installation" / PG_VERSION
    @property
    def psql(self) -> Path: return self.install / "bin" / "psql"
    @property
    def data(self) -> Path: return self.root / "data"
    @property
    def run(self) -> Path: return self.root / "run"
    @property
    def secret(self) -> Path: return self.root / "secret"
    @property
    def log(self) -> Path: return self.root / "postgres.log"
    @property
    def state(self) -> Path: return self.root / "state.json"
    @property
    def backups(self) -> Path: return self.root / "backups"


def db_name(profile: str | None) -> str:
    name = (profile or "default").lower()
    return "lib_" + re.sub(r"[^a-z0-9_]", "_", name)[:50]


def profile_of_home(hermes_home: str | Path) -> str:
    """The profile a Hermes home belongs to: ``<home>/profiles/<name>`` or the default one."""
    p = Path(hermes_home)
    return p.name if p.parent.name == "profiles" else "default"


def pg_env(paths: Paths) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
    lib = str(paths.install / "lib")
    env["LD_LIBRARY_PATH"] = lib + (os.pathsep + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    env["PGCONNECT_TIMEOUT"] = "4"
    try:
        env["PGPASSWORD"] = paths.secret.read_text().strip()
    except OSError:
        pass
    return env


def _clean(value) -> str:
    return "" if value is None else str(value).replace("\x00", "")


def tsquery(text: str) -> str:
    """OR of the words in [text] (prefix match for longer ones), safe for to_tsquery('simple')."""
    words, seen = [], set()
    for w in re.findall(r"[^\W_]+", (text or "").lower()):
        if len(w) < 2 or w in seen:
            continue
        seen.add(w)
        words.append(w + ":*" if len(w) >= 4 else w)
        if len(words) >= 32:
            break
    return " | ".join(words)


SCHEMA = f"""
create extension if not exists pg_trgm;
create table if not exists meta (key text primary key, value text not null);
create table if not exists memories (
  id bigserial primary key,
  kind text not null check (kind in ({", ".join(repr(k) for k in KINDS)})),
  severity smallint not null default 2 check (severity between 1 and 5),
  scope text not null default 'global',
  text text not null check (length(text) between 1 and {MAX_TEXT}),
  keywords text not null default '',
  status text not null default 'active' check (status in ({", ".join(repr(s) for s in STATUSES)})),
  review boolean not null default false,
  hits integer not null default 0,
  repeats integer not null default 0,
  source text not null default 'chat',
  source_session text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen timestamptz,
  last_recalled timestamptz,
  search tsvector generated always as (to_tsvector('simple', text || ' ' || keywords)) stored
);
create index if not exists memories_search on memories using gin (search);
create index if not exists memories_trgm on memories using gin ((text || ' ' || keywords) gin_trgm_ops);
create index if not exists memories_scope on memories (scope, status);
create table if not exists links (
  src bigint not null references memories(id) on delete cascade,
  dst bigint not null references memories(id) on delete cascade,
  rel text not null check (rel in ({", ".join(repr(r) for r in RELS)})),
  created_at timestamptz not null default now(),
  primary key (src, dst, rel),
  check (src <> dst)
);
create table if not exists history (
  id bigserial primary key,
  memory_id bigint not null references memories(id) on delete cascade,
  at timestamptz not null default now(),
  by text not null,
  action text not null,
  before jsonb,
  after jsonb
);
create index if not exists history_memory on history (memory_id, at);
insert into meta (key, value) values ('schema_version', '{SCHEMA_VERSION}')
  on conflict (key) do update set value = excluded.value;
"""

_COLS = ("id, kind, severity, scope, text, keywords, status, review, hits, repeats, source, source_session, "
         "created_at, updated_at, last_seen, last_recalled")


class Store:
    """One profile's database in the Library cluster."""

    def __init__(self, paths: Paths, profile: str | None = None, *, timeout: float = 15):
        self.paths = paths
        self.profile = profile or "default"
        self.db = db_name(profile)
        self.timeout = timeout

    # ------------------------------------------------------------------ plumbing

    def _psql(self, sql: str, db: str | None = None, **vars) -> str:
        argv = [str(self.paths.psql), "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                "-h", str(self.paths.run), "-p", str(PORT), "-U", "hermes", "-d", db or self.db]
        for k, v in vars.items():
            argv += ["-v", f"{k}={_clean(v)}"]
        try:
            r = subprocess.run(argv, input=sql, capture_output=True, text=True, timeout=self.timeout,
                               env=pg_env(self.paths), stdin=None)
        except FileNotFoundError:
            raise StoreError("The Memory Library runtime is not installed.") from None
        except subprocess.TimeoutExpired:
            raise StoreError("The Memory Library database did not answer in time.") from None
        if r.returncode != 0:
            msg = (r.stderr or r.stdout).strip().splitlines()
            raise StoreError(msg[-1] if msg else f"psql exited {r.returncode}")
        return r.stdout

    def rows(self, sql: str, **vars) -> list[dict]:
        out = self._psql(f"with t as (\n{sql}\n) select coalesce(json_agg(t), '[]'::json) from t;\n", **vars)
        return json.loads(out.strip() or "[]")

    def one(self, sql: str, **vars) -> dict | None:
        r = self.rows(sql, **vars)
        return r[0] if r else None

    def script(self, sql: str, db: str | None = None) -> None:
        self._psql(sql, db=db)

    # ------------------------------------------------------------------ setup

    def ensure_database(self) -> None:
        exists = self._psql("select 1 from pg_database where datname = :'d';\n", db="postgres", d=self.db).strip()
        if not exists:
            self._psql(f'create database "{self.db}";\n', db="postgres")  # db_name() allows [a-z0-9_] only
        self.script(SCHEMA)

    def schema_version(self) -> int:
        r = self.one("select value from meta where key = 'schema_version'")
        return int(r["value"]) if r else 0

    # ------------------------------------------------------------------ validation

    @staticmethod
    def check(kind=None, severity=None, scope=None, text=None, keywords=None, status=None) -> None:
        if kind is not None and kind not in KINDS:
            raise Invalid(f"kind must be one of {', '.join(KINDS)}")
        if severity is not None and (isinstance(severity, bool) or not isinstance(severity, int)
                                     or not 1 <= severity <= 5):
            raise Invalid("severity must be a whole number from 1 (note) to 5 (fatal)")
        if scope is not None and not SCOPE_RE.match(scope):
            raise Invalid("scope must be global, project:<id> or client:<name>")
        if text is not None and not 1 <= len(text.strip()) <= MAX_TEXT:
            raise Invalid(f"text must be 1 to {MAX_TEXT} characters")
        if keywords is not None and len(keywords) > MAX_KEYWORDS:
            raise Invalid(f"keywords must be at most {MAX_KEYWORDS} characters")
        if status is not None and status not in STATUSES:
            raise Invalid(f"status must be one of {', '.join(STATUSES)}")

    # ------------------------------------------------------------------ memories

    def add(self, text: str, *, kind: str = "fact", severity: int = 2, scope: str = "global", keywords: str = "",
            source: str = "chat", session: str | None = None, review: bool = False, by: str = "hermes") -> dict:
        text, keywords = text.strip(), (keywords or "").strip()
        self.check(kind=kind, severity=severity, scope=scope, text=text, keywords=keywords)
        dup = self.one(f"select {_COLS} from memories where status = 'active' and scope = :'scope' "
                       "and lower(text) = lower(:'text') limit 1", scope=scope, text=text)
        if dup:
            return {**dup, "duplicate": True}
        row = self.one(
            f"insert into memories (kind, severity, scope, text, keywords, source, source_session, review) "
            f"values (:'kind', :'sev'::smallint, :'scope', :'text', :'kw', :'src', nullif(:'sess', ''), :'rev'::boolean) "
            f"returning {_COLS}",
            kind=kind, sev=severity, scope=scope, text=text, kw=keywords, src=source, sess=session or "",
            rev="true" if review else "false")
        self._log(row["id"], by, "create", None, row)
        return row

    def get(self, mid: int) -> dict | None:
        row = self.one(f"select {_COLS} from memories where id = :'id'::bigint", id=int(mid))
        if not row:
            return None
        row["links"] = self.rows(
            "select l.src, l.dst, l.rel, m.text as other_text, m.kind as other_kind from links l "
            "join memories m on m.id = case when l.src = :'id'::bigint then l.dst else l.src end "
            "where l.src = :'id'::bigint or l.dst = :'id'::bigint", id=int(mid))
        row["history"] = self.rows("select at, by, action, before, after from history where memory_id = :'id'::bigint "
                                   "order by at desc limit 50", id=int(mid))
        return row

    def update(self, mid: int, *, by: str = "hermes", **fields) -> dict:
        allowed = {"text", "kind", "severity", "scope", "keywords", "status", "review"}
        fields = {k: v for k, v in fields.items() if k in allowed and v is not None}
        if not fields:
            raise Invalid("Nothing to change")
        if "text" in fields:
            fields["text"] = fields["text"].strip()
        self.check(**{k: v for k, v in fields.items() if k != "review"})
        before = self.one(f"select {_COLS} from memories where id = :'id'::bigint", id=int(mid))
        if not before:
            raise Invalid(f"No memory #{mid}")
        sets = ", ".join(f"{k} = :'{k}'" + ("::smallint" if k == "severity" else "::boolean" if k == "review" else "")
                         for k in fields)
        vals = {k: (("true" if v else "false") if k == "review" else v) for k, v in fields.items()}
        row = self.one(f"update memories set {sets}, updated_at = now(), last_seen = now() "
                       f"where id = :'id'::bigint returning {_COLS}", id=int(mid), **vals)
        self._log(mid, by, "edit", before, row)
        return row

    def bump(self, mid: int, *, by: str = "hermes", note: str = "") -> dict:
        """The same mistake/rule came up again: count it and climb severity at the thresholds."""
        before = self.one(f"select {_COLS} from memories where id = :'id'::bigint", id=int(mid))
        if not before:
            raise Invalid(f"No memory #{mid}")
        repeats = before["repeats"] + 1
        severity = min(5, before["severity"] + (1 if repeats in ESCALATE_AT else 0))
        row = self.one(f"update memories set repeats = :'r'::int, severity = :'s'::smallint, last_seen = now(), "
                       f"updated_at = now(), status = 'active' where id = :'id'::bigint returning {_COLS}",
                       id=int(mid), r=repeats, s=severity)
        self._log(mid, by, "bump" + (f": {note[:200]}" if note else ""), before, row)
        return row

    def link(self, src: int, dst: int, rel: str, *, by: str = "hermes") -> dict:
        if rel not in RELS:
            raise Invalid(f"rel must be one of {', '.join(RELS)}")
        if int(src) == int(dst):
            raise Invalid("A memory cannot link to itself")
        self.one("insert into links (src, dst, rel) values (:'s'::bigint, :'d'::bigint, :'r') "
                 "on conflict do nothing returning src", s=int(src), d=int(dst), r=rel)
        if rel == "supersedes":
            self.update(int(dst), by=by, status="superseded")
        self._log(int(src), by, f"link {rel} #{dst}", None, None)
        return {"src": int(src), "dst": int(dst), "rel": rel}

    def unlink(self, src: int, dst: int, rel: str, *, by: str = "phone") -> None:
        self.rows("delete from links where src = :'s'::bigint and dst = :'d'::bigint and rel = :'r' returning src",
                  s=int(src), d=int(dst), r=rel)
        self._log(int(src), by, f"unlink {rel} #{dst}", None, None)

    def merge(self, keep: int, drop: int, *, text: str | None = None, by: str = "phone") -> dict:
        """Fold [drop] into [keep]: counts add up, severity takes the higher, [drop] is superseded."""
        a, b = self.get(keep), self.get(drop)
        if not a or not b:
            raise Invalid("Both memories must exist")
        if keep == drop:
            raise Invalid("Pick two different memories")
        kws = " ".join(dict.fromkeys((a["keywords"] + " " + b["keywords"]).split()))[:MAX_KEYWORDS]
        self.update(keep, by=by, text=text or a["text"], keywords=kws, severity=max(a["severity"], b["severity"]))
        self.one("update memories set hits = hits + :'h'::int, repeats = repeats + :'r'::int where id = :'id'::bigint "
                 "returning id", h=b["hits"], r=b["repeats"], id=int(keep))
        self.link(keep, drop, "supersedes", by=by)
        return self.get(keep)

    def delete(self, mid: int) -> None:
        self.rows("delete from memories where id = :'id'::bigint returning id", id=int(mid))

    def _log(self, mid: int, by: str, action: str, before, after) -> None:
        strip = lambda r: None if r is None else {k: v for k, v in r.items() if k in  # noqa: E731
                                                  ("text", "kind", "severity", "scope", "keywords", "status", "review",
                                                   "repeats")}
        self.rows("insert into history (memory_id, by, action, before, after) values "
                  "(:'id'::bigint, :'by', :'act', nullif(:'b', '')::jsonb, nullif(:'a', '')::jsonb) returning id",
                  id=int(mid), by=by[:40], act=action[:240],
                  b=json.dumps(strip(before)) if before else "", a=json.dumps(strip(after)) if after else "")

    # ------------------------------------------------------------------ browse

    def list(self, *, kind: str = "", scope: str = "", status: str = "active", q: str = "", review: bool | None = None,
             min_severity: int = 1, sort: str = "severity", limit: int = 100, offset: int = 0) -> dict:
        order = {"severity": "severity desc, repeats desc, updated_at desc",
                 "newest": "created_at desc",
                 "hits": "hits desc, severity desc",
                 "repeats": "repeats desc, severity desc",
                 "unused": "hits asc, created_at asc"}.get(sort)
        if order is None:
            raise Invalid("sort must be severity, newest, hits, repeats or unused")
        where = ("(:'kind' = '' or kind = :'kind') and (:'scope' = '' or scope = :'scope') "
                 "and (:'status' = '' or status = :'status') and severity >= :'minsev'::int "
                 "and (:'rev' = '' or review = (:'rev' = 'true')) "
                 "and (:'q' = '' or (text || ' ' || keywords) ilike '%' || :'q' || '%')")
        vars = dict(kind=kind, scope=scope, status=status, minsev=int(min_severity), q=q.strip(),
                    rev="" if review is None else ("true" if review else "false"))
        items = self.rows(f"select {_COLS} from memories where {where} order by {order} "
                          f"limit :'lim'::int offset :'off'::int",
                          lim=max(1, min(int(limit), 500)), off=max(0, int(offset)), **vars)
        total = self.one(f"select count(*) as n from memories where {where}", **vars)["n"]
        return {"items": items, "total": total}

    def stats(self) -> dict:
        by = self.rows("select kind, status, count(*) as n from memories group by kind, status")
        top = self.rows(f"select {_COLS} from memories where status = 'active' and repeats > 0 "
                        "order by repeats desc, severity desc limit 10")
        unused = self.one("select count(*) as n from memories where status = 'active' and hits = 0 "
                          "and created_at < now() - interval '30 days'")["n"]
        review = self.one("select count(*) as n from memories where review and status = 'active'")["n"]
        scopes = self.rows("select scope, count(*) as n from memories where status = 'active' group by scope "
                           "order by n desc")
        return {"by_kind": by, "most_repeated": top, "unused_30d": unused, "needs_review": review, "scopes": scopes}

    # ------------------------------------------------------------------ recall

    def recall(self, query: str, *, scopes: list[str] = (), limit: int = 8, mark: bool = True,
               explain: bool = False) -> dict:
        """Pinned (severity >= 4 in scope, always) + relevant (matched, ranked) memories for a prompt.

        score = match x scope weight x severity weight x freshness, where match is full-text rank
        (OR of the prompt's words, prefix-matched) plus trigram similarity on text + keywords.
        """
        scopes = [s for s in scopes if s and s != "global" and SCOPE_RE.match(s) and "," not in s]
        vars = dict(q=(query or "")[:2000], tsq=tsquery(query), scopes=",".join(scopes))
        in_scope = ("(scope = 'global' or scope = any(string_to_array(nullif(:'scopes', ''), ',')) "
                    "or (scope like 'client:%' and position(lower(substr(scope, 8)) in lower(:'q')) > 0))")
        pinned = self.rows(
            f"select {_COLS} from memories where status = 'active' and severity >= {PINNED_MIN_SEVERITY} "
            f"and kind in ('mistake', 'rule', 'preference', 'workflow') and {in_scope} "
            f"order by severity desc, (scope <> 'global') desc, repeats desc, id limit {PINNED_MAX}", **vars)
        relevant = []
        if vars["tsq"] or vars["q"].strip():
            relevant = self.rows(
                f"""select * from (
                  select {_COLS},
                    (case when :'tsq' = '' then 0 else ts_rank(search, to_tsquery('simple', :'tsq')) end) as ft,
                    similarity(text || ' ' || keywords, :'q') as sim,
                    case when scope = 'global' then 1.0 else 1.5 end as scope_w,
                    0.6 + 0.2 * severity as sev_w,
                    1.0 / (1 + extract(epoch from now() - coalesce(last_seen, created_at)) / 86400 / 90) as fresh
                  from memories where status = 'active' and {in_scope}
                    and ((:'tsq' <> '' and search @@ to_tsquery('simple', :'tsq'))
                         or similarity(text || ' ' || keywords, :'q') > 0.12)
                ) s order by (ft * 2 + sim) * scope_w * sev_w * fresh desc limit :'lim'::int""",
                lim=max(1, min(int(limit), 30)), **vars)
            pinned_ids = {p["id"] for p in pinned}
            relevant = [r for r in relevant if r["id"] not in pinned_ids]
            for r in relevant:
                r["score"] = round((r["ft"] * 2 + r["sim"]) * r["scope_w"] * r["sev_w"] * r["fresh"], 4)
                if not explain:
                    for k in ("ft", "sim", "scope_w", "sev_w", "fresh"):
                        r.pop(k, None)
        if mark and (pinned or relevant):
            ids = ",".join(str(r["id"]) for r in pinned + relevant)
            self.rows("update memories set hits = hits + 1, last_recalled = now() "
                      "where id = any(string_to_array(:'ids', ',')::bigint[]) returning id", ids=ids)
        return {"pinned": pinned, "relevant": relevant}

    @staticmethod
    def format_recall(result: dict, project_names: dict[str, str] | None = None) -> str:
        names = project_names or {}

        def line(m: dict) -> str:
            scope = m["scope"]
            if scope.startswith("project:"):
                scope = "project " + names.get(scope[8:], scope[8:])
            return f"- #{m['id']} [{m['kind']}, severity {m['severity']}, {scope}] {m['text']}"

        out, used = [], 0
        if result.get("pinned"):
            out.append("Always apply (critical, from past mistakes and rules):")
            for m in result["pinned"]:
                s = line(m)
                if used + len(s) > PINNED_MAX_CHARS:
                    break
                out.append(s)
                used += len(s)
        if result.get("relevant"):
            out.append("Relevant to this message:")
            out += [line(m) for m in result["relevant"]]
        if not out:
            return ""
        return ("## Memory Library\n" + "\n".join(out) +
                "\n(Bump a memory with library_bump when its mistake happens again; fix wrong ones with library_update.)")

    # ------------------------------------------------------------------ graph

    def graph(self, *, scope: str = "", kinds: list[str] = (), min_severity: int = 1, chats: bool = False,
              limit: int = 400) -> dict:
        kinds = [k for k in kinds if k in KINDS]
        mems = self.rows(
            f"select {_COLS} from memories where status = 'active' and severity >= :'ms'::int "
            "and (:'scope' = '' or scope = :'scope' or scope = 'global') "
            "and (:'kinds' = '' or kind = any(string_to_array(:'kinds', ','))) "
            "order by severity desc, repeats desc limit :'lim'::int",
            ms=int(min_severity), scope=scope, kinds=",".join(kinds), lim=max(1, min(int(limit), 1000)))
        ids = {m["id"] for m in mems}
        links = [l for l in self.rows("select src, dst, rel from links") if l["src"] in ids and l["dst"] in ids]
        nodes = [{"id": f"m{m['id']}", "type": "memory", "memory": m["id"], "kind": m["kind"],
                  "severity": m["severity"], "label": m["text"][:90], "repeats": m["repeats"]} for m in mems]
        edges = [{"from": f"m{l['src']}", "to": f"m{l['dst']}", "rel": l["rel"]} for l in links]
        scopes = {}
        for m in mems:
            sid = "global" if m["scope"] == "global" else m["scope"]
            scopes.setdefault(sid, 0)
            scopes[sid] += 1
            edges.append({"from": f"m{m['id']}", "to": f"s:{sid}", "rel": "applies_to"})
        for sid, n in scopes.items():
            typ = "global" if sid == "global" else sid.split(":", 1)[0]
            nodes.append({"id": f"s:{sid}", "type": typ, "label": sid.split(":", 1)[-1] if typ != "global" else "Everywhere",
                          "scope": sid, "count": n})
        if chats:
            sessions = {}
            for m in mems:
                if m.get("source_session"):
                    sessions.setdefault(m["source_session"], []).append(m["id"])
            for sid, mids in sessions.items():
                nodes.append({"id": f"c:{sid}", "type": "chat", "session": sid, "label": sid})
                edges += [{"from": f"m{mid}", "to": f"c:{sid}", "rel": "learned_in"} for mid in mids]
        return {"nodes": nodes, "edges": edges}

    # ------------------------------------------------------------------ doctor helpers

    def health(self) -> dict:
        dups = self.rows(
            "select a.id as a, b.id as b, similarity(a.text, b.text) as sim from memories a join memories b "
            "on a.id < b.id and a.scope = b.scope and a.status = 'active' and b.status = 'active' "
            "and a.text % b.text where similarity(a.text, b.text) > 0.8 limit 20")
        orphans = self.one("select count(*) as n from history h left join memories m on m.id = h.memory_id "
                           "where m.id is null")["n"]
        no_kw = self.one("select count(*) as n from memories where status = 'active' and keywords = ''")["n"]
        pinned = self.rows(f"select scope, count(*) as n, sum(length(text)) as chars from memories where status = 'active' "
                           f"and severity >= {PINNED_MIN_SEVERITY} group by scope")
        return {"duplicates": dups, "orphan_history": orphans, "missing_keywords": no_kw, "pinned": pinned,
                "schema_version": self.schema_version(), "total": self.one("select count(*) as n from memories")["n"]}


# ---------------------------------------------------------------------- server lifecycle

UNIT = "hermes-remote-library"


def unit_name(paths: Paths) -> str:
    """The server's systemd unit, unique per data folder, so a test install never touches the real one."""
    import hashlib

    return f"{UNIT}-{hashlib.sha256(str(paths.data.resolve()).encode()).hexdigest()[:10]}"


def socket_file(paths: Paths) -> Path:
    return paths.run / f".s.PGSQL.{PORT}"


def is_installed(paths: Paths) -> bool:
    return (paths.install / "bin" / "postgres").exists() and (paths.data / "PG_VERSION").exists()


def is_running(paths: Paths) -> bool:
    if not socket_file(paths).exists():
        return False
    r = subprocess.run([str(paths.install / "bin" / "pg_isready"), "-q", "-h", str(paths.run), "-p", str(PORT)],
                       env=pg_env(paths), capture_output=True, timeout=10)
    return r.returncode == 0


def server_argv(paths: Paths) -> list[str]:
    return [str(paths.install / "bin" / "postgres"), "-D", str(paths.data), "-k", str(paths.run), "-p", str(PORT),
            "-c", "listen_addresses=", "-c", "shared_buffers=32MB", "-c", "max_connections=40",
            "-c", "logging_collector=off"]


def ensure_running(paths: Paths, wait: float = 20) -> None:
    """Start the Library's Postgres when it is down. Under systemd it runs as its own user unit, so
    restarting the bridge or Hermes never takes it down; elsewhere it is a detached process."""
    import shutil
    import time

    if is_running(paths):
        return
    if not is_installed(paths):
        raise StoreError("The Memory Library runtime is not installed.")
    paths.run.mkdir(parents=True, exist_ok=True, mode=0o700)
    env = pg_env(paths)
    env.pop("PGPASSWORD", None)
    if os.name != "nt" and shutil.which("systemd-run") and Path("/run/user", str(os.getuid()), "systemd").exists():
        unit = unit_name(paths)
        subprocess.run(["systemctl", "--user", "reset-failed", f"{unit}.service"], capture_output=True, timeout=10)
        subprocess.run(["systemd-run", "--user", "--quiet", "--collect", f"--unit={unit}",
                        "--description=Hermes Remote Memory Library (Postgres)", "-p", "Restart=on-failure",
                        "-E", f"LD_LIBRARY_PATH={env['LD_LIBRARY_PATH']}", *server_argv(paths)],
                       capture_output=True, text=True, timeout=20)
    else:
        with open(paths.log, "ab") as log:
            kw = {"creationflags": 0x00000008 | 0x00000200} if os.name == "nt" else {"start_new_session": True}
            subprocess.Popen(server_argv(paths), stdout=log, stderr=log, stdin=subprocess.DEVNULL, env=env, **kw)
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        if is_running(paths):
            return
        time.sleep(0.2)
    raise StoreError("The Memory Library database did not start; see `hermes-remote-bridge library doctor`.")


def stop(paths: Paths) -> None:
    import shutil

    if os.name != "nt" and shutil.which("systemctl"):
        subprocess.run(["systemctl", "--user", "stop", f"{unit_name(paths)}.service"], capture_output=True, timeout=30)
    if is_running(paths):
        env = pg_env(paths)
        subprocess.run([str(paths.install / "bin" / "pg_ctl"), "-D", str(paths.data), "stop", "-m", "fast", "-w"],
                       env=env, capture_output=True, timeout=60)


# ---------------------------------------------------------------------- Hermes-side helpers

def project_for_session(hermes_home: str | Path, session_id: str) -> tuple[str | None, str | None]:
    """(project id, project name) whose folder holds the session's working directory, or (None, None)."""
    home = Path(hermes_home)
    cwd = None
    try:
        with sqlite3.connect(f"file:{home / 'state.db'}?mode=ro", uri=True, timeout=2) as c:
            r = c.execute("select cwd from sessions where id = ?", (session_id,)).fetchone()
            cwd = r[0] if r else None
    except sqlite3.Error:
        pass
    cwd = cwd or os.environ.get("TERMINAL_CWD") or None
    if not cwd:
        return None, None
    best = (None, None, -1)
    for pid, name, path in projects(home):
        if path and (cwd == path or cwd.startswith(path.rstrip("/") + "/")) and len(path) > best[2]:
            best = (pid, name, len(path))
    return best[0], best[1]


def projects(hermes_home: str | Path) -> list[tuple[str, str, str | None]]:
    try:
        with sqlite3.connect(f"file:{Path(hermes_home) / 'projects.db'}?mode=ro", uri=True, timeout=2) as c:
            return [tuple(r) for r in c.execute("select id, name, primary_path from projects where archived = 0")]
    except sqlite3.Error:
        return []


def session_titles(hermes_home: str | Path, ids: list[str]) -> dict[str, str]:
    """Chat titles for the knowledge map's optional chat layer."""
    if not ids:
        return {}
    try:
        with sqlite3.connect(f"file:{Path(hermes_home) / 'state.db'}?mode=ro", uri=True, timeout=2) as c:
            q = ",".join("?" * len(ids[:200]))
            return {r[0]: r[1] or "" for r in c.execute(f"select id, title from sessions where id in ({q})", ids[:200])}
    except sqlite3.Error:
        return {}
