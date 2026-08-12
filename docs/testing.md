# Testing strategy

Last updated: 2026-08-12

Rule that shapes everything below: **tests assert semantics, not shape.** A test that checks a field exists is not a test. Fault paths are as mandatory as happy paths, because every interesting failure here is a fault path: a dropped socket, a crash between journal states, a coalesced update, a denied approval, a chunk that returned out of order, a tmux pane that mangled a key release.

Fixtures are sanitized, checked in (`protocol/fixtures/`), and never produced by mutating `~/.pi`.

## Current suites and how to run them

### Node / TypeScript (mac host, protocol, approval, scripts)

```bash
npm ci
npm run check     # eslint . && tsc -b && vitest run
npm test          # vitest run only (372 tests, 45 files, green 2026-08-11)
npm run terminal:verify   # rebuild xterm bundle and assert committed assets are unchanged
npm run fixtures:verify   # cross-check golden protocol fixtures
npm run dal:verify        # live DAL check (200/JSON/no-redirect/both relations)
npm run identity:verify   # release-signing identity derivation check
```

Vitest covers `protocol/**`, `mac/**`, `scripts/**` (see `vitest.config.ts`). Suites of note: `mac/host/test/journal.test.ts` (crash matrix), `pi-core.test.ts` (framing/delta/raw), `canonical-snapshot.test.ts`, `security-*.test.ts`, `relay-*.test.ts`, `terminal-*.test.ts`, `voice*.test.ts`; `protocol/ts/test/{pimb,conformance,fuzz}.test.ts`; `mac/approval/test/broker.test.ts`; `mac/pi-patch/test/{patch,runtime}.test.ts`.

Smoke helpers against real Pi: `npm run pi:smoke`, `npm run pi:smoke:daemon`.

#### Opt-in live Mac-host Groq transcription

This host-only live gate is macOS-only and excluded from normal `npm test` runs. It does not exercise Android, a phone microphone, PIMB transport, relay, composer integration, or phone-to-Mac E2E. It requires executable `/usr/bin/say` and `/usr/bin/afconvert`, plus an owner-only regular `~/.groq_key` with mode `0600`. Missing prerequisites fail when explicitly requested; without the opt-in flag, the test is reported as skipped.

```bash
PI_GROQ_LIVE=1 npx vitest run mac/host/test/voice-live.test.ts
```

`voice-live.test.ts` uses macOS text-to-speech to synthesize a fixed non-sensitive phrase, converts and validates it as 16 kHz mono s16le PCM, and passes those bytes directly through the Mac `GroqTranscriber`. It requires nonempty text and stable normalized fixture keywords, asserts a 2–8 second duration and ledger accounting for one to four provider attempts, then closes the ledger and removes its private temporary directory. Generated audio is enclosed by a mode-`0700` directory, normalized to mode `0600`, never retained as an artifact, and deleted even on failure. The key remains at `~/.groq_key`; the test suppresses tool output and never logs or persists the returned transcript. This proves the host/provider path only, not phone dictation E2E.

Recorded local gate: 2026-08-12, one test passed in 1.84 seconds.

### Android / Gradle

JDK 21 is mandatory; the build enforces it (`require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21))` in the root build). Temurin 21 is at `/Library/Java/JavaVirtualMachines/temurin-21.jdk`; JDK 25 is installed but is not the build JDK.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew verifyEnvironment testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Gradle runs with 9 workers (`org.gradle.workers.max=9` in `gradle.properties`) plus parallel/configuration-cache. Last recorded run: 545 unit test executions (311 debug + 234 release variant), 0 failures.

Scope to one module when iterating (do not run the aggregate for a focused change):

```bash
./gradlew :android:core:push:testDebugUnitTest
./gradlew :android:feature:session:lintDebug
```

### Android instrumentation (emulator)

Recorded green run: 113 connected tests, 0 failures, on AVD `PiApp_API_29` (Google APIs, WebView 91.0.4472.114) at serial `emulator-5590`. Modules covered: app launch + terminal canary, core security/network/storage/push/voice/update, feature session/agents/settings, terminal runtime.

