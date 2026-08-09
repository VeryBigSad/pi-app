# Pi Mobile 1.0 master plan

Last updated: 2026-08-09
Status: final-audited plan; Stage 0 scaffold started, executable contracts in progress

## Definition of done

Pi Mobile 1.0 is complete only when a release-signed Android app can pair with the Mac, authenticate with a Bitwarden-compatible passkey, operate Pi through native semantic UI, operate current custom TUI extensions through terminal mode, reconnect without corrupting state or replaying mutations, receive a UnifiedPush completion wake without FCM, dictate an editable Groq transcript, and pass automated plus physical-device release gates.

Both modes are release requirements:

- **Semantic:** `pi --mode rpc`, native timeline/settings/dialogs/review.
- **Terminal:** bundled xterm, node-pty, private tmux display/control clients, split input.

Provider/Pi/Groq secrets never leave the Mac. Relay and ntfy remain content-blind.

## Current baseline

Verified on 2026-08-09:

- Repository is private GitHub `VeryBigSad/pi-app`.
- Pi is 0.84.0; exact current extension versions are listed in [architecture.md](architecture.md) and must be machine-regenerated.
- Node 22.23.2 is active; JDK 21 is installed.
- Android SDK is installed: platforms 28/29/34/36, build-tools 36.0.0, emulator 37.1.11, platform-tools 37.0.1.
- Supported Google APIs AVDs: `PiApp_API_29`, `domonap` (API 34), `PiApp_API_36`; no-Google UI `PiApp_API_34_AOSP_UI` uses the default image and headless `PiApp_API_34_AOSP` uses AOSP ATD; `PiApp_API_28` remains unsupported negative-only.
- `~/.groq_key` exists, 57 bytes, mode `0600`.
- Go 1.26.2 and Terraform 1.5.7 are installed.
- No project YC resource, production DAL repository/file, release signing key, Firebase credential, or physical-device evidence exists yet.

Do not repeat stale claims that Android SDK/AVDs are absent or that the Groq key is world-readable.

## Fixed decisions

- Real CLI subprocess; strict LF Pi framing and strict delta assembly.
- Bounded 12-byte framed raw-event JSON/binary protocol, not Protobuf.
- Fail-closed durable journal with dormant recovered `RECEIVED`, explicit resubmit, and read-only `command.query`.
- Standing authenticated Mac control WSS, one-use outbound data rendezvous, inner TLS 1.3 (QR-pinned server-auth for pairing, mTLS after cert), and direct LAN mTLS.
- Mac WebAuthn verifier; RP `verybigsad.github.io`; package `io.github.verybigsad.pimobile`.
- UnifiedPush with self-hosted ntfy primary; optional FCM adapter.
- xterm WebView + node-pty + private tmux display/control split input.
- Integrity-pinned minimal Pi 0.84 final-policy patch plus `NODE_OPTIONS` preload/Unix-socket broker; direct host actions gated separately.
- Foreground PCM; Mac VAD chunks at 8–12 seconds; Groq ordered batch transcription.
- One new dedicated YC VM only after local acceptance.

Changes require an ADR, fixture/schema update, both language implementations, and repeated independent review.

## Ownership and merge discipline

After Stage 0 scaffolding, agents receive exclusive paths:

| Lane | Paths |
|---|---|
| Protocol | `protocol/**` |
| Mac runtime | `mac/host/src/pi/**`, `journal/**`, `sync/**`, `cli/**` |
| Transport/auth | `mac/host/src/transport/**`, `auth/**`; `android/core/protocol/**`, `network/**`, `security/**` |
| Android data | `android/core/model/**`, `storage/**` |
| Android semantic UX | `android/feature/sessions/**`, `conversation/**`, `review/**`, `settings/**` |
| Push | `android/feature/notifications/**`, `mac/host/src/notifications/**` |
| Voice | `android/feature/voice/**`, `mac/host/src/voice/**` |
| Terminal | `android/terminal/**`, `mac/host/src/terminal/**` |
| Approval/compatibility | `mac/approval/**`, `mac/pi-patch/**`, `mac/preload/**`, `mac/compatibility/**`, harness paths |
| Relay/infra | `relay/**`, `infra/**` |
| Integration | root build files, version catalogs, workflows, `README.md`, `AGENTS.md`, central `docs/**`, `tests/e2e/**` |

