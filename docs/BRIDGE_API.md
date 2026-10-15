# Bridge API contract (v1)

This is the contract the Android client codes against. Since 1.0 the phone is another window of the
same Hermes backend the desktop app uses (`hermes serve`): the bridge authenticates the phone and
relays one WebSocket to that backend's own JSON-RPC socket. A few REST endpoints remain for the
session list and settings. `/v1/me` and `/v1/status` report `protocol: 2`.

Old app versions (0.x) spoke a different API (`/v1/runs`, SSE, `/sync`) and do not work with a 1.x
bridge. Update the app from its System tab.

## Transport and auth

The bridge answers on up to three kinds of address, all on the same port (default `8650`):

- **Loopback** (`127.0.0.1`): plain HTTP, for local tools. No network checks.
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
| GET | `/v1/me` | `{device{id,name,created_at}, peer_node, via, port, addresses{lan[],tailnet[]}, lan_scheme, network, bridge_version, protocol}`. `via` is `lan` or `tailnet`; `addresses` lists every address served right now so the app can switch; `network` is `{id, name, interface, trusted}` or null. |
| GET | `/v1/status` | `components.{bridge, hermes, tailscale}`. `hermes` is `{status, version, port, shared_with_desktop}`, or `{status: "down", error, starts_on_connect}`. |
| GET | `/v1/profiles` | `{profiles[]}`, each `{name, display_name, description, model, provider, is_default}`. Only profiles the phone may use. |
| GET | `/v1/sessions?limit&offset` *(profile)* | The desktop sidebar's own query (recent first, archived and cron excluded): `{sessions[], total, limit, offset}`. Rows carry `id, title, preview, source, message_count, last_active, model, pinned`. |
| PATCH | `/v1/sessions/{id}` `{title?, pinned?, archived?}` *(profile)* | Rename and/or set the desktop-sidebar flags. At least one field required, else 400 `empty_patch`. |
| GET | `/v1/settings/approvals` *(profile)* | `{mode, modes}`. The profile's `approvals.mode`: `manual`, `smart` or `off`. |
| PUT | `/v1/settings/approvals` `{mode}` *(profile)* | Sets it via `hermes [-p <profile>] config set` (fixed argv, no shell), reads it back, audits the change. 400 `invalid_mode` for anything else. |
| GET | `/v1/settings/model` *(profile)* | `{model, provider}`: the profile's default for new chats. |
| PUT | `/v1/settings/model` `{model, provider?, confirm_expensive_model?}` *(profile)* | Sets that default. May answer `{confirm_required, confirm_message}` first. |
| GET | `/v1/cron/jobs` *(profile)* | `{jobs[]}`, each `{id, name, schedule_display, state, enabled, next_run_at, last_run_at, last_status, last_error, failure_streak, model}`. `state` is Hermes' `scheduled`, `paused`, `completed` or `error`. A job's prompt, script, delivery target and folder are not sent. |
| GET | `/v1/cron/jobs/{id}/runs?limit` *(profile)* | `{runs[]}`, newest first, each `{id, title, preview, source, started_at, ended_at, message_count, model}`. `limit` 1–50, default 10. |
| POST | `/v1/cron/jobs/{id}/pause\|resume\|trigger` *(profile)* | Pause, resume, or run now (`trigger`). Answers `{job}` as it is afterwards. Audited. A job that is not in that profile is 404 `job_not_found`. Creating, editing and deleting jobs is not offered. |

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
   `subagent_id, goal, model, status, tool_count, last_tool, depth, started_at`) and recently failed
   background delegations. `subagent.interrupt {session_id, subagent_id}` stops one (`{found}`); the
   chat's own turn goes on. Hermes answers both only for sessions this socket is attached to.
   Steering a child (`subagent.steer`) and reading its transcript (`subagent.tail`) stay on the PC.

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

## Known limitations

- **Smart approval mostly decides on its own.** With `approvals.mode: smart`, an approval request
  only appears when the smart assessor is uncertain; clear-cut safe commands run automatically
  and clearly dangerous ones are refused. Use `manual` to be asked every time.
- **Deleted sessions are gone for good.** Hermes has no undo for `session.delete`.
- **The PC must be on with the bridge running.** There is no relay in the cloud.
- **Secret and sudo prompts are answered on the PC.** The phone is told one is waiting but is never
  asked to type a password.
