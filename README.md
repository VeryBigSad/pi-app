# Pi Mobile

Native Android control surface for the Pi coding agent running on your Mac. The Mac keeps every credential, runs Pi, and owns session truth; the phone is a fast, secure client for triage, steering, review, and dictation.

> **Status: live end-to-end core path proven 2026-08-12 against the real YC deployment.** The full module set exists: protocol schemas/codecs, Mac host daemon (Pi runtime, journal, sync, pairing, terminal, voice, push), Android core (protocol, network, security, storage, push, voice, update) and features (session, agents, settings), the xterm terminal module, the Go relay, and hardened Terraform. Verified live: YC deployment (VM, registry, sslip.io TLS 1.3, signed in-place update rollout), full pairing ceremony E2E on an API 29 emulator over the relay rendezvous, steady-state mTLS reconnect to READY, and the semantic round trip (phone message → journal → real Pi → LLM events → message.append) with a durable canonical log surviving daemon restarts. Pixel 8 Pro + Bitwarden first-owner registration and certificate issuance were verified 2026-08-13; steady-state auth-to-sync retest remains open. CI green: node/android/api29/api34-terminal/terraform/go + secret scan. **Remaining physical-device matrix, terminal-mode and voice E2E on device, and full timeline UI render evidence remain open.**

Application ID: `io.github.verybigsad.pimobile` · `minSdk 29` · Passkey RP: `verybigsad.github.io` · Pi baseline: 0.84.0 + reviewed patch

## What it does

- Attaches to Pi sessions on the Mac, lists them by state, and continues one from the phone.
- Streams assistant text, thinking, tool calls, bash output, diffs, and extension events, with unknown events kept inspectable as exact raw JSON.
- Answers Pi's real interactive dialogs (`select`, `confirm`, `input`, `editor`) as native mobile UI.
- Gates destructive tool/user-bash calls on their final extension-mutated arguments through a fail-closed Mac broker (`mac/approval`, `mac/pi-patch`, `mac/preload`).
- Pre-routes manifest-known custom-TUI invocations to real terminal mode, because RPC returns no detectable `ctx.ui.custom()` event.
- Notifies on completion without FCM, via UnifiedPush and a self-hosted ntfy distributor. Full no-Google use requires Android 14+ and a compatible third-party passkey provider such as Bitwarden; Android Credential Manager resolves that provider during the ceremony. API 29–33 still needs Play services for passkey auth.
- Dictates into the composer with Groq `whisper-large-v3-turbo`, with `~/.groq_key` never leaving the Mac.
- Surfaces agent activity (subagent fleet state) in a dedicated agents insight screen.
- Self-updates via an assisted, human-approved updater: signed metadata feed, monotonic `versionCode` high-water mark, and an APK signing-certificate pin. See [ADR-0020](docs/adr/0020-secure-self-update.md).
- Uses the Pi logo (`artwork/pi-logo-on-dark.svg`) for launcher icons and permits user screenshots plus OS-managed Recents previews; it never captures or uploads screen content itself.

Both modes are release requirements and both are implemented: **semantic** (native UI over `pi --mode rpc`) and **terminal** (bundled xterm over a real PTY).

## Architecture