Each implementation lane owns its unit/fixture tests. Only integration changes shared manifests and central docs after scaffold. An integration PR may combine lanes only after their isolated checks pass. Never assign two agents the same path concurrently.

Every stage uses this loop:

1. Atomic implementation + tests by path owner.
2. Independent Codex-large review and Claude-large review in parallel.
3. Rank findings P0/P1/P2; fix every P0/P1.
4. Rerun focused and stage-wide suites.
5. Re-review security-sensitive fixes.
6. Update architecture/protocol/security/operations docs in the same integration PR.

A stage cannot advance with unresolved P0/P1 or unverifiable required evidence.

## Stage 0 — contract and security freeze

Parallel lanes after a short schema kickoff:

### 0A Protocol owner

- Materialize [protocol-v1.md](protocol-v1.md) as schemas/constants/errors/state machines and golden JSON/binary fixtures, including pairing registration/assertion, approval messages, prompt-image blobs, dormant commands, idle snapshots, terminal history, and route handshake vectors.
- Capture sanitized Pi 0.84 traces: interleaved thinking, content-only end events, signed/redacted authoritative final messages, parallel tools, dialogs, retry/compaction/follow-up, `agent_settled`, unknown/large exact raw lines.
- Write minimal Kotlin and TypeScript reference codecs consuming identical fixtures.

### 0B Security owner

- Convert [security.md](security.md) to executable threat/abuse tests.
- Freeze certificate profiles/SANs, provisional server-auth pairing, CSR-first exporter-bound registration/assertion challenges, route-key challenge/rotation/revocation, key wrapping, both DAL relations/origin derivation, and redaction.
- Pin `@simplewebauthn/server` to the exact release selected by lockfile and prove exact Android-origin handling before freezing it.

### 0C Compatibility owner

