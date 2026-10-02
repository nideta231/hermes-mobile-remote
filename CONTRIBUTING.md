# Contributing

Thanks for looking at this. The rules below are short on purpose; the reasoning is in the last
section, and most of it is "this bit me once".

## The rules

**English everywhere.** Identifiers, functions, files, comments, commit messages, documentation and
pull request descriptions. User-facing strings in the app are the one exception — translate those
if you like.

**One commit per logical change.** A commit does one thing and does it completely: a fix, a
feature, a refactor, a documentation pass. Do not mix a refactor with a behaviour change in the
same commit; the reviewer cannot tell which part caused what.

**Commit messages in the imperative, prefixed by area.**

```
bridge: reject chunked request bodies
app: keep the draft when a run is steered
docs: explain why the bridge buffers run events
demo: return {"session": {...}} from session creation
ci: match apksigner's signer line across build-tools versions
```

**No personal data, ever.** Not in code, tests, documentation, commit messages or screenshots. That
means no real hostnames, IP addresses, tailnet names, Wi-Fi network names, device names, usernames,
file paths, API keys, tokens or conversation content. Use the reserved examples: `100.64.0.x`,
`192.168.1.x`, `mypc.tail0000.ts.net`, `192.0.2.1`. Before every push:

```bash
git grep -nE '100\.(6[4-9]|[7-9][0-9])|192\.168\.|tail[0-9a-z]*\.ts\.net|ts\.net'
git log -p --all | grep -iE 'password|token|api[_-]?key' | head
```

**Never commit a secret.** No API keys, no keystores, no `keystore.properties`, no pairing
payloads, no `local.properties` with a local SDK path that other people will not have. These are
gitignored; leave them that way.

**The bridge API is a contract.** If you change a field name, an SSE event name, an error code or a
status code, update [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md) in the same commit. The app codes
against that document, and a silent change breaks installed apps in a way no test here would catch.

**No platform checks outside `host/`.** Do not write `sys.platform`, `os.name` or a hardcoded `/proc`
path in the bridge. Ask the host object, and add the method to `host/base.py` with a safe default.
A change that only works on one OS must say so in [docs/PLATFORMS.md](docs/PLATFORMS.md).

**Add a test with the change.** `bridge/tests/` for the bridge, `android/app/src/test/` for the app.
A bug fix gets a test that fails before it and passes after. The app tests should cover the pure
logic (`LiveReducer`, `HistoryMapper`, `StatusMapper`, request building) rather than the UI.

**Verify before you push.**

```bash
(cd bridge && uv run pytest -q)
(cd android && ./build.sh test)
```

If your change touches the UI, look at it. Build and install, or drive the demo stack
([demo/README.md](demo/README.md)) and check the screens you changed at a small size as well as
a normal one.

**Keep the demo working.** If you change an endpoint the app calls, or a response shape, the demo
backend in `demo/mock_hermes.py` has to keep up. A demo that lies is worse than no demo.

## Pull requests

1. Open an issue first for anything larger than a bug fix, so the approach can be agreed before you
   write it.
2. Branch from `main`, one topic per branch.
3. Fill in the description: what changed, why, and how you verified it. Screenshots for UI changes.
4. CI must be green. It runs the bridge tests and the app unit tests.
5. One logical change per pull request. Unrelated cleanups belong in their own PR.

## Reporting bugs

Include what you expected, what happened, and how to reproduce it. The bridge's audit log
(`~/.local/state/hermes-remote/audit.log`) and `hermes-remote-bridge doctor` output are usually the
most useful things to paste. Redact tokens — the log does not contain any, but your prompt might
appear in a screenshot.

## Security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

## Why these rules

A few of them exist because of something that actually went wrong:

- **English only** because mixing languages in identifiers makes a codebase harder to read for
  everyone, including the person who wrote it six months ago.
- **No personal data** because this is a public repository. A screenshot with a real session title
  or a Tailscale hostname is a small mistake with a long tail, which is why the demo stack exists
  and why the screenshots are recorded against synthetic data.
- **One commit per change** because the alternative is a diff where you cannot tell whether a line
  moved because of the fix or because of the reformat.
- **The API contract in the same commit** because the app and the bridge ship together but the
  contract between them is invisible to the compiler.