```text
Remote:
Mac ===== standing authenticated control WSS =====> YC relay
Android -- authenticated data WSS --> relay -- one-use notice --> control WSS
Android <== one-use paired byte splice; inner TLS 1.3 ==> Mac
          QR-pinned server auth while pairing; mTLS after certificate

LAN:
Android ---------------- direct TLS 1.3 mTLS ------------- Mac

Mac host (Node 22, TypeScript; mac/host)
├── daemon: pairing coordinator, direct-TLS listener, relay manager,
│   admin socket, launchd agent, push sender, voice service
├── pinned, minimally patched Pi 0.84 RPC subprocess per session
│   (mac/pi-patch + mac/preload + mac/approval Unix-socket final-policy broker)
├── strict LF byte framer + exact Pi delta assembler (mac/host/src/pi)
├── SQLite fail-closed at-most-once command journal (mac/host/src/journal)
├── idle-only canonical snapshot actor and writer lease (mac/host/src/sync)
├── WebAuthn verifier + pairing CA (mac/host/src/security)
├── Groq VAD transcription worker (mac/host/src/voice)
└── node-pty + private tmux display/control clients (mac/host/src/terminal)

Android (Kotlin + Compose; android/)
├── core: protocol codec, network/TLS, security (Keystore, passkeys),
│   storage (encrypted cache/drafts), push (UnifiedPush), voice, update
├── feature: session (inbox, timeline, composer, approval sheet),
│   agents (fleet insight), settings (pairing, devices, push, update)
└── terminal: hardened WebView + locally built xterm bundle

Relay (Go; relay/): content-blind rendezvous. Standing Mac control WSS,
one-use data notices, P-256 route-key auth, public-key/revocation-only
persistence, byte splice; never terminates inner TLS.
```

Trust zones:

1. **Mac** — Pi, provider credentials, `~/.groq_key`, sessions, WebAuthn verification, all plaintext.
2. **Phone** — separate non-exportable P-256 TLS and relay route-auth keys, passkey, encrypted cache. No provider secrets.
3. **Relay and ntfy** — content-blind. The relay retains only route-auth public keys/revocation, signals the Mac's standing control WSS, pairs one-use data sockets, and copies bytes; it terminates outer WSS but never inner TLS.

Key decisions and why:

| Decision | Reason | ADR |
|---|---|---|
| Run an integrity-pinned, minimally patched Pi 0.84 CLI subprocess per session | Preserves real CLI loading/settings/packages/skills/prompts/extensions while adding only the reviewed final-policy call; full-dist-tree manifest verified at startup | [0001](docs/adr/0001-pi-rpc-subprocess.md) |
| Bounded 12-byte `PIMB` JSON/binary protocol | Carries exact Pi-line `rawJson` UTF-8 plus digest and parsed projection, with raw references whenever 128 KiB raw or 256 KiB escaped-frame bounds would be exceeded; supports terminal, prompt blobs, and PCM under hard bounds | [0002](docs/adr/0002-bounded-json-binary-protocol.md) |
| Fail-closed at-most-once SQLite command journal | Pi RPC ids are correlation ids, not idempotency keys: a probe with a duplicate id executed bash twice, so blind retry after a crash is unsafe | [0003](docs/adr/0003-fail-closed-command-journal.md) |
| Standing Mac control WSS + one-use data rendezvous + inner TLS; direct LAN mTLS | A NATed Mac remains reachable without keeping a relay data tunnel; P-256 route challenge auth survives cold reconnect, and LAN skips relay latency | [0013](docs/adr/0013-authenticated-control-data-rendezvous.md) |
| WebAuthn verified on the Mac, RP on GitHub Pages | The RP host only needs to serve Digital Asset Links; verification belongs where session truth already lives | [0005](docs/adr/0005-mac-webauthn-verifier.md) |
| UnifiedPush with self-hosted ntfy primary | Doze makes background sockets unreliable, and FCM would require external Google credentials; optional FCM stays behind the same wake interface but is not a release gate | [0006](docs/adr/0006-unifiedpush-ntfy.md) |
| Bundled xterm in a hardened WebView + node-pty + private tmux with split input | RPC returns `undefined` from `ctx.ui.custom()`; tmux consumes Kitty negotiation, so exact key bytes go through a control client while output goes through the display PTY | [0007](docs/adr/0007-terminal-compatibility.md) |
| Minimal Pi 0.84 final-policy patch + preload broker | Ordinary extension hooks can mutate arguments and nested AgentSessions may disable extensions; the immutable out-of-band hook gates final tool args and resolved `executeBash` in every in-process session, while the host separately gates bridge-owned actions | [0012](docs/adr/0012-final-policy-broker.md) |
| VAD-driven 8-12 s Groq chunks | Batch-only transcription needs ordered chunks; durable RPM/RPD/audio-window limits, 429 backoff, exact 10 s minimum billing, and hard daily/monthly budgets bound cost | [0009](docs/adr/0009-groq-vad-chunking.md) |
| Exactly one dedicated YC VM, created last | Remote rendezvous and no-Google push need one always-on public host; serverless WebSockets are unsuitable for terminal and PCM traffic | [0010](docs/adr/0010-one-dedicated-yc-vm.md) |
| Native Kotlin + Compose | Credential Manager, Keystore, audio, WorkManager, accessibility, and release performance all need native integration | [0011](docs/adr/0011-native-android-stack.md) |
| Assisted, never-automatic self-update | Sideloaded app needs an update path, but a single metadata host must never push code; APK signature pin + monotonic versionCode + explicit human approval per download and install | [0020](docs/adr/0020-secure-self-update.md) |