Voice permission coverage: coordinator unit tests prove one grant-triggered retry, denial without a retry loop, and stale-session rejection. Session instrumentation proves denied permission offers an explicit retry and permanent denial routes only to Android app settings. The ActivityResult ceremony is not synthetic-E2E-controlled; debug transcript hooks remain transcript-only.

Always target the serial explicitly; never use unqualified `adb` when more than one device may exist:

```bash
adb devices -l
adb -s emulator-5590 emu avd name   # confirm it is PiApp_API_29
ANDROID_SERIAL=emulator-5590 ./gradlew connectedDebugAndroidTest
# or scoped:
ANDROID_SERIAL=emulator-5590 ./gradlew :android:core:update:connectedDebugAndroidTest
```

API 34/36 and no-Google AOSP lanes are installed (`PiApp_API_36`, `domonap`, `PiApp_API_34_AOSP_UI`, `PiApp_API_34_AOSP`) but have **no recorded green run yet**; do not cite them as evidence. API 28 is a negative-only lane.

### Installed-stack live E2E safety contract

`npm run e2e:installed` is destructive only to one fresh host-owned E2E session and a fresh debug APK installation. It captures the original session-ID set, creates a new UUIDv4 semantic session through the mode-`0600` local admin socket, receives a random delete capability, seeds `PONG` through real `prompt` and `get_state` dispatches, and waits for that content in the canonical log. It never selects, trusts, or mutates an operator-supplied session. `--session-id` and `PI_E2E_SESSION_ID` are rejected.

The run fails closed unless all of these are supplied:

- `--allow-destructive-session` (or `PI_E2E_ALLOW_DESTRUCTIVE_SESSION=1`) acknowledges mutation of the harness-owned session. Without it the harness exits with `E2E_DESTRUCTIVE_SESSION_OPT_IN_REQUIRED` before session creation.
- The target serial starts with `emulator-`; `adb emu avd name` must return exactly an allowlisted AVD name on its first line followed only by the emulator-console `OK` line, and `ro.kernel.qemu=1` is required. `ro.boot.qemu.avd_name`, when nonempty, must match that console identity; its absence is accepted for the valid API 29 AVD. Allowed names are `PiApp_API_29`, `PiApp_API_34_AOSP`, `PiApp_API_34_AOSP_UI`, `PiApp_API_36`, and `domonap`. Any physical device, unknown AVD, malformed console response, or inconsistent nonempty identity fails closed.
- `--isolate-host-auth` is required for a fresh debug passkey ceremony against a real host. The owned E2E session is created and canonically seeded before the daemon stop, so it survives the isolation restart. Recovery is registered before that stop; it preserves private `0600` host/route backup outside artifacts, restores and verifies owner/device/instance/route state, then restarts and health-checks on every path.

After APK build and installation, the harness captures a paired-device baseline immediately before pairing. Teardown revokes only IDs absent from that successfully captured baseline; if baseline capture never succeeds, it does not query or revoke devices. It then restores isolated host authentication when selected and presents the delete capability. The daemon stops the owned actor, transactionally removes that session's canonical state and records, removes only that session's command-journal rows, and removes only its checked `sessions/<uuid>` directory; it refuses traversal, symlinks, ownership mismatches, and pre-existing sessions. The harness verifies the original session-ID set is restored. Any cleanup failure fails E2E; an interrupted disposal remains capability-bound and can only be retried by that owned session's cleanup path.

The pairing invitation remains in a random, mode-`0600`, one-use device channel; neither it nor host-state backup bytes are copied into artifacts. Release APK analysis remains part of the run, so debug hooks cannot appear in release.

Safe final live invocation:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
npm run e2e:installed -- \
  --serial emulator-5590 \
  --allow-destructive-session \
  --isolate-host-auth \
  --hooks voice,push