- Generate exact Pi/package/local/bundled-extension manifest with version/integrity and invocation-level `requiresTerminal`, side-effect class, expected activity, and watchdog deadline.
- Catalogue every command/tool/UI method/callback/ANSI/external dependency; assert source call sites: subagents 6, ask-user-question 4, MCP 3, usage 2, btw 1; add bundled `/llama` class.
- Pre-route `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and all discovered custom paths. Define unexpected-command kill/restart/resync tests; never depend on a nonexistent custom event.
- Define deterministic fake provider/MCP/web fixtures without modifying `~/.pi`.

### 0D Build/integration owner

- Scaffold modules and ownership boundaries.
- Freeze ADR-0019: Gradle 8.13/checksum, AGP 8.13.2, Kotlin/compiler 2.4.10, KSP 2.3.11, Compose BOM 2026.06.01, JDK21/JVM17, SDK 36/36/29, Node22.23.2/npm10.9.8/TS6.0.3, Go toolchain1.26.2; add catalog, verification, and locks.
- Fix `minSdk 29` (platform TLS 1.3; API 28 unsupported), build-tools 36.0.0, application ID, and exact xterm/node-pty integrity/packed hashes/locks. Reproducibly bundle xterm for Chromium 91 with the narrow `structuredClone` shim; run a minimal API 29 WebView boot/render/Unicode/input canary before terminal feature work.
- Add dependency locks, secret scan, SBOM/license jobs, and sanitized fixture policy.
- Wire `PiApp_API_29`, `domonap`, `PiApp_API_36`, no-Google UI `PiApp_API_34_AOSP_UI`, and headless `PiApp_API_34_AOSP` into distinct lanes; run `PiApp_API_28` only as an unsupported-install negative. AOSP lanes use debug-only fake auth; production no-Google auth requires Android 14+ and a third-party provider.
- Pin Pi 0.84 tarball/integrity and materialize the minimal final-hook patch, preload, source-locator drift check, patch hash, and notices.

Environment checks:

```bash
java -version
node --version
pi --version
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools sdkmanager --list_installed
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools avdmanager list avd
stat -f '%Sp %z %N' ~/.groq_key
```

Gate:

- Kotlin/TypeScript codecs pass identical fixtures, fuzz bounds, and unknown-field retention.
- Strict LF fixtures preserve `U+2028/U+2029`; Pi records over 16 MiB fault.
- Delta assembly is byte-identical to `message_end.message`; end-event replacement covers carried content, while `message_end` supplies authoritative thinking signature/redaction; exact `rawJson` digest/projection fixtures pass; leaf IDs reject UUIDs.
- Source patch runs after mutated handlers in root/nested/`extensions:false` sessions; one global active offer/FIFO eight, 30-second queue wait, 120-second decision, and 150-second total cap are contract-tested; timeout/unreachable returns block and resumes.
- Threat model, dormant-RECEIVED/query crash matrix, idle snapshot contract, prompt-image orphan flow, and invocation catalogue have no open P0/P1 after dual review.
- No cloud resource created.

## Stage 1 — parallel foundations

### 1A Mac runtime

Implement pinned/patched subprocess supervisor/preload, strict LF/raw digest framer, exact delta assembler, session actor/lease, idle-only canonical snapshot fence, event cache, SQLite `FULL` journal with dormant recovery/query, prompt-blob store/sweeper, and crash injection.

### 1B Android core

Implement codec, TLS-stream abstraction, immutable reducer, encrypted Room cache/drafts, Paging, process-death restoration, bounded queues, and basic locked Compose shell against fake host traces.

### 1C Transport/auth

Implement direct TLS and TLS-over-WSS adapters, standing control heartbeat/backoff, P-256 route challenge/rotation, one-use data notices, Mac PKI/key wrapping, CSR-first QR provisional TLS, separate first-owner registration/later-device assertion, exporter binding, revocation, path racing, and generations.

### 1D Relay/infra locally

Implement Go relay with persisted public-key/revocation registry only, control/data WSS pairing, one-use in-memory notices, challenge replay/rotation tests, local Caddy/ntfy, slow-consumer/no-payload behavior, Terraform modules/policy tests. Use local containers only.

### 1E Compatibility harness

Run semantic and PTY probes against isolated copied configuration, deterministic providers, and mocked external systems. Commit reviewed synthetic fixture extensions for CI that reproduce each local extension's UI/lifecycle shape without credentials or personal code; live local hashes/outcomes remain a separate uncommitted evidence artifact. Never mutate user configuration.

Gate:

- Encrypted echo and framed protocol work direct and through local WSS relay.
- Pairing accepts only QR-pinned TLS 1.3 server auth and no app data; post-cert direct/relay connections require mTLS and reject invalid/missing/expired/revoked peers.
- Journal kills prove recovered `RECEIVED` dormant until current READY same-id/hash resubmit/full revalidation; query never dispatches; 100 duplicates yield at most one Pi line.
- Host restart/active gap waits idle, freezes one canonical response, records final append ID separately from leaf, validates from append ID, and replays post-fence. A backward branch behind later off-branch appends publishes without livelock; concurrent append/leaf change retries.
- App builds/runs on API 29/34/36, API 34 no-Google default and AOSP ATD lanes, with provider-absent/setup-required auth states; API 28 fails or is excluded as documented negative.
- No cloud apply.

Known commands once scaffold exists:

```bash
./gradlew test lintDebug assembleDebug
npm ci
npm test
npm run lint
npm run typecheck
go test ./...
gofmt -w relay
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
```

## Stage 2 — semantic vertical slice

Parallel lanes:

- Mac runtime: full RPC facade, idle-only single-entries snapshot fence/adjunct cursor/leaf retry/post-fence replay, all documented commands, blob download/upload, diagnostics.
- Android UX: inbox, timeline, composer, model/thinking/queue/retry/compaction settings, session tree/actions, extension dialogs, raw inspector, image/artifact/diff cards.
- Android performance/data: active-row coalescing, markdown cache, windowing/paging, scroll policy, baseline profile and Macrobenchmark datasets.
- Approval owner: minimal pinned-Pi final-hook patch, global preload/Unix-socket client, resolved executeBash/direct-RPC single-offer checks, bridge-action gate, final-argument classifier/broker, approval protocol, versioned corpus, global FIFO/overflow/30-second queue/120-second decision/150-second total boundary tests, unreachable/nested-session tests.
- Compatibility owner: semantic scenarios for every pinned package/local extension and generated matrix.

Gate from fresh install:

- Pair/authenticate, create/resume real Pi, stream text/thinking/tools, steer, follow up, abort, reconnect, answer dialogs, review changes, and observe exactly one settlement.
- Every Pi RPC command has native UI/API or an explicit terminal fallback.
- Unknown exact `rawJson` is inspectable by verified digest; prompt images complete ready/ref/hash/orphan flow.
- Final mutated destructive sentinel is absent before allow and after deny/disconnect/broker loss; nested `extensions:false` and direct RPC paths gate; allow once executes once.
- Known custom invocations pre-route; an unexpected command watchdog restarts/resyncs without claiming a detectable custom event.
- No generic “Approve” steering UI.
- Current extension semantic outcomes are recorded; `extension_error` diagnostics do not become false task failures.

Emulator run (never use unqualified `adb` when more than one device is attached):

```bash
emulator -avd PiApp_API_36
adb devices -l
# Map serial to AVD: adb -s <serial> emu avd name
SERIAL=<the-PiApp_API_36-serial>
adb -s "$SERIAL" wait-for-device
ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest
```

Run `PiApp_API_29` floor and API 34 `domonap` separately or select each explicit serial. `adb wait-for-device` without `-s`, and screenshots/log pulls without `-s`, are forbidden in multi-device runs.

## Stage 3 — push, voice, and release identity

### Push lane

Implement UnifiedPush connector/service, ntfy distributor registration, opaque wake endpoint registration, Mac sender/outbox, notification dedupe, WorkManager catch-up, permission/force-stop/error UX, and fake distributor/server tests. Keep optional FCM behind the same interface and absent credentials non-fatal.

### Voice lane

Implement foreground `AudioRecord`, waveform/state UI, bounded PCM transport, Mac VAD with 300 ms pre-roll, 8-second preferred/12-second forced boundaries, 500 ms overlap, one in-flight/two queued cap, Groq client, ordered merge, final flush, cancellation, cleanup, and editable non-auto-sent transcript. Add a restart-durable pre-send ledger for every attempt: defaults 18 RPM/1,800 RPD/6,480 ASH/25,920 ASD, encoded overlap, 10-second minimum billing, `$0.25` UTC-day and `$2` UTC-month hard budgets. Implement 429 `Retry-After` seconds/date, monotonic wait up to 120 seconds, full-jitter 1–30-second fallback, and maximum three retries.

### Identity lane

Generate the dedicated release key outside Git; derive/cross-check signed-APK origin and DAL fingerprint; deploy Pages with both `get_login_creds` and `handle_all_urls`; verify HTTP/MIME/no redirect/content/API. Record signer/Pages compromise and overlap-rotation runbooks. External owner credential and release-key backup remain human gates.

Gate:

- Fake Groq/push suites pass, including silence, overlap, ordered dedupe, every quota window and restart rollover, exact cost/budget boundaries, 429 header/jitter/exhaustion, backlog stop, no auto-send, opaque payload, dedupe, and offline/app-open catch-up.
- A bounded opt-in live Groq test reads `~/.groq_key` only on Mac and leaves no temp audio.
- DAL validates exact production package/fingerprint/origin.
- Release-signed Bitwarden and real ntfy/Doze tests are scripted and await or contain physical-device evidence; lack of required device evidence blocks release, not implementation.

## Stage 4 — mandatory terminal compatibility

Terminal owner implements:

- bundled `@xterm/xterm@6.1.0-beta.292` JS/CSS/licenses pinned by full npm integrity, packed SHA-256, deterministic bundle hash, and Chromium-91 target; no CDN;
- source-locked plain-object `structuredClone` shim for API 29 WebView 91; runtime version/feature/write/render/Unicode/resize/input canary and explicit update-required state;
- hardened `WebViewAssetLoader`, CSP, origin-aware narrow message channel, no file/content/mixed/network access, Safe Browsing, renderer recovery;
- Node 22 with `node-pty@1.2.0-beta.15`, executable-helper and packaging preflight;
- private tmux 3.5+ socket/config, display client, persistent control client;
- local activation of Pi’s known Kitty flags;
- parsed split routing: exact Kitty/CSI-u press/repeat/release through serialized control `send-keys -H`, text/paste/mouse/replies through display PTY;
- native key row, IME composition, clipboard, search, resize, selection/scrollback, accessibility;
- connected xterm keeps 5,000 lines; reconnect restores visible pane only; separate bounded read-only `capture-pane` history drawer; no serialized xterm/full-scrollback claim;
- writer-lease semantic/TUI handoff and native image/artifact companion.

Compatibility owner invokes every discovered custom path/extension command in terminal scenarios and proves manifest pre-routing. `/mcp`, `/usage`, `/agents`, `/btw`, and `/llama` are mandatory fixtures; source call-site counts must match 6/4/3/2/1 for subagents/ask/MCP/usage/btw before any upgrade.

Gate:

- Fresh-image WebView canary and full terminal suite pass on `PiApp_API_29` (currently WebView 91.0.4472.114), API 34, and API 36; deterministic double-build hashes match; too-old/missing-feature state never opens terminal or remote code.
- ANSI/truecolor/OSC8, wide/combining/emoji, resize, bracketed paste, mouse, Shift/Alt+Enter, Ctrl keys, press/repeat/release, overlay, detach/reconnect pass.
- `space-invaders`-style key-release state works through the production split path.
- Disconnect never replays input; renderer kill creates a fresh attach with visible-pane redraw; bounded history opens separately and is never fed into xterm; mode switch never has two Pi writers.
- Real Gboard/IME and Bluetooth keyboard evidence is required before release. If Gboard fails, add a native `InputConnection`/Compose text-entry bridge; do not fork Termux.
- Images remain native cards; do not claim tmux Kitty image parity.

## Stage 5 — cloud, hardening, E2E, release

Only after Stages 0–4 local gates:

1. Review Terraform plan and current YC calculator.
2. Record unrelated resource IDs; create one dedicated VM; prove unrelated resources unchanged.
3. Deploy pinned Caddy, relay, and ntfy.
4. Run remote WSS/inner-mTLS, direct LAN TLS, push, fault, load, and security suites.
5. Run release Macrobenchmarks and manual emulator/device suites.
6. Produce SBOM, dependency/license/secret scans, signed artifacts, operations/destroy/rollback evidence.
7. Dual final review, fix and re-review all P0/P1.
8. Push reviewed history and publish private GitHub prerelease, then 1.0 after soak.

Terraform commands and safeguards are in [infra-and-cost.md](infra-and-cost.md).

## Automated release gates

| Area | Evidence |
|---|---|
| RPC | LF/CRLF/Unicode/16 MiB; exact raw UTF-8+digest+projection; text/thinking content and tool end replacement plus authoritative final signature/redaction |
| Lifecycle | retries, compaction, queued continuation; push only at `agent_settled` |
| Journal | crash boundaries; dormant recovered `RECEIVED`; READY resubmit/revalidation; query non-dispatch; duplicate/hash/integrity failure |
| Sync | loss/duplicate/gap; idle fence; append ID ≠ leaf; backward-branch no-livelock; append/leaf retry; adjuncts; post-fence replay; 100 reconnects |
| Protocol | cross-language fixtures, fuzzing, unknown fields/types, raw references, allocation bounds |
| TLS/auth | provisional pinned server-auth pairing, registration/assertion separation/exporter+CSR binding, direct/relay mTLS, P-256 route cold reconnect/rotation/revocation |
| Approval | patch integrity/order; final args; nested `extensions:false`; direct RPC; broker deadline/unreachable; offer/decision/expired; sentinel |
| Push | ntfy/UnifiedPush registration, opaque payload, settled dedupe, Doze catch-up, permission/force-stop |
| Voice | VAD/silence/overlap/final fragment; durable RPM/RPD/ASH/ASD and budgets; 429 header/jitter/retry cap; backlog/cancel/temp cleanup/no auto-send |
| Terminal | rendering/keys/input loss; connected 5k lines; visible-pane reconnect; bounded history drawer; isolation/IME/device |
| Extensions | invocation manifest/pre-route/watchdog; `/mcp` `/usage` `/agents` `/btw` `/llama`; semantic+PTY scenarios; direct Node/fs non-sandbox proof |
| Supply chain | locks, checksums, SBOM, SCA, licenses, secret scan, signed package smoke |
| Infra | fmt/validate/policy, local load/restart, plan isolation, remote health/destroy orphan check |

## Performance gates

Release build, Pixel 7-class or newer physical 60 Hz device:

- cold time-to-interactive after unlock p95 < 1.5 s;
- 10,000 events, 500 finalized messages, 100 events/s benchmark;
- frame p95 < 16.7 ms, p99 < 33.4 ms, zero >700 ms frozen frames;
- Android PSS < 250 MiB and retained growth < 50 MiB after five open/reconnect cycles;
- visible semantic delta p95 < 100 ms direct, < 250 ms at controlled 80 ms relay RTT;
- 500-message catch-up < 2 s direct, < 5 s relayed;
- terminal input-to-echo p95 < 150 ms at controlled 80 ms RTT;
- no network/database/markdown/image work on main thread;
- host idle RSS < 150 MiB excluding Pi children; relay idle RSS < 100 MiB.

Threshold changes require benchmark evidence and ADR, not a test-only relaxation.

## Manual evidence

### Supported API 29 floor and API 34/API 36 emulators

- API 29 install/startup, TLS 1.3, storage, network, process-death, and unsupported-API coverage on `PiApp_API_29`, plus Linux CI;
- API 28 `PiApp_API_28` negative proves the package is unsupported/rejected and is never counted as floor coverage;
- `PiApp_API_34_AOSP_UI` proves no-Google UI/notification behavior and `PiApp_API_34_AOSP` proves headless transport; both use fake debug auth and do not prove production passkeys;
- fake credential pairing/auth;
- phone/tablet/foldable layouts, light/dark, rotation, process death;
- semantic stream/steer/follow-up/abort/dialog/diff/image/raw;
- terminal keys/resize, connected 5k lines, visible-pane reconnect, bounded history drawer, renderer kill;
- airplane mode, relay/host restart;
- TalkBack order and polite streaming, 200% font, 48 dp targets, contrast;
- sanitized screenshots and semantic/terminal recordings.

### Physical release-signed Android 14+ device

- Bitwarden-selected passkey creation/assertion and DAL;
- hardware-backed/key-invalidation inspection without unsupported claims;
- direct LAN and YC relay;
- Android 14+ with Bitwarden as third-party passkey provider and self-hosted ntfy while Google services are disabled/absent;
- Doze/background completion and approval wakes; OEM battery behavior;
- real microphone/Groq editable transcript;
- Gboard plus Bluetooth keyboard press/repeat/release;
- device certificate and passkey revocation.

The integration owner must personally use the app. Automated UI tests do not replace this evidence.

## Rollout and rollback

1. Create/back up dedicated Android release key.
2. Deploy account GitHub Pages DAL and validate it.
3. Install Mac host locally; create owner passkey/pairing CA.
4. Apply the one-VM stack and configure ntfy distributor.
5. Pair release app and run full direct/remote acceptance.
6. Publish private prerelease; soak on physical device.
7. Complete dual final review and security rerun.
8. Publish 1.0; retain previous compatible app/host/relay artifacts.

Upgrade host before app when a new protocol minor is required. Unsupported major fails visibly; there is no insecure fallback. Relay rollback may drop tunnels but never loses session truth. Android database migration must retain the previous install artifact and tested downgrade/recovery runbook.

## Honest limitations and external gates

- Required Pages/DAL and dedicated release key do not exist; signer/Pages compromise remains residual identity risk even after exact-origin pinning.
- Real Bitwarden, ntfy under Doze/OEM rules, microphone, hardware key, and Gboard evidence needs a physical device.
- API 29/34/36 Google APIs and API 34 AOSP ATD lanes are installed; API 28 is negative-only, not supported.
- Optional FCM live test needs Firebase credentials and is not a release blocker.
- Public Mac signing/notarization needs Apple Developer credentials; local private installation can proceed without them.
- Offline/sleeping Mac cannot execute, authenticate fresh users, or send wakes.
- UnifiedPush is best effort under force-stop/battery restrictions; app-open catch-up remains authoritative.
- Groq is external, rate-limited, and chunked batch transcription.
- tmux images and Mac-only GUI windows cannot be reproduced literally.
- Current compatibility is tied to exact Pi/extension versions and must be regenerated after upgrades.

## Requirement trace

- R1/R2: Stages 1–2, protocol/sync/RPC gates.
- R3: Stages 0/2/4, exact capability harness and terminal mode.
- R4/R5: Stages 2/4/5, accessibility/manual/performance gates.
- R6/R7: Stages 0–3, DAL/WebAuthn/mTLS/journal/approval gates.
- R8/R9: Stage 3 and physical-device tests.
- R10: Stage 5, single dedicated VM, cost/destroy evidence.
- R11/R12: every stage’s tests; Stage 5 packaging/supply-chain/release.
- R13: same-PR docs, ADRs, evidence, and ownership rules.
