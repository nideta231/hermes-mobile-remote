# Bridge API contract (v1)

This is the contract the Android client codes against. Hermes' own API stays on `127.0.0.1:8642`
and is never reached directly by the phone.

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

Every non-loopback request has to pass these checks:

1. The source address is allowed:
   - a tailnet address (`100.64.0.0/10` or `fd7a:115c:a1e0::/48`) whose Tailscale `whois` login is
     allowed (default: the owner of the PC's node). Otherwise `403 forbidden_peer`.
   - or, with LAN enabled, a private address (RFC 1918, link-local, ULA) reaching the HTTPS listener.
   - anything else: `403 forbidden_network`.
2. The request carries `Authorization: Bearer <token>`, a per-device token that hasn't been revoked.
   Otherwise `401 unauthorized`.

- Errors always come back as `{"error": {"code", "message", ...}}`.
- Limits: 240 requests/min and 20 runs/min per device; request bodies up to 1 MB (chunked bodies are
  refused with 411); prompts up to 100k chars.

## Pairing

On the PC, run `hermes-remote-bridge pair phone`. It prints a QR code containing:

`hermesremote://pair?v=2&url=https://192.168.1.10:8650&device=phone&token=hrb_…&pin=<cert-sha256>&alt=http://100.64.0.10:8650`

- `url` is the preferred address: a trusted-LAN HTTPS address first, otherwise a Tailscale address.
- `alt` (optional) is a comma-separated list of the other addresses, in order of preference.
- `pin` is `base64url(SHA-256(DER certificate))`, unpadded. The app must see this certificate on an
  HTTPS address before it sends the token there.
- The token is shown once. Only its SHA-256 hash is stored, in `~/.config/hermes-remote/devices.json`.
- On Android, store the token in EncryptedSharedPreferences / Keystore.
- To revoke a device: `hermes-remote-bridge revoke phone`. It takes effect on the device's next request; no restart needed.

On a trusted network the bridge also advertises `_hermesremote._tcp` over mDNS (Avahi). The TXT record
carries `pin=` (the first 12 characters of the pin, enough to recognise the bridge; the full pin is
still checked on connect), `ip=` (the addresses actually served) and `v=2`.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/healthz` | No auth. Liveness only. |
| GET | `/v1/me` | `{device{id,name,created_at}, peer_node, via, port, addresses{lan[],tailnet[]}, lan_scheme, network, bridge_version}`. `via` is `lan` or `tailnet`; `addresses` lists every address served right now so the app can switch; `network` is `{id, name, interface, trusted}` or null. |
| GET | `/v1/status` | `components.{bridge, hermes, model, desktop, tailscale}`. Each component reports its own status, so the app can show which part is down. |
| GET | `/v1/desktop` | RDP connection info: `protocol, host, dns_name, port, username, rdp_uri`. RDP rides on the tailnet, so with Tailscale down this is 503 `desktop_unavailable` rather than a failure — Tailscale is optional, as `/v1/status` already reports. |
| GET | `/v1/sessions?limit&offset` | Passes through Hermes: `{data[], has_more}`. |
| POST | `/v1/sessions` `{title?}` | Returns 201 `{session{id,…}}`. |
| GET | `/v1/sessions/{id}` | Hermes session metadata, plus `active_run` (null or a run snapshot). |
| PATCH | `/v1/sessions/{id}` `{title?, pinned?, archived?}` | Rename and/or set the desktop-sidebar flags. At least one field required, else 400 `empty_patch`. |
| DELETE | `/v1/sessions/{id}?confirm={id}` | Without `confirm` you get 428 `confirmation_required`. With an active run you get 409. |
| GET | `/v1/sessions/{id}/messages?limit&offset` | History rows: `role, content, tool_calls, tool_name, reasoning, timestamp`. |
| GET | `/v1/sessions/{id}/sync?since=N&limit=60` | Tail a session another surface is driving. See below. |
| POST | `/v1/sessions/{id}/fork` `{title?}` | Branch the session. Returns 201. |
| GET | `/v1/models` | Selectable models. `{current{model,provider}, providers[{slug,name,current,default,models[],featured[],capabilities}], reasoning{default, levels[]}}`. `reasoning.default` is Hermes' `agent.reasoning_effort` (or `medium`). Providers that aren't authenticated are dropped, and so are the models Hermes lists in `unavailable_models`, so the picker never offers something the account can't run. |
| POST | `/v1/runs` `{session_id, input, client_request_id, model?, provider?, reasoning_effort?}` | Returns 202 with a run snapshot. `model`/`provider` override the model for this run only; omit them to keep the session's. `reasoning_effort` is one of `none, minimal, low, medium, high, xhigh, max` and is passed to Hermes as `model_options.reasoning_effort` for this run. Resending the same `client_request_id` returns the same run with `replayed:true`. If the session already has an active run you get 409 `session_busy` with `run_id`. |
| GET | `/v1/commands` | Slash commands the app may offer: `{data[{name, description, args, kind}]}`. `kind` is `app` (handled by the app itself: `new, model, reasoning, stop`), `output`, `prompt` or `skill`. Cached for 5 min. |
| POST | `/v1/sessions/{id}/command` `{command}` | Runs `/name args` in Hermes' TUI gateway against that session. Returns `{type:"output", text}` to show, or `{type:"send", message, display}`: the expanded prompt (skills, `/plan`) that the client starts as a normal run, showing `display`. 400 `command_not_available` for commands not offered; 409 `session_busy` while a run is active. |
| GET | `/v1/settings/approvals` | `{mode, modes}`. Hermes' global `approvals.mode`: `manual`, `smart` or `off`. |
| PUT | `/v1/settings/approvals` `{mode}` | Sets it via `hermes config set` (fixed argv, no shell), reads it back, audits the change. 400 `invalid_mode` for anything else. |
| GET | `/v1/runs?session_id&active=true` | Runs the bridge knows about. Kept for 30 min after they finish. |
| GET | `/v1/runs/{id}` | `{run_id, session_id, status, created_at, finished_at, last_seq, pending_approval}` |
| GET | `/v1/runs/{id}/events?after=N` or `Last-Event-ID: N` | SSE stream: replays buffered events after N, then follows live, then closes after the terminal event. |
| POST | `/v1/runs/{id}/stop` | Moves the run to `stopping`, and the stream ends with `run.cancelled`. |
| POST | `/v1/runs/{id}/steer` `{text}` | Injects guidance into a running turn. |
| POST | `/v1/runs/{id}/approval` `{choice, request_id?}` | `choice` is one of `once, session, always, deny`. Returns 409 if nothing is pending. |

Run `status` values: `running, waiting_for_approval, stopping, completed, failed, cancelled, interrupted`.

## SSE frames (`/v1/runs/{id}/events`)

Each frame looks like this:
```
id: 7
event: tool.started
data: {"timestamp": 1790827241.14, "tool": "terminal", "preview": "uname -r"}
```
- `id` is a sequence number per run. It starts at 1 and has no gaps.
- Lines starting with `:` are keepalives, sent every 15s.

Events forwarded from Hermes:

| event | data fields |
|---|---|
| `message.delta` | `delta`. Append it to the streaming assistant bubble. |
| `message.interim` | `text, already_streamed`. Commentary in the middle of a turn. |
| `reasoning.available` | `text` |
| `tool.started` | `tool, preview` |
| `tool.completed` / `tool.failed` | `tool, duration, error, preview` (output preview, redacted) |
| `approval.request` | `request_id?, command (redacted), description, choices[]` |
| `approval.responded` | `choice, resolved` |
| `run.steered` | `accepted` |
| `subagent.start` / `subagent.complete` | `goal, status, summary, child_session_id, …` |
| `run.completed` | `output, usage, runtime{provider,model}` |
| `run.failed` / `run.cancelled` / `run.interrupted` | `error?` |
| `bridge.resync` | Older events were evicted. Reload `/messages`, then continue from `earliest_seq`. |

Events with `source: "bridge.reconcile"` were made by the bridge from a status poll after the Hermes stream dropped.

## Following a session driven elsewhere (`/sync`)

Runs started outside the bridge (desktop app, Telegram) are invisible to `/v1/runs`, so the app
can't stream them. It can still follow the conversation:

```
GET /v1/sessions/{id}/sync?since=<message_count>
→ {"messages": [...], "cursor": N, "count": N, "changed": bool,
   "active": bool, "ended_at":…, "end_reason":…, "pinned": bool, "model":…, "title":…}
```

- `since` is a **message count**, not an id. Pass the `cursor` you got last time.
- `messages` holds only the rows appended after `since`, oldest first. At the head it's empty and
  the bridge makes no messages call at all, so polling an idle session is one cheap request.
- First sync of a session (`since=0`) returns only the last `limit` rows, so opening a 500-message
  chat doesn't re-render all of it. Load the full history from `/messages` for that.
- `changed` is `count != since`, so a client can skip rendering entirely.
- `active` is true only for runs **the bridge** started. A desktop turn is not flagged while it runs.

Poll every 2s while messages are arriving, 5s when quiet. Stop when the app takes the session over
with its own run.

## Client rules

1. **Sending a message:**
   - Generate `client_request_id` once per user send. A UUID without dashes is fine.
   - Persist it before calling `POST /v1/runs`.
   - Retries must reuse the same id. That makes them safe: no duplicate runs.
2. **Streaming:**
   - Track the last `id` you received.
   - On reconnect, reattach with `after=<last id>`.
   - Never re-request from 0 into an existing bubble, or you'll append duplicate text.
3. **Resuming after the app was killed:**
   - Call `GET /v1/sessions/{id}`.
   - If `active_run` is set, render history from `/messages`, then attach with `after=active_run.last_seq` so you get only new events.
   - Alternatively, attach with `after=0` and rebuild the bubble from scratch.
4. **When a run finishes:** reload `/messages` and treat it as the source of truth for what the bubble finally shows.
5. **Bridge restarts:** after a bridge restart, older run ids return 404. Fall back to `/messages`.

## Known limitations

- **Smart approval mostly decides on its own.** With `approvals.mode: smart`, the `approval.request`
  event only appears when the smart assessor is uncertain; clear-cut safe commands run automatically
  and clearly dangerous ones are refused. Use `manual` to be asked every time.
- **Deleted sessions are gone for good.** Hermes has no undo for `DELETE`.
- **Runs started elsewhere don't stream token by token.** `/sync` follows a desktop or Telegram
  session as Hermes persists its messages, so text arrives in steps rather than live. Only runs the
  app itself started stream live.
