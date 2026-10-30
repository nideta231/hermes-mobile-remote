# Bridge API contract (v1)

This is the contract the Android client codes against. Since 1.0 the phone is another window of the
same Hermes backend the desktop app uses (`hermes serve`): the bridge authenticates the phone and
relays one WebSocket to that backend's own JSON-RPC socket. A few REST endpoints remain for the
session list and settings. `/v1/me` and `/v1/status` report `protocol: 2`.

The `/v1/local/*` routes (see [Local control API](#local-control-api-desktop-app)) are for the desktop app on the same PC, not for the phone.

Old app versions (0.x) spoke a different API (`/v1/runs`, SSE, `/sync`) and do not work with a 1.x
bridge. Update the app from its System tab.

## Transport and auth

The bridge answers on up to three kinds of address, all on the same port (default `8650`):

- **Loopback** (`127.0.0.1`): plain HTTP, for local tools. The phone's routes make no network checks here. `/v1/local/*` is loopback-only and also needs the local secret.
- **Tailscale** (e.g. `http://100.64.0.10:8650`): plain HTTP. The traffic travels inside Tailscale's
  WireGuard tunnel, which encrypts and authenticates both ends. Android needs a
  `network_security_config` cleartext exception scoped to those addresses only.
- **Local network** (e.g. `https://192.168.1.10:8650`): only when `lan = true` in `config.toml` and the
  PC is on a network marked trusted (`hermes-remote-bridge trust`). HTTPS only, with a self-signed
  certificate the app pins (its SHA-256 travels in the pairing code).

The bridge refuses to bind `0.0.0.0` / `::`. It watches the network and restarts its listeners when the
set of addresses it should serve changes (new Wi-Fi, DHCP lease, Tailscale up/down, trust change).

Every non-loopback request, and every WebSocket upgrade, has to pass these checks:

1. The source address is allowed:
   - a tailnet address (`100.64.0.0/10` or `fd7a:115c:a1e0::/48`) whose Tailscale `whois` login is
     allowed (default: the owner of the PC's node). Otherwise `403 forbidden_peer`.
   - or, with LAN enabled, a private address (RFC 1918, link-local, ULA) reaching the HTTPS listener.
   - anything else: `403 forbidden_network`.
2. The request carries `Authorization: Bearer <device token>`, a per-device token that hasn't been
   revoked. Otherwise `401 unauthorized`. On the WebSocket the token travels in the upgrade
   request's header, never in the URL.

- REST errors always come back as `{"error": {"code", "message", ...}}`.
- Limits: 240 REST requests/min per device; request bodies up to 1 MB (chunked bodies are refused
  with 411). On the socket: 600 frames/min from the phone and 16 MB per frame.

## Pairing

On the PC, run `hermes-remote-bridge pair phone`. It prints a QR code containing:

`hermesremote://pair?v=2&url=https://192.168.1.10:8650&device=phone&token=hrb_…&pin=<cert-sha256>&alt=http://100.64.0.10:8650`

- `url` is the preferred address: a trusted-LAN HTTPS address first, otherwise a Tailscale address.
- `alt` (optional) is a comma-separated list of the other addresses, in order of preference.
- `pin` is `base64url(SHA-256(DER certificate))`, unpadded. The app must see this certificate on an
  HTTPS address before it sends the token there.
- The token is shown once. Only its SHA-256 hash is stored, in `~/.config/hermes-remote/devices.json`.
- On Android, store the token in EncryptedSharedPreferences / Keystore.
- To revoke a device: `hermes-remote-bridge revoke phone`. It takes effect on the device's next
  request or connection; no restart needed.
- The desktop app's *Pair a device* does the same and shows the QR code inline, with a Copy button.

On a trusted network the bridge also advertises `_hermesremote._tcp` over mDNS (Avahi). The TXT record
carries `pin=` (the first 12 characters of the pin, enough to recognise the bridge; the full pin is
still checked on connect), `ip=` (the addresses actually served) and `v=2`.

## Which Hermes backend

The bridge attaches to the backend the desktop app is already using. It reads Hermes' spawn ledger
(`~/.hermes/spawn-ledger.json`), takes the newest live `serve`/`dashboard` entry for the default
profile, and reads that backend's session token from the page it serves its own renderer. When no
backend is running and `start_hermes = true` (the default), the bridge starts `hermes serve` itself
on loopback; a desktop opened later attaches to that one through the same ledger.

## REST endpoints

Every endpoint marked *(profile)* takes an optional `?profile=<name>`: the Hermes profile it is
for (see [Profiles](#profiles)). Without it, or with `default`, it is the default profile.

| Method | Path | Notes |
|---|---|---|
| GET | `/healthz` | No auth. Liveness only. |
| GET | `/metrics?format` | Counts and timings, Prometheus text or JSON. See [Metrics](#metrics-get-metrics). |
| GET | `/v1/me` | `{device{id,name,created_at}, peer_node, via, port, addresses{lan[],tailnet[]}, lan_scheme, network, bridge_version, protocol}`. `via` is `lan` or `tailnet`; `addresses` lists every address served right now so the app can switch; `network` is `{id, name, interface, trusted}` or null. |
| GET | `/v1/status` | `components.{bridge, hermes, tailscale}`. `hermes` is `{status, version, port, shared_with_desktop}`, or `{status: "down", error, starts_on_connect}`. |
| GET | `/v1/profiles` | `{profiles[]}`, each `{name, display_name, description, model, provider, is_default}`. Only profiles the phone may use. |
| GET | `/v1/sessions?limit&offset` *(profile)* | The desktop sidebar's own query (recent first, archived and cron excluded): `{sessions[], total, limit, offset}`. Rows carry `id, title, preview, source, message_count, last_active, model, pinned`. |
| GET | `/v1/sessions/search?q&limit` *(profile)* | Chats whose messages (or id) contain `q`: Hermes' own `/api/sessions/search` (the desktop sidebar's FTS5 search, prefix-matched, one row per conversation across compressions, best match first). `{sessions[]}`, rows shaped like `/v1/sessions` plus `snippet` (the matching text on one line, at most 160 characters, highlight marks removed). Archived chats and cron runs are left out. `q` is 1..200 characters after trimming, else 400 `invalid_query`; `limit` defaults to 20, capped at 50. Bridges before this route answer 404. |
| PATCH | `/v1/sessions/{id}` `{title?, pinned?, archived?}` *(profile)* | Rename and/or set the desktop-sidebar flags. At least one field required, else 400 `empty_patch`. |
| GET | `/v1/settings/approvals` *(profile)* | `{mode, modes}`. The profile's `approvals.mode`: `manual`, `smart` or `off`. |
| PUT | `/v1/settings/approvals` `{mode}` *(profile)* | Sets it via `hermes [-p <profile>] config set` (fixed argv, no shell), reads it back, audits the change. 400 `invalid_mode` for anything else. |
| GET | `/v1/settings/model` *(profile)* | `{model, provider}`: the profile's default for new chats. |
| PUT | `/v1/settings/model` `{model, provider?, confirm_expensive_model?}` *(profile)* | Sets that default. May answer `{confirm_required, confirm_message}` first. |
| GET | `/v1/cron/jobs` *(profile)* | `{jobs[]}`, each `{id, name, schedule_display, state, enabled, next_run_at, last_run_at, last_status, last_error, failure_streak, model}`. `state` is Hermes' `scheduled`, `paused`, `completed` or `error`. A job's prompt, script, delivery target and folder are not sent. |
| GET | `/v1/cron/jobs/{id}/runs?limit` *(profile)* | `{runs[]}`, newest first, each `{id, title, preview, source, started_at, ended_at, message_count, model}`. `limit` 1–50, default 10. |
| POST | `/v1/cron/jobs/{id}/pause\|resume\|trigger` *(profile)* | Pause, resume, or run now (`trigger`). Answers `{job}` as it is afterwards. Audited. A job that is not in that profile is 404 `job_not_found`. Creating, editing and deleting jobs is not offered. |
| GET | `/v1/monitor/now?live` | `{host, stats, core_pct[], top[], claude, alerts[]}`. `stats` is `{cpu, mem, swap, temp, disk, load1, rx, tx, rd, wr, mem_used_gb, mem_total_gb, swap_used_gb, disk_used_gb, disk_total_gb, uptime_s, cores}` (null where the OS doesn't say; `temp` is Linux only). `live=true` samples now instead of returning the last 10 s sample. 404 `monitor_off` when `monitor = false`. |
| GET | `/v1/monitor/history?range` | `range` is `1h,6h,24h,7d,30d,1y`. `{range, start, end, bucket, ts[], <metric>[], <metric>_max[]}`, at most 360 points. Raw samples are kept 48 h, 1-minute averages 30 days, 15-minute averages a year (SQLite in the state dir). |
| GET | `/v1/monitor/processes?sort&limit&q` | `sort` is `cpu`, `mem`, `name` or `pid`; `q` filters by name, command line or PID. `{total, sort, processes[]}`, each `{pid, name, user, cpu, mem_mb, status, cmd}`. |
| POST | `/v1/monitor/processes/{pid}/stop?force` | Terminate (or with `force=true`, kill) a process the bridge's user owns, waiting up to 3 s. `{pid, name, stopped}`. PID ≤ 1 and the bridge itself are 403 `forbidden_process`; another user's process is 403; gone is 404. Audited. |
| GET | `/v1/claude/usage?refresh` | Claude plan usage read with Claude Code's own login on this PC: `{ok, plan, session, weekly, session_resets_at, weekly_resets_at, limits[{label, percent, resets_at, active}], ts}` or `{ok: false, error}`. Polled every 5 min; `refresh=true` asks now. The token is only read, never refreshed (that would sign Claude Code out). |
| GET | `/v1/claude/history?range` | `{ts[], session[], weekly[]}` for the range. |

`/v1/me` also lists `features`: any of `monitor`, `claude_usage`, `terminal`, so the app shows only
the pages this bridge serves.

## Metrics: `GET /metrics`

What the bridge has done since it started, in Prometheus text format 0.0.4
(`text/plain; version=0.0.4`); `?format=json` is the same data as JSON, which
`hermes-remote-bridge metrics [--json]` reads. In memory only: every value resets when the bridge
restarts. Off with `metrics = false` in config.toml (then 404 `metrics_off`, after auth).

**Auth.** The same network gate as every other endpoint, then a bearer token, even on loopback:
loopback is any local user on a shared machine, and the metrics say when someone is using the
phone. Either token works:

- the **metrics token**, `~/.config/hermes-remote/metrics.token` (mode 600; `serve` creates it once
  and keeps it). It opens `/metrics` and nothing else, so it is the one to give a scraper;
- a paired device's token. Works, but a device token can drive Hermes: don't put one in a
  Prometheus config.

**Where people see it.** The app's PC page has a *Bridge* card (the phone reads `?format=json`
with its own device token every ~15 s while the page is open); the Linux tray shows an *Activity*
line and *Bridge stats...* (the `metrics` CLI). Both hide themselves on a bridge without metrics.

**What is never in it.** Labels come from closed sets only: route templates
(`/v1/sessions/{session_id}`, never the path or query), allowlisted RPC method names, fixed
reasons and outcomes. No session ids or titles, prompts, message text, file paths, device names or
tokens. Paths no route serves count as `unmatched`, requests refused before routing (network,
body size) as `refused`.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `bridge_info` | gauge | `version`, `protocol` | Always 1. `version` (also `bridge_version` in `/v1/me` and `/v1/status`) is the release tag the bridge runs, without the `v` (`dev` outside a release checkout). |
| `process_start_time_seconds` | gauge | | Bridge start, Unix time. |
| `bridge_paired_devices` | gauge | | Paired devices not revoked. |
| `bridge_live_devices` | gauge | | Distinct devices with a live socket open now. |
| `bridge_http_requests_total` | counter | `route`, `method`, `status` | REST requests answered. |
| `bridge_http_request_seconds` | histogram | `route` | REST request duration. |
| `bridge_ws_connections` | gauge | `endpoint` (`live`, `terminal`) | Open sockets. |
| `bridge_ws_connections_total` | counter | `endpoint` | Sockets accepted. |
| `bridge_ws_rejected_total` | counter | `endpoint`, `reason` (`network`, `unauthorized`, `hermes_unavailable`, `disabled`) | Upgrades refused. |
| `bridge_relay_frames_forwarded_total` | counter | `method` (allowlisted, or `response` for an answer to a question) | Phone→Hermes frames forwarded. |
| `bridge_relay_frames_refused_total` | counter | `reason` (`method`, `profile`, `config_key`, `too_large`, `not_json`, `not_jsonrpc`, `stray_response`, `rate_limited`) | Phone→Hermes frames refused. |
| `bridge_relay_frames_from_hermes_total` | counter | | Hermes→phone frames relayed. |
| `bridge_hermes_rpc_seconds` | histogram | `method` | Phone RPC round trip: forwarded to its response relayed. |
| `bridge_hermes_rpc_errors_total` | counter | `method` | RPCs Hermes answered with an error. |
| `bridge_questions_total` | counter | `method` (`approval`, `clarify`, `sudo`, `secret`, `vault.*`) | Questions Hermes asked on a live socket. |
| `bridge_questions_open` | gauge | | Seen and not yet settled. |
| `bridge_questions_ended_total` | counter | `method`, `outcome` (`answered`, `declined` from a phone; `resolved`, `timeout`, `interrupted`, `shutdown`, `other` from Hermes' `request.cancel`; `unobserved`) | Questions settled. |
| `bridge_question_wait_seconds` | histogram | `method` | Seen to first settlement (any screen). Buckets 1 s to 1 h. |
| `bridge_turns_started_total` | counter | | `message.start` events. |
| `bridge_turns_completed_total` | counter | `status` (`complete`, `interrupted`, `error`, `other`) | `message.complete` events. |

Latency buckets are 5 ms to 30 s. Relay figures come from what crosses a phone's socket: questions
and turns are only counted while at least one phone is connected, an event two phones both receive
counts once, and a question still open when the last socket that saw it closes ends as
`unobserved` (not timed).

Scraping from Prometheus on the same PC:

```yaml
scrape_configs:
  - job_name: hermes-remote-bridge
    metrics_path: /metrics
    static_configs:
      - targets: ["127.0.0.1:8650"]
    authorization:
      type: Bearer
      credentials_file: /home/you/.config/hermes-remote/metrics.token
    # older Prometheus: bearer_token_file: /home/you/.config/hermes-remote/metrics.token
```

From another machine, scrape the bridge's Tailscale address (the Tailscale identity check still
applies, so the scraper's node must be an allowed login) with a copy of the metrics token.

## Terminal: `GET /v1/terminal?cols&rows` (WebSocket)

A shell on the PC as the bridge's user (`$SHELL -l` on Linux, see below for Windows), one per connection,
killed when it closes. Same gate as the live socket: tailnet or pinned LAN, device token in the
upgrade request's `Authorization` header. Turn it off with `terminal = false` in config.toml
(closes with 4404).

On Windows the shell is `pwsh`, else `powershell`, else `cmd`. Set `terminal_shell` in config.toml to use another, e.g. `terminal_shell = "\"C:\\Program Files\\Git\\bin\\bash.exe\" -l"`.

The bridge runs the terminal emulator (pyte), so it answers the shell's queries (fish waits for
them) and the phone only draws rows. Frames are JSON text:

- phone → bridge: `{"type":"input","data":"ls\r"}`, `{"type":"resize","cols":80,"rows":24}`, `{"type":"interrupt"}`
- bridge → phone: `{"type":"screen","cols","rows","full","cursor":[x,y,visible],"lines":{"<row>":[[text,fg,bg,flags],…]},"scrolled":[[…]],"title"}`
  (only changed rows, at most one frame per 40 ms; `scrolled` is rows that left the top, for the
  phone's scrollback; flags: 1 bold, 2 reverse, 4 underline, 8 italic), `{"type":"exit","code"}`,
  `{"type":"error","message"}`.

On Windows the shell runs on pipes (no PTY in the standard library): line commands work,
full-screen programs don't, and Ctrl-C isn't delivered.

## Profiles

Hermes can have several profiles (`hermes profile list`), each with its own sessions, model,
skills and settings. The one `hermes serve` the desktop uses serves all of them; a call says which
one it means. So the phone can switch profiles without the bridge starting anything new:

- REST: the `?profile=` parameter above.
- Live socket: Hermes' own `profile` param, e.g. `session.create {source: "desktop", profile:
  "developer"}`, `session.resume {session_id, profile}`, `projects.tree {profile}`. The bridge
  forwards it unchanged.

The bridge checks every profile name, REST or socket, before it reaches Hermes: a malformed name is
refused (400 `invalid_profile`, or JSON-RPC `-32602`), and so is a profile left out of
`allowed_profiles` in `config.toml` (403 `profile_refused`, or `-32602`). An empty `allowed_profiles`
allows every profile. The default profile is always allowed. A well-formed name Hermes does not know
is Hermes' 404.

## The live socket: `GET /v1/ws`

A WebSocket relayed frame for frame to the backend's `/api/ws`. It speaks Hermes' gateway
protocol (JSON-RPC 2.0, the contract in Hermes' `apps/shared/src/gateway-contract.openrpc.json`), the
same one the desktop app uses. The bridge adds only:

- the auth and network checks above (close `4401` for a bad token, `4403` for a refused peer);
- a method allowlist: anything the desktop's chat view does not do is answered with JSON-RPC error
  `-32601` without reaching Hermes;
- a `profile` param is checked as described under [Profiles](#profiles);
- `config.set` limited to the keys `model`, `reasoning`, `fast` and `yolo`;
- replies to the agent's questions must carry a `srq-…` id;
- frame size and rate limits (close `1009` / error), and close `1013` when Hermes is not reachable;
- `ws_open` / `ws_close` audit entries with per-method counts, never prompts or tokens.

### What a client does

1. **Announce itself first:** `client.capabilities {server_requests: true}`. Without it Hermes
   assumes the client cannot answer approvals or clarify questions and never routes them to it.
2. **Open a chat:** `session.resume {session_id: <stored id>}` returns the runtime `session_id`, the
   transcript (`messages`), `info` (title, model, provider, reasoning effort, running), the turn in
   flight and any open questions. New chats: `session.create {source: "desktop"}` returns
   `session_id` (runtime) and `stored_session_id`.
3. **Send:** `prompt.submit {session_id, text}`. While a turn runs: `session.steer {session_id, text}`
   or `session.interrupt {session_id}`.
4. **Follow:** events arrive as notifications `{"method": "event", "params": {type, session_id, seq,
   payload}}` for every session the socket is attached to, wherever the turn was started.
5. **Answer questions:** Hermes sends requests `{"id": "srq-…", "method": "approval" | "clarify",
   "params": {...}}`. Reply with the same id: `{"id": "srq-…", "result": {"choice": "once"}}` (approval:
   `once, session, always, deny`) or `{"result": {"answer": "..."}}` (clarify). A question answered on
   another screen is withdrawn with a `request.cancel` event.
6. **Sidebar:** `session.active_list` gives what is working right now; `sessions.changed` events say
   when to re-fetch `/v1/sessions`.
7. **Subagents:** `subagent.list {session_id}` returns the chat's live children (`subagents[]`:
   `subagent_id, goal, model, status, tool_count, last_tool, depth, started_at, accepting_steer`) and
   recently failed background delegations. `subagent.interrupt {session_id, subagent_id}` stops one
   (`{found}`); the chat's own turn goes on. `subagent.tail {session_id, subagent_id}` reads the end
   of a running child's live transcript (`{available, text, truncated}`, the last 16 KB; `available`
   is false before it starts writing and once it ended). `subagent.steer {session_id, subagent_id,
   text}` queues an instruction the child reads after its current tool call (`{status: "queued" |
   "rejected"}`; queued is not a delivery receipt). The bridge refuses steer text that is not a
   non-blank string of at most 4000 characters (`-32602`). Hermes answers all four only for sessions
   this socket is attached to.
8. **Loop and heartbeat state:** `session.control.read {session_id}` returns `{control: {goal, loop, heartbeat,
   revision, updated_at}}`; `loop` and `heartbeat` are `null` when none is set, else carry `status`
   (`active | paused | done | cleared`). Read-only; the phone cannot change them. The same object arrives
   pushed as a `session.control.update` event while the socket is attached.
9. **Dropped sockets are parked while their chats have work:** when a phone's socket closes, the bridge keeps its
   Hermes socket open so Hermes does not start its 20 s reap of the chat (which would kill a background process).
   It learns the chats attached on the socket from the `session_id` in the `session.create` / `session.resume`
   results it relays (a relayed `session.close` drops one). Every 30 s while parked it asks Hermes
   (`session.active_list`, `process.list`, `subagent.list`, `session.control.read`, with ids starting `brg-`) whether
   any of them has work: a running turn, a live subagent, an active /loop or /heartbeat, or a running process.
   It keeps the socket while so, and releases it after `park_seconds` of idle (config, default 600, `0` = parking
   off) or after `park_max_seconds` (default 86400) regardless. A probe that fails, is refused or times out counts
   as work. Probe replies are consumed by the bridge and never forwarded, also to a phone that reattaches
   meanwhile. The same device connecting again gets the parked socket: the bridge sends it the stored
   `gateway.ready` first, then relays as usual, and the app should `session.resume` its open chats. Events and
   questions (approvals, clarify) Hermes emits while no phone is attached are not answered or replayed; Hermes
   re-offers questions on resume. A second live connection from the same device closes the first. Audit events:
   `ws_park`, `ws_reattach`, `ws_park_expired`, `ws_park_cap`.

### Events the app renders

| type | payload |
|---|---|
| `message.start` | A turn began. |
| `message.delta` | `text`: append to the streaming reply. |
| `reasoning.delta` / `reasoning.available` | `text`: the model's reasoning. |
| `thinking.delta` | Spinner status text. Not part of the transcript. |
| `message.interim` | `text, already_streamed`: commentary in the middle of a turn. |
| `tool.start` / `tool.complete` | `tool_id, name, context, args` / `result, duration_s`. |
| `message.complete` | `text, status` (`complete`, `interrupted`, `error`), `error?`, `warning?`. |
| `session.title` / `session.info` | Title and model changes. |
| `btw.complete` / `background.complete` | Side answers from `/btw` and `/background`. |
| `subagent.spawn_requested` / `.start` / `.tool` / `.progress` / `.thinking` / `.complete` | A child agent (`delegate_task`): `subagent_id, goal, model, depth, tool_count`; `.tool` adds `tool_name`; `.complete` adds `status, summary, duration_seconds`. Delegations run in the background: `.complete` usually arrives after the turn's `message.complete`, and Hermes then starts a turn of its own to report the result. |
| `request.cancel` | `id, reason`: an open question was answered elsewhere or withdrawn. |
| `sessions.changed` | The session list changed. Global (`session_id` is empty). |

### Slash commands, models, reasoning

- Suggestions: `complete.slash {text, session_id?}`.
- Running one: `slash.exec {session_id, command}`; on error `4018` (needs the live agent) fall back to
  `command.dispatch {session_id, name, arg}`, exactly as the desktop does. The result is output to
  show, an alias, or a message to send or prefill. `/retry`, `/undo`, `/btw`, `/background`,
  `/title`, `/compress`, `/branch`, `/status`, `/save` and skills all work, because the agent is the
  live one.
- Models: `model.options`. Switch the chat's model with `config.set {session_id, key: "model",
  value: "<model> --provider <slug> --session"}`, and its reasoning effort with `key: "reasoning"`.

## Notes

Keep-style notes, one Markdown file each in `notes_dir` (default `~/.config/hermes-remote/notes/`): a
`---` header of `key: <json>` lines (`title, color, pinned, archived, labels, created, updated`), then the
body; `- [ ]` / `- [x]` lines are checklist items. Hermes reads and edits the same files through
`hermes-remote-bridge notes ...` (the `personal-notes` skill, installed by install.sh). Listed as
`notes` in `/v1/me`.

- `GET /v1/notes?q=&archived=false&label=` → `{notes: [...], labels, colors}`, pinned first, newest next.
- `POST /v1/notes` `{title?, body?, color?, pinned?, labels?}` → the note.
- `GET|PATCH|DELETE /v1/notes/{id}`: PATCH takes any of those fields plus `archived`; last write wins.

## Blueprints

A flow diagram + task board per Hermes project (feature `blueprints`, config `blueprints`,
`blueprints_dir`, default `~/.config/hermes-remote/blueprints/<project id>.json`). Hermes edits the
same files through `hermes-remote-bridge blueprint ...` (skill `project-blueprint`).

| Method | Path | |
|---|---|---|
| GET | `/v1/blueprints` | `{blueprints: [summary], trash: [...]}` |
| GET | `/v1/blueprints/{project}?since=N` | the document; `{version, unchanged: true}` when still at N |
| POST | `/v1/blueprints/{project}` | create (`{name, path}`) |
| POST | `/v1/blueprints/{project}/ops` | `{ops: [...]}` → `{doc, applied}`; all ops land or none (400 `bad_op` with the current `doc`) |
| GET | `/v1/blueprints/{project}/file?path=` | a file a card comment attaches (`files`); 403 `not_attached` for any other path, relative paths resolve in the project folder |
| GET | `/v1/blueprints/{project}/file/info?path=` | `{path, name, exists, size, modified, mime}` of such a file |
| DELETE | `/v1/blueprints/{project}` | move to the trash → `{trashed: id}` |
| POST | `/v1/blueprints-trash/{id}/restore` | restore |
| DELETE | `/v1/blueprints-trash/{id}` | delete for good |
| POST | `/v1/blueprints/{pid}/upload?name=` | raw body (max 10 MB, refused with 413 and a message naming the file size and the limit): a file the phone attaches; stored under `blueprints_dir/files/<pid>/` with a safe, unique name. `{path, name, size}`; put `path` in a card's `files` (`card.update`) or a comment's `files` to make it servable |
| PUT | `/v1/blueprints-settings` | global board settings (`new_cards`, `aging_days`, `sticky_add`; `default` clears one) and global agent `instructions` (text; empty clears); boards without their own choice follow them. `GET /v1/blueprints` and every blueprint carry them as `defaults` and `default_instructions` |

Ops are applied to the newest copy under a file lock, so phone and Hermes edits merge:
`node.add` (`kind` start/end/feature/flow/decision/note, `label`, `after` = node it follows; placed
and connected automatically), `node.update`, `node.remove`, `edge.add`, `edge.update`, `edge.remove`,
`diagram.layout`, `column.add/update/move/remove` (`limit`: WIP cap, 0 = none), `card.add` (`column`, `title`, `body`, `tags`,
`checklist`, `priority` low/medium/high, `due` YYYY-MM-DD, `assignee` me/hermes, `node`),
`card.update` (also `agent`: who is working on it now, cleared when the card reaches the last
column), `card.check`, `card.move` (`column`, `index`), `card.remove`, `card.comment` (`text`,
`kind` comment/question/review, `files` PC paths, `resolves` = also resolve open asks; on a question: `options` up to 6
one-tap answers of max 80 chars, `input` true or a hint string = show a text answer box),
`comment.resolve` (`comment_id`, or every open ask; `resolved` false reopens; moving a card to the last column resolves its open asks), `comment.remove`,
`instructions.set` (`text`: the user's standing rules, shown first in Hermes' Markdown view),
`settings.set` (`new_cards` top/bottom: where cards added or moved into another column without an
`index` land; `aging_days` 0/3/7/14/30: fade untouched cards; `default` follows the global setting), `control.set` (`shared`/`hermes`), `log`. Summaries carry `needs_you` (cards with an open
question/review) and `working`. Cards, nodes and columns can be named by id or by a unique title. Every
change is logged with who made it (`phone`, `hermes`).

## Bridge update

`GET /v1/bridge/update[?refresh=true]` (device token): `{current, commit, latest, available, can_update,
reason, status}`. `current` is the release tag of the checkout, `latest` the newest `vX.Y.Z` tag on the
`origin` remote (one `git ls-remote`, cached 10 minutes), `reason` why it can't update itself (installed by
the desktop app, local changes, no git/systemd, offline). `status` is the last update:
`{state: running|done|failed, step, to, error}`.

`POST /v1/bridge/update` starts it and returns `{started, to}` (409 when up to date, already running or
refused). It runs as a transient systemd unit outside the bridge's cgroup: `git fetch`, fast-forward to the
tag, `uv sync` (rolled back on failure), refresh the installed unit, move any Hermes still in the bridge's
cgroup into a scope of its own, restart the bridge. Pairings, TLS keys and networks are in the config
directory and are not touched, so phones reconnect with the same pairing. The feature is listed as
`bridge_update` in `/v1/me`; `self_update = false` turns it off. CLI: `hermes-remote-bridge update`.

A bridge installed by the desktop app (a copy without `.git`) updates too, on Linux: `latest` is the newest
`vX.Y.Z` tag from the GitHub API, and the unit downloads that tag's source archive, extracts only `bridge/`
(no `tests/`, `.venv`, `__pycache__`; archives with `..` or absolute paths are refused) beside the current
copy, swaps it in keeping the old one as `bridge.old`, runs `uv sync --frozen --no-dev` into the app's
existing venv, restarts and health-checks. On any failure the old copy is restored, re-synced and restarted.
`current` comes from the `bridge.version` file written after each successful update (unknown before the
first, which counts as "update available"). Windows still says to update the desktop app.

## Parked sockets: `GET /v1/admin/parked`

Device token; off with `remote_admin = false` (404). Lists the Hermes sockets the bridge holds open for
phones that dropped: `{parked: [{device, held_seconds, sessions, work[], releases_in}]}`. `sessions` is how
many chats are attached (never their ids or content). `work` is the kind seen on the last 30 s check:
`turn`, `process`, `subagent`, `loop`, `heartbeat` or `unknown` (a probe failed); empty when idle, and then
`releases_in` is the seconds left before release (null while there is work). Restarting or updating the
bridge closes these sockets, so a running background process dies. Listed as `parked` in `/v1/me`.

## Local control API (desktop app)

The desktop app manages the bridge through these routes. They are not for the phone and are never in the relay's allowlist.

- **Who:** only a loopback peer that sends `X-Hermes-Local: <secret>`. Any other request gets `403 forbidden_local`, including a tailnet or LAN peer that has the secret.
- **Secret:** `<config_dir>/local-secret`, 32 random bytes in hex, mode 0600 on Linux, created on first start and created again if it is deleted. Deleting it revokes every local client.
- **No device token and no device rate limit** apply to these routes.
- **Bodies and answers:** the JSON shapes are the same as the CLI's `--json` output, since both call the same functions. The exact fields of each answer are not listed here (unchecked).

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/local/status` | What the app's status line shows: network trusted, firewall, LAN, tailnet. |
| GET | `/v1/local/devices` | Paired devices (`devices --json`). |
| POST | `/v1/local/pair` | Body `{name, replace?}`. Pairs a device, as `pair` does. |
| POST | `/v1/local/revoke` | Body `{name}`. Revokes that device. |
| GET | `/v1/local/trust` | Trusted networks (`trust --list --json`). |
| POST | `/v1/local/trust` | Trusts the network the PC is on (`trust`). |
| POST | `/v1/local/untrust` | Body `{id}`. Removes that trusted network. |
| GET | `/v1/local/doctor` | Doctor report (`doctor --json`). |

## Known limitations

- **Smart approval mostly decides on its own.** With `approvals.mode: smart`, an approval request
  only appears when the smart assessor is uncertain; clear-cut safe commands run automatically
  and clearly dangerous ones are refused. Use `manual` to be asked every time.
- **Deleted sessions are gone for good.** Hermes has no undo for `session.delete`.
- **The PC must be on with the bridge running.** There is no relay in the cloud.
- **Secret and sudo prompts are answered on the PC.** The phone is told one is waiting but is never
  asked to type a password.
| GET / PUT | `/v1/monitor/tracking` | which monitor groups the PC reads (`cpu`, `memory`, `temp`, `disk`, `network`, `processes`, `claude`); PUT `{"network": false}` stops reading it (saved in `monitor-tracking.json` next to the database). `stats.tracking` in `/v1/monitor/now` mirrors it |
- **Two trust prompts on Linux.** Until the bridge's own `notify-send` trust prompt is removed, a Linux desktop can show two "Trust this network?" prompts: one from the desktop app and one from the bridge.
