# Development

How to work on this repository: run the tests, build the app, and cut a release.

## Layout

OS-specific code goes in `bridge/hermes_remote_bridge/host/`, never inline: see
[PLATFORMS.md](PLATFORMS.md#adding-a-platform).

```
android/    Kotlin + Compose app
bridge/     Python (FastAPI) bridge, packaged as hermes-remote-bridge
demo/       synthetic backend + screenshot capture, no real Hermes needed
docs/       one document per subject
install.sh  PC installer
```

## Run the tests

```bash
(cd bridge && uv sync && uv run pytest -q)      # bridge unit tests
(cd android && ./build.sh test)                 # app unit tests
```

The app tests include real pinned-TLS handshakes against a loopback server, so certificate
pinning is actually exercised rather than mocked.

## Build the app

Needs JDK 17+ and the Android SDK (`sdk.dir` in `android/local.properties`, or `ANDROID_HOME`).

```bash
cd android
./build.sh              # unit tests + HermesRemote.apk
./build.sh install      # same, then adb install
./build.sh test         # unit tests only
```

Without `android/keystore.properties` you get a debug-signed APK. For release-signed builds,
create that file (it is gitignored):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=release
keyPassword=...
```

Keep the keystore safe: Android only installs updates signed with the same key, so losing it
means every existing install has to be uninstalled. A self-built APK is signed with a different
key than the official releases, so the in-app updater cannot replace it with a release — that is
Android's rule, not a bug.

## Try it without a real Hermes

The demo stack runs the real bridge against a scripted fake backend, so you can work on the UI
without API keys or model calls. See [../demo/README.md](../demo/README.md).

```bash
(cd bridge && uv sync)
./demo/run-demo.sh --pair
```

## Live end-to-end tests

These need a real Hermes and spend a few model calls. Pair a throwaway device first and revoke it
afterwards:

```bash
bridge/.venv/bin/hermes-remote-bridge pair e2e --token-file /tmp/e2e.json
(cd bridge && uv run python tests/e2e_live.py /tmp/e2e.json)
(cd android && HERMES_REMOTE_E2E=/tmp/e2e.json ./gradlew testDebugUnitTest --tests '*LiveBridgeTest*')
bridge/.venv/bin/hermes-remote-bridge revoke e2e
```

Never point a probe run at a live conversation session. Create a throwaway session and delete it.

## Releasing

Versions ship by tag. The version comes from the tag — `v0.8.1` becomes versionName `0.8.1` and
versionCode `801`. The default in `build.gradle.kts` is the next development version.

```bash
git push origin main                       # wait for CI to go green
git tag -a v0.9.1 -m v0.9.1 && git push origin v0.9.1
```

`.github/workflows/release.yml` then builds, verifies the APK against the pinned signing
certificate (SHA-256) and publishes `HermesRemote-X.Y.Z.apk`. Installed apps see it in the System
tab at startup and update in place.

Signing needs two repository secrets: `ANDROID_KEYSTORE_BASE64` (the keystore, base64-encoded) and
`ANDROID_KEYSTORE_PASSWORD`. The key alias is `release`, which is deliberately *not* a secret.
Forks that publish their own builds need their own key and must change `Updater.REPO` to their
repository.

Two traps worth knowing:

- `apksigner` output differs between build-tools versions (`Signer #1` vs `V2 Signer`). Match on
  the digest, not the label.
- Never use a common word like `release` as a secret value: GitHub masks it everywhere in logs,
  including where it is legitimately needed.

Verify a built APK with:

```bash
aapt2 dump badging HermesRemote.apk | head -1
```

## CI

`.github/workflows/ci.yml` runs on every push and pull request:

- **bridge:** `uv sync` then `pytest -q`
- **android:** JDK 17, `testDebugUnitTest` and `assembleDebug`, uploading the debug APK

## Conventions

- One commit per logical change. Messages in the imperative: `bridge: ...`, `app: ...`,
  `docs: ...`, `demo: ...`, `ci: ...`.
- English for identifiers, comments, commit messages and documentation. User-facing strings in
  the app may be translated.
- No personal data in the repository: no real hostnames, IP addresses, tailnet names, Wi-Fi names,
  device names, tokens or session content — not in code, tests, docs or screenshots. Use
  `100.64.0.x`, `192.168.1.x`, `mypc.tail0000.ts.net`. `git grep` before every push.
- The bridge API is a contract with the app. Changing a field, an event name or an error code means
  changing [BRIDGE_API.md](BRIDGE_API.md) in the same commit.