```

`voice` selects a debug-only deterministic host/Groq transcript hook. It injects a synthetic `voice.partial` then `voice.finish` through the production transcript gate and proves the selected session draft changes without auto-send. `push` selects a debug-only opaque wake hook: it first validates a bounded opaque wake ID, then drives the application wake/reconnect contract and observes the authenticated sync transition. Each selected hook atomically records only its stable pass/fail code into a bounded debug-only report; missing execution is `not-run`, never a pass. Unselected hooks are `not-selected`/skipped, not passed.

A real UnifiedPush distributor callback cannot be deterministically supplied by a stock emulator. It is a separate explicit external gate: selecting `--hooks external-push` fails with `E2E_EXTERNAL_PUSH_DISTRIBUTOR_REQUIRED` until a configured distributor test environment is supplied. A green `voice,push` installed-stack run is therefore not evidence of distributor delivery, Doze, or physical-device wake behavior.

After submitting the final reply canary, the 180-second reply deadline is only a timeout ceiling. The installed test polls sanitized state and fails immediately with a stable code for `SEQUENCE_GAP`, command rejection/indeterminate/send failure, or lost authentication/connection. Evidence retains those codes and bounded hook outcomes only; it never stores the canary prompt/reply, invitation, credentials, keys, or transcript content.

### Go relay

```bash
cd relay && go test ./...
```

All six packages green (`cmd/relay`, `internal/auth`, `bootstrap`, `httpapi`, `pairing`, `registry`, `rendezvous`).

### Infrastructure

```bash
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -backend=false
terraform -chdir=infra/terraform validate
infra/local/check-orphans.sh            # after any destroy experiment
infra/local/smoke-ntfy-auth.sh          # local ntfy ACL smoke
infra/local/verify-relay-image.sh       # cosign policy verification of relay image
```

No `terraform apply` has been run; there is no cloud evidence.

### CI coverage (`.github/workflows/`)

| Workflow | Contents |
|---|---|
| `ci.yml` | `node`: `npm ci`, `npm run check`, terminal verify, fixtures verify, DAL verify, `npm audit --audit-level=high`. `supply-chain`: deterministic CycloneDX and SPDX SBOMs from npm lock/workspaces, resolved Gradle classpaths, and `go list -m`; fail-closed license policy; Grype high+ SCA for every CycloneDX SBOM and the locally rebuilt digest-pinned relay base-image closure. `android`: JDK 21, unit tests, lint, assemble debug+release. `android-api29`: emulator-runner API 29 connectedDebugAndroidTest across app + all core + terminal + feature/session. `terraform`: fmt/init/validate. `go`: relay tests. |
| `dal.yml` | scheduled live DAL verification. |
| `relay-image.yml` | relay image build/verify/publish with cosign keyless signature (identity pinned in cloud-init cosign policy). |
| `secret-scan.yml` | gitleaks. |
| `android-release.yml` | manual `workflow_dispatch`: build/verify signed APK, mirror to `VeryBigSad/pi-app`, regenerate `update-v1.json`, commit to Pages repo. |

## Layers

| Layer | Runs on | Scope | Status |
|---|---|---|---|
| Contract and fuzz | CI, both languages | Identical golden fixtures for the framed protocol; allocation bounds | **exists** — `protocol/ts/test/*`, `android/core/protocol/src/test/*` against `protocol/fixtures/pimb-v1.json` |
| Pi framing and assembly | CI | LF scanner, oversized records, exact delta assembly | **exists** — `mac/host/test/pi-core.test.ts` |
| Unit | CI | Framing/reducers/journal/VAD/merge/redaction, P-256 route challenges/one-use notices | **exists** — 372 vitest + 545 JVM tests green |
| Integration | CI and local | Mac host against a real `pi --mode rpc` process | **exists** — `rpc-process.test.ts`, `runtime-supervisor.test.ts`, `pi:smoke` helpers |
| Journal crash matrix | CI | Kill at every transition and stdin boundary | **exists** — `mac/host/test/journal.test.ts` |
| Android instrumentation | Emulator | Compose behavior, navigation, accessibility semantics | **exists, API 29 only** — 113 green on `emulator-5590` |
| Transport and auth | CI and emulator | Direct TLS, TLS over WSS, certificate lifecycle, WebAuthn verifier | **exists** — unit + partial instrumentation |
| E2E | Emulator plus local host and relay | Phone-to-Mac scenarios over the real transport | **partial** — smoke daemons exist; full E2E not wired into CI |
| Fault injection | CI and emulator | Disconnects, gaps, epochs, crashes, malformed frames, clock skew | **partial** — covered within unit suites, no dedicated harness |
| Terminal | Emulator plus real PTY | Rendering, keys, resize, reconnect, isolation | **exists, API 29 only** — canary + runtime instrumentation |
| Security | CI | Replay, revocation, redaction, DAL, opacity, dependency scan | **exists** — security suites + `dal.yml` + `secret-scan.yml` |
| Supply chain | CI | Locks, checksums, CycloneDX SBOMs, high-severity SCA, fail-closed licenses, secret scan, signed package smoke | **exists** — `scripts/supply-chain.mjs`, exact license overrides, pinned Grype image, `npm audit`, `secret-scan.yml`, and relay image verification |
| Performance | Physical device; emulator indicative only | Release-build Macrobenchmark against the budgets below | **missing** — no Macrobenchmark suite in tree |
| Manual | Emulator and physical device | The matrices below, with sanitized artifacts | **pending** — physical device unavailable |

## Contract corpus

Kotlin and TypeScript codecs consume **identical** checked-in fixtures (`protocol/fixtures/pimb-v1.json`). A fixture only one side can parse fails CI (`conformance.test.ts` / `ConformanceFixtureTest.kt`).

Mandatory cases:

1. Fragmented and coalesced frame headers; every kind; maximum and oversized payload lengths; invalid magic, major, flags, and UTF-8; no resynchronization scan on an authenticated stream.
2. Bounds before allocation: 1 MiB frame, 256 KiB JSON, 64 KiB binary, 128 events/256 KiB batch, raw ≤128 KiB plus escaped-envelope check, 512 frames/8 MiB queue and 10-second stall. The lower bound wins.
3. Binary stream lifecycle: `stream.open` before data, contiguous sequence and offset, duplicate chunk, overflow, digest mismatch, data after close, and cancellation.
4. Exact Pi line contract: `rawJson` UTF-8 bytes excluding LF, parsed projection, size, SHA-256; shared bounded-projector equality and raw-reference digest fetch for raw-size or escaped-envelope overflow. Parsed reserialization is never called exact.
5. Unknown types/fields remain in exact bytes and inspectable, never executed.
6. Eight-lowercase-hex/null leaf IDs; UUID rejection. Active gaps wait idle. Canonical capture stores final append-order `lastAppendId` independently from branch leaf, validates `since: lastAppendId`, tags adjuncts, retries on append/leaf change, and replays post-fence. A fixture whose active leaf moved backward behind later off-branch appends must publish without livelock.
7. Dormant recovered `RECEIVED`, current READY same-id/hash resubmit with full revalidation, non-dispatching `command.query`, and all command states.
8. Prompt-image flow plus exact 8/64/256 MiB and 32-upload quotas; 15-minute orphan, 24-hour dormant, one-hour terminal cleanup; startup sweep never deletes a live row.
9. Separate registration/assertion messages, `PAIRING_PROVISIONAL`, TLS-exporter+invitation+CSR-hash binding (exporter label `EXPORTER-Pi-Mobile-Pairing-v1`), and no mTLS claim before certificate.
10. Approval offer/decision/expired exact binding; terminal history bounds/generation/truncation.
11. Canonical RFC 8785 hashing: reorder invariant; absent fields excluded; image refs and expected eight-hex leaf included.
12. Fuzzers prove bounded allocation on hostile input.

## Pi framing and delta assembly

1. **LF-only** splitting with `U+2028` and `U+2029` inside JSON strings; a generic line reader must fail. CRLF tolerated by stripping one CR immediately before LF.
2. A Pi record over 16 MiB, malformed JSON, or EOF mid-record faults that subprocess rather than corrupting state.
3. **Byte-exact assembly**: assembled content equals `message_end.message` across interleaved thinking and text, parallel tool calls, arguments split across many deltas, empty blocks, emoji, combining marks, and right-to-left text.
4. `text_end.content`, `thinking_end.content`, and `toolcall_end.toolCall` replace provisional fields. Because RPC strips upstream `partial`, fixtures assert no provisional signature/redaction claim, then verify authoritative `message_end.message` supplies signed/redacted final blocks with no stale metadata.
5. Tool execution correlates by `toolCallId`, proven with two concurrent tools whose events interleave.
6. An unexpected transition or index, a sequence gap, or an epoch change discards every provisional block and starts snapshot recovery.
7. Lifecycle: `agent_end` with `willRetry`, then `auto_retry_*`, compaction retry, and a queued continuation, finally `agent_settled`, produces exactly one settlement and exactly one wake.

## Journal crash matrix

Implemented in `mac/host/test/journal.test.ts`.

- Kill before `RECEIVED`, after `RECEIVED`, before `ARMED`, after `ARMED` but before the first stdin byte, mid-write, and after the write but before `ACKED`.
- Recovered `RECEIVED` remains dormant across time/restart and `command.query`; neither dispatches. Only same-id/hash resubmission on a current READY, user-authenticated connection may proceed after auth, lease, leaf, blobs, classification, and approval are revalidated.
- Recovered `ARMED` becomes `INDETERMINATE` and never redispatches. A prior approval cannot be reused after recovery.
- Fake-clock retention covers 24-hour dormant expiry, 30-day payload purge, 365-day/100,000-row tombstones, capacity rejection, and concurrent sweep/submit.
- 100 duplicate/concurrent submissions of one `commandId`/hash produce **at most one** Pi line.
- Duplicate id with a different canonical hash closes with `COMMAND_ID_REUSE`.
- Journal integrity failure or lock loss rejects all mutations rather than proceeding.
- Read-only queries bypass the journal only when enumerated in the frozen schema.

## Emulator matrix

Present today: Google APIs arm64 images/AVDs for 28/29/34/36, no-Google `android-34;default;arm64-v8a` as `PiApp_API_34_AOSP_UI`, and headless `android-34;aosp_atd;arm64-v8a` as `PiApp_API_34_AOSP`; emulator `37.1.11.0`, platform-tools `37.0.1`, SDK `/opt/homebrew/share/android-commandlinetools`. `PiApp_API_29` is the floor. API 28 remains unsupported negative-only.

| Configuration | Purpose | Evidence state |
|---|---|---|
| API 29 `PiApp_API_29` | Supported `minSdk` floor, TLS 1.3, terminal engine floor | **green** — 113 instrumentation tests, `emulator-5590` |
| API 36 `PiApp_API_36` | Primary instrumentation/E2E | installed, no recorded run |
| API 34 `domonap` | Permission/FGS behavior | installed, no recorded run |
| API 28 `PiApp_API_28` | Unsupported negative | installed; negative-only |
| API 34 `PiApp_API_34_AOSP_UI` | No-Google UI/notification path | installed, no recorded run; debug-only fake auth |
| API 34 `PiApp_API_34_AOSP` | Headless no-Google transport path | installed, no recorded run; no IME/Settings/provider |
| Resizable/foldable, tablet | Responsive layout | AVD substitute; no real hinge/OEM evidence |

Before any terminal feature test, each lane reports the WebView package/version and runs the local xterm canary (shim, `WeakRef`, canvas/font, Unicode width, write/render, resize, input). Below WebView 91 or missing capability: update-required state, no terminal/network load. The xterm asset builds twice with identical hash (`npm run terminal:verify`).

Emulator limits, stated rather than glossed: graphics timing is not representative so performance numbers are indicative only; key attestation differs from hardware; the Bitwarden provider ceremony is not credibly reproducible; OEM battery policy does not exist.

Every `adb` invocation uses `-s` when another emulator/device may exist. Each AVD scenario records serial-to-AVD mapping. Unqualified `adb wait-for-device`, screenshots, logcat, install, and input commands are prohibited in parallel runs.

## Manual matrix

Unchanged from the original plan and still mostly pending; every row produces a sanitized artifact. The integration owner must personally use the app; automated UI tests do not replace that. The emulator rows now have partial automated backing (see Layers); the physical-device rows have none.

### Physical release-signed Android 14+ device (all pending — no device)

| Scenario | Pass condition |
|---|---|
| Bitwarden passkey creation and assertion, plus DAL | Ceremony completes with the third-party provider |
| Hardware-backed key and invalidation inspection | Behavior recorded without unsupported claims |
| Direct LAN and relayed remote paths | Both work; path is visible in the UI |
| Android 14+ Bitwarden plus self-hosted ntfy with Google disabled/absent | Production passkey assertion and wake both work |
| Doze and background completion and approval wakes; OEM battery behavior | Wake on next window; no duplicate; documented latency |
| Real microphone dictation through Groq | Ordered editable transcript, seams merged, never auto-sent |
| Gboard plus Bluetooth keyboard press, repeat, release in terminal mode | Exact key semantics including release |
| Device certificate and passkey revocation | Independent revocation confirmed |
| 5,000-message session scroll and reconnect | Smooth scroll within budget |

## Voice quota and economics

Implemented in `mac/host/src/voice/rate-ledger.ts` with fake-clock tests in `mac/host/test/voice.test.ts`:

- billed seconds for 0.01/8/10/12.5-second uploads are 10/10/10/12.5; every retry adds another attempt/duration reservation;
- cost is `billedSeconds / 3600 × $0.04`, including `$0.04` for 3,600 billed seconds and `$0.05` for 4,500;
- configured RPM/RPD/ASH/ASD and UTC-day/month budgets are each filled in tests, then a wall-clock rollback is denied without changing billed duration or cost;
- the persisted effective-time high-water survives restart; parallel child processes on separate SQLite connections cannot reopen a filled window after rollback;
- a forward clock jump advances and pins effective time, so a later correction cannot reopen the jumped-to window;
- Retry-After seconds/date parsing is tested, and a bounded 429 retry is charged as a second complete attempt;
- first- and middle-chunk failures each abort the stream, emit one error, and suppress queued late partial/final completions; seam tests cover punctuation, case, contractions, Unicode normalization, repeated-token ambiguity, and no overlap.

## Performance budgets

Release build with R8 and a Baseline Profile, Macrobenchmark, Pixel 7-class or newer physical 60 Hz device. These are assertions. Emulator figures are recorded separately and never used to claim a budget is met. Threshold changes need benchmark evidence and an ADR, not a test-only relaxation. **No Macrobenchmark suite exists yet (not-implemented).**

| Metric | Budget |
|---|---:|
| Cold time-to-interactive after unlock, p95 | < 1.5 s |
| Benchmark load | 10,000 events, 500 finalized messages, 100 events/s |
| Frame duration p95 | < 16.7 ms |
| Frame duration p99 | < 33.4 ms |
| Frozen frames over 700 ms | 0 |
| Android PSS | < 250 MiB |
| Retained growth after five open/reconnect cycles | < 50 MiB |
| Visible semantic delta, p95 direct | < 100 ms |
| Visible semantic delta, p95 at controlled 80 ms relay RTT | < 250 ms |
| 500-message catch-up, direct | < 2 s |
| 500-message catch-up, relayed | < 5 s |
| Terminal input-to-echo p95 at controlled 80 ms RTT | < 150 ms |
| Main-thread network, database, markdown, or image work | none |
| Mac host idle RSS, excluding Pi children | < 150 MiB |
| Relay idle RSS | < 100 MiB |
| In-memory retention | 500 finalized messages; 5,000 xterm lines only while connected; reconnect visible pane + separate bounded server history |

## Fault injection matrix

| Fault | Injection | Expected behavior |
|---|---|---|
| Transport drop mid-stream | Kill the socket | Reconnect with backoff; discard provisional state; resume or reset; no duplication |
| Sequence gap while active | Drop one event | Provisional unavailable; wait canonical idle; one entries fence; never append partial state |
| Epoch change | Restart the host | New `streamEpoch`; atomic snapshot recovery |
| Malformed or oversized frame | Corrupt a byte; exceed a bound | Deterministic stable-code close; no resync scan |
| Replayed route challenge/frame/invitation | Resend nonce/notice/frame/QR | Challenge or inner auth rejects; no rendezvous/command |
| Host crash at journal boundary | Kill each transition | RECEIVED dormant until READY resubmit/full revalidation; ARMED indeterminate; query cannot dispatch |
| Pi subprocess crash | Kill the child | Respawn, resync, user told plainly |
| Second writer on one session | Launch desktop Pi on the same session | Lease fault, visible |
| Relay/control restart or hostile relay | Restart control/data; tamper | P-256 cold reconnect/backoff; one-use rematch; tampering fails inner TLS; only public-key/revocation state persists |
| Slow consumer | Stall a reader | Flow control, then `RESOURCE_EXHAUSTED`; authoritative data never dropped |
| Push publish failure | Fail the distributor | App-open catch-up works; failure visible in Settings |
| Forged wake | Publish an arbitrary wake | No authority beyond triggering authenticated catch-up |
| Groq 429, quota/budget edge, oversize, silence, mid-chunk failure | Force headers/jitter/retry exhaustion and each durable limit | No early retry/upload past limit; specific reset/cost message; text retained; backlog stop visible; temp audio cleaned |
| Microphone permission denied | Deny at runtime | Clear explanation, no crash, composer unchanged |
| Clock skew | Skew the device clock | Challenge and certificate validation fail closed with a stable code |
| Terminal renderer kill | Kill WebView renderer | Fresh visible-pane attach; connected scrollback gone; bounded history separate; no input replay |
| Unexpected extension command | Omit manifest flag, invoke custom path | Watchdog kills/restarts/resyncs, never retries; direct side effect marked unknown; no custom-event claim |
| Unknown Pi fields | Inject unknown event/dialog | Exact raw bytes/digest retained and inspectable |

## Security tests

Existing coverage is named in [requirements-traceability.md](requirements-traceability.md). The matrix below remains the target:

- Replay/reorder rejection; P-256 route challenge cold reconnect, one-use notice, rotation overlap/revoke; passkey/device-cert independent revocation.
- Pairing matrix: QR-pinned server-auth only, mTLS forbidden before cert, CSR generated first, exporter/invitation/CSR-hash binding, first-owner registration versus later assertion, local confirm.
- Certificate matrix: invalid/missing/expired/revoked and live close.
- WebAuthn verifier: RP/origin/challenge/UV/UP/signature/credential/counter/replay.
- DAL: 200, MIME, no redirect, exact package/fingerprint, both `get_login_creds` and `handle_all_urls`, signed-APK origin cross-check, Digital Asset Links API, signer overlap-rotation drill.
- No production auth bypass, by source scan. Notification delivery has no required Firebase/FCM dependency; the Play-services Credential Manager adapter is expected for API 29–33 auth and cannot become a push dependency.
- Redaction across every sink including crash paths: prompts, Pi raw payloads, terminal bytes, audio, credentials, keys. Logs carry stable codes and opaque ids only.
- `~/.groq_key` never serialized into a frame, asserted by scanning serialized traffic; permission remains `0600`.
- Wake payload opacity and size within the distributor limit.
- Final policy patch: integrity/source drift, post-handler final args, nested AgentSession/`extensions:false`, direct RPC path, FIFO/concurrency/30+120=150-second boundary races, broker unavailable, safe turn resume. Separate fixture proves direct extension Node/fs side effects are not sandboxed.
- Prompt-image ownership/digest/orphan cleanup and no leaked temp files.
- Terminal bundle npm integrity/packed SHA/built hash, deterministic double build, Chromium-91 syntax target, source-locked clone shim, API 29/34/36 canary, too-old engine refusal, WebView isolation, and connected/history separation.
- Relay persistence/log inspection contains only route public keys/revocation and no payload, bearer secret, topic, or query string.
- Updater: metadata fail-closed parse, monotonic `versionCode`, signature pin, downgrade/replay rejection (`android/core/update/src/test/**`).

## CI

Current workflows are enumerated at the top of this document. Every commit runs node (lint/typecheck/tests/fixtures/DAL/audit), supply-chain (SBOM/license/SCA/relay-image closure), android (unit/lint/assemble ×2), android-api29 (instrumentation), terraform (fmt/validate), and go (relay). SBOMs are deterministic CycloneDX run artifacts; licenses reject unknown, forbidden, and unallowlisted values, except exact reviewed coordinates in `supply-chain/license-policy.json`. Pre-release still lacks Macrobenchmark/performance and the manual matrices. Cross-language fixture parity is a hard gate and exists.