## Quickstart

Prerequisites: macOS Apple Silicon, Node 22.23.x, JDK 21 (`/usr/libexec/java_home -v 21`), Android SDK with platform 36/build-tools 36.0.0, Go 1.26 for relay work.

### Mac host

```bash
npm ci
npm run build        # also builds the terminal web bundle
npm run check        # lint + typecheck + all Node/vitest suites

# Provision pinned Pi 0.84 (integrity-verified + patched), install launchd agent
npx pi-mobile-host install
# or: node mac/host/dist/src/cli.js install

npx pi-mobile-host status          # daemon state
npx pi-mobile-host pair            # QR invitation + short code + local confirm
npx pi-mobile-host devices         # list paired devices
npx pi-mobile-host revoke <id>     # revoke a device (kills its sessions)
npx pi-mobile-host verify          # pinned-Pi integrity + data dir permissions
npx pi-mobile-host serve           # foreground daemon (what launchd starts)
```

Data lives in `~/Library/Application Support/PiMobile` (override with `--data-dir`).

### Android app

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug                       # debug APK (own identity, cannot talk to production RP)
./gradlew assembleRelease                     # release APK (dedicated signing identity)
./gradlew testDebugUnitTest lintDebug         # unit + lint
```

### Performance harness

`:android:benchmark` is an opt-in Macrobenchmark/Baseline Profile harness. It profiles the app's R8-minified, shell-profileable `benchmarkRelease` target and uses `CompilationMode.Partial(BaselineProfileMode.Require)`. The deterministic timeline route is unavailable in ordinary `debug` and `release` builds; it uses 10,000 historical events, the production 500-message retention window, and a 100-event-per-second catch-up through the real session screen.

Generate the profile before measuring. This requires a rooted emulator or API 33+ device; retain the generated profile source, then use a Pixel 7-class-or-newer 60 Hz physical device for any release-budget claim:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ANDROID_SERIAL=<serial> ./gradlew --no-daemon --no-configuration-cache :android:app:generateReleaseBaselineProfile
ANDROID_SERIAL=<serial> ./gradlew --no-daemon --no-configuration-cache \
  :android:benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.verybigsad.pimobile.benchmark.PiMobileMacrobenchmark
```

Emulator Macrobenchmark output is diagnostic only and cannot satisfy any performance budget or physical-device gate. No physical benchmark result is claimed here.

Install the debug APK on an emulator or the release APK on a device, then pair by scanning the QR shown by `pi-mobile-host pair`.

### Relay / infrastructure

```bash
cd relay && go test ./...
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
```

The YC deployment is applied and live (folder `pimobile`, VM 158.160.210.102); see [docs/infra-and-cost.md](docs/infra-and-cost.md).

## Verification state (honest gates)

Verified live 2026-08-12 against the real deployment:

- **YC deployment**: folder `pimobile`, VM 158.160.210.102 (`standard-v4a`), private YC Container Registry + VM service account, sslip.io ACME TLS 1.3, hardened cloud-init, ntfy deny-all with `up*`-scoped ACL, signed in-place `pimobile-update` rollout path exercised repeatedly.
- **Pairing ceremony E2E over relay** on API 29 emulator: signed `pimobile://pair` invitation → rendezvous exchange → device route key registration → relay-spliced data tunnel → inner provisional TLS 1.3 → passkey ceremony (debug authenticator) → short code → local Mac confirmation → device certificate issued. Mac daemon relay control channel ready.
- **Steady state**: device reconnects via device-data tunnel → inner mTLS → passkey assertion → `USER_AUTHENTICATED` → sync → `READY`; session list loads over relay ("Pi Mac · Relayed"); live `session.catalog` push on new sessions; unknown-session snapshot union on resume.
- **Semantic round trip**: message typed on the phone → `command.submit` → fail-closed journal dispatch → real Pi RPC child → LLM response events → `message.append` published (canonical projected records).
- **Durable canonical log** (`canonical.sqlite`): epochs/sequences survive daemon restarts; snapshots serve durable history; respawn replay dedup.
- **CI green history**: node / android / api29 / api34-terminal / terraform / go + secret scan.

Verified earlier (unit/instrumentation, 2026-08-11):

- `npm run check` green: ESLint, `tsc -b`, and 372 vitest tests across 45 files.
- Gradle aggregate green: `verifyEnvironment testDebugUnitTest lintDebug assembleDebug assembleRelease`; 545 unit test executions (0 failures).
- API 29 instrumentation green: 113 connected tests, 0 failures, on `PiApp_API_29` (serial `emulator-5590`).
- Go relay suites green; Terraform `fmt`/`validate` wired into CI.
- DAL live: `https://verybigsad.github.io/.well-known/assetlinks.json` 200 `application/json`, zero redirects, both relations; local release APK v3 signature matches the published fingerprint.

Open gates (none of these are satisfied; do not claim otherwise):

- **Multi-session sync completeness.** The app session list may show a subset until the ack/fence loop converges; under work.
- **Timeline live-render.** Streamed message content verified at the projection layer; full UI render evidence pending.
- **Terminal-mode E2E on device** not run.
- **Voice dictation E2E** with a real microphone not run.
- **Physical-device matrix incomplete**: Pixel 8 Pro + Bitwarden first-owner registration and certificate issuance are verified; steady-state auth-to-sync, API 29 Google Play services, hardware-backed key, Doze/OEM battery behavior, Gboard/Bluetooth input, and performance budgets remain pending. Emulator results are never physical evidence.
- **GHCR package public visibility** (optional provenance path; runtime uses YC CR).
- **Update feed publication** needs `RELEASE_PUBLISH_TOKEN`.
- **Signing backup incomplete.** Dedicated EC release cert exists outside Git with mode-`0600` keystore and Keychain password; off-machine backup and rotation drill remain.
- **No Firebase credentials, by design.** Optional FCM adapter is not a release gate.

Full requirement-by-requirement evidence: [docs/requirements-traceability.md](docs/requirements-traceability.md).

## Security

- Provider credentials, `~/.pi/agent/auth.json`, and `~/.groq_key` never leave the Mac. Verified: the key is mode `0600`, 57 bytes, untracked.
- Passkeys authenticate the user; mTLS client certificates authenticate a paired device. Separate credentials, separate revocation.
- TLS 1.3 only: QR-pinned server auth in restricted pairing, then checked/revoked mTLS on normal data. Pairing binds the passkey ceremony to the signed invitation (which carries `macInstanceId`) and the TLS exporter under label `EXPORTER-Pi-Mobile-Pairing-v1`. Android serializes mutable `SSLEngine` transitions and hands encrypted records to a bounded dedicated egress actor, so the read pump never performs transport writes. Relay sees metadata/ciphertext only.
- Revoking a device certificate also revokes its relay route key and removes the device record; revoked peers are rejected at the next handshake and refused new relay rendezvous, so access ends within one handshake.
- Mutations are journaled `ARMED` before the first Pi stdin byte. Recovered `ARMED` becomes `INDETERMINATE`; recovered `RECEIVED` stays dormant until the same id/hash is deliberately resubmitted over a current READY, user-authenticated connection and every guard is revalidated.
- Approval is its own `approval.offer/decision/expired` protocol, never a Pi `confirm`. The patched final hook sees final mutated arguments; timeout, broker loss, disconnect, changed arguments, or classifier failure blocks and safely resumes the Pi turn. There is no "always allow".
- Push payloads are opaque bounded wakes with no session content; detail is fetched over the authenticated channel and rendered after unlock. A forged wake grants nothing.
- The updater verifies the APK signing certificate against a compile-time pin and enforces a monotonic `versionCode` floor; a compromised metadata host cannot push code.
- No production password, debug-certificate, or offline authentication bypass.
- Logs record error codes and opaque ids only, never prompts, Pi raw payloads, terminal bytes, audio, credentials, or keys.

See [docs/security.md](docs/security.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/plan.md](docs/plan.md) | Master plan, stage gates, ownership lanes, release gates |
| [docs/architecture.md](docs/architecture.md) | Frozen system shape and components |
| [docs/protocol-v1.md](docs/protocol-v1.md) | Frame format, envelope, sync, command dispatch, bounds |
| [docs/security.md](docs/security.md) | Threat model and security design |
| [docs/infra-and-cost.md](docs/infra-and-cost.md) | One-VM infrastructure, cost envelope, destroy proof |
| [docs/requirements.md](docs/requirements.md) | R1-R13 with testable acceptance criteria |
| [docs/requirements-traceability.md](docs/requirements-traceability.md) | Requirement to evidence mapping with real file paths |
| [docs/capability-matrix.md](docs/capability-matrix.md) | Every Pi surface, package, and local extension with its treatment |
| [docs/ux.md](docs/ux.md) | Screens, notifications and privacy, accessibility, motion |
| [docs/testing.md](docs/testing.md) | Test layers, how to run, CI coverage |
| [docs/adr/README.md](docs/adr/README.md) | Decision history and supersession |
| [docs/reviews/plan-verification.md](docs/reviews/plan-verification.md) | Full review dispositions, including rejected recommendations |
| [docs/research/](docs/research/) | Four research tracks with primary sources |

## Current local setup (verified 2026-08-12)

| Item | State |
|---|---|
| Pi | 0.84.0 pinned in `mac/pi-patch` with a full-dist-tree integrity manifest and a two-file final-policy patch |
| Local Pi extensions | 5: `btw/`, `macos-input-notifier.ts`, `mcp-tool-search.ts`, `self-reload.ts`, `subagent-model-policy.ts` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`; platforms 28/29/34/36; build-tools 36.0.0 |
| AVDs | Supported: `PiApp_API_29` (floor), `domonap` (API 34), `PiApp_API_36`; no-Google UI `PiApp_API_34_AOSP_UI`; headless `PiApp_API_34_AOSP`; negative-only `PiApp_API_28` |
| Running emulator | `PiApp_API_29` on serial `emulator-5590` (instrumentation evidence above) |
| Build tuple | Gradle 8.13, AGP 8.13.2, Kotlin 2.4.10, Compose BOM 2026.06.01, JDK21/JVM17, SDK 36/36/29, 9 Gradle workers |
| JDK | Temurin 21.0.8 (build JDK, mandatory) and 25.0.2 present |
| Node / Go / Terraform | 22.23.2 / 1.26.5 / 1.5.7 |
| Groq key | `~/.groq_key`, mode `0600`, 57 bytes. Read on the Mac at request time only, never serialized into a frame |
| tmux | 3.5a; `capture-pane -e` and `pipe-pane` both confirmed working |
| GitHub | `gh` authenticated as `VeryBigSad`, SSH git protocol |
| Yandex Cloud | `yc` present, `default` profile active; live deployment in folder `pimobile` (VM 158.160.210.102, `standard-v4a`, private CR + VM SA, sslip.io TLS 1.3) |

## Repository

<https://github.com/VeryBigSad/pi-app>
