# Requirements traceability

Last updated: 2026-08-12

Maps each requirement in [requirements.md](requirements.md) to real, runnable evidence. Evidence names are paths in this repository, not planned identifiers. Where a row has no executable evidence it says so.

Status vocabulary, used strictly:

- **verified-unit** — implemented, with green unit/contract tests at the named paths (Node vitest, JVM `testDebugUnitTest`, or Go tests).
- **verified-instrumentation** — additionally proven on-device by green Android instrumentation results. All current instrumentation evidence is from the API 29 emulator `PiApp_API_29` at serial `emulator-5590` (result XMLs under each module's `build/outputs/androidTest-results/connected/debug/`).
- **emulator-only** — passes on an emulator; explicitly weaker than physical evidence and never a substitute for a physical gate.
- **physical-gate-pending** — requires a physical device, external account state, or live ceremony that does not exist here. No emulator substitute satisfies it.
- **verified-live** — proven end-to-end 2026-08-12 against the real deployment: YC folder `pimobile`, VM 158.160.210.102 (`standard-v4a`, private YC CR + VM SA, sslip.io ACME TLS 1.3, hardened cloud-init, ntfy deny-all with `up*`-scoped ACL); client on API 29 emulator over the relay rendezvous.
- **verified-host-live** — the Mac host exercised a real external provider, but no Android client, phone microphone, relay, or phone-to-Mac E2E participated.
- **not-implemented** — no code, or code without meaningful test coverage.

General evidence anchors, not repeated per row:

- Node suites (`npm test`, 372 tests/45 files green 2026-08-11): `mac/host/test/`, `protocol/ts/test/`, `mac/approval/test/`, `mac/pi-patch/test/`, `mac/preload/test/`, `scripts/*.test.mjs`.
- JVM unit suites (545 executions green in the last recorded run — 311 debug + 234 release variant, 0 failures): `android/*/src/test/**`.
- API 29 instrumentation (113 tests, 0 failures, `emulator-5590`): `android/*/src/androidTest/**`.
- Go relay suites: `relay/**/…_test.go`, green via `go test ./...`.
- CI: `.github/workflows/ci.yml` (node, android, android-api29, api34-terminal, terraform, go), `dal.yml`, `relay-image.yml`, `secret-scan.yml`, `android-release.yml`; green history including secret scan.

## Live E2E evidence (2026-08-12)

Proven against the real YC deployment with the API 29 emulator client over the relay:

- Full pairing ceremony: signed `pimobile://pair` invitation → rendezvous exchange → device route key registration → relay-spliced data tunnel → inner provisional TLS 1.3 → passkey ceremony (debug authenticator) → short code → local Mac confirmation → device certificate issued.
- Steady state: device-data tunnel → inner mTLS → passkey assertion → `USER_AUTHENTICATED` → sync → `READY`; session list over relay ("Pi Mac · Relayed"); live `session.catalog` push; unknown-session snapshot union on resume.
- Semantic round trip: phone message → `command.submit` → fail-closed journal dispatch → real Pi RPC child → LLM response events → `message.append` (canonical projected records).
- Durable canonical log (`canonical.sqlite`): epochs/sequences survive daemon restarts; durable snapshots; respawn replay dedup.
- Signed in-place `pimobile-update` rollout path exercised repeatedly.

Honest remaining gates: multi-session sync completeness (session list may show a subset until the ack/fence loop converges; under work); timeline live-render of streamed content verified at projection layer only, full UI render evidence pending; terminal-mode E2E on device; voice dictation E2E with microphone; all physical-device gates; GHCR package public visibility (optional; runtime uses YC CR); update feed publication (needs `RELEASE_PUBLISH_TOKEN`).

## Requirement to evidence

| Req | Evidence | Status |
|---|---|---|
| R1 pairing ceremony (CSR-first, provisional server-auth, exporter binding) | `mac/host/test/security-ceremony.test.ts`, `mac/host/test/pairing-invitation.test.ts`, `mac/host/test/security-pki.test.ts`, `mac/host/test/inner-tls.test.ts`, `mac/host/test/direct-tls.test.ts`; Android: `android/core/network/src/test/.../PairingOrchestratorTest.kt`, `RelayPairingClientTest.kt`, `TlsExporterAndroidTest.kt` (instrumented); live E2E 2026-08-12 (see above) | verified-live (relay path, debug authenticator; physical provider ceremonies pending) |
| R1 session list fidelity | `mac/host/test/session-service.test.ts`, `mac/host/src/daemon/session-service.ts`; live: session list over relay, `session.catalog` push, snapshot union on resume | verified-live; multi-session completeness under work |
| R1 exact resync / idle fence / canonical snapshot | `mac/host/test/canonical-snapshot.test.ts`, `mac/host/test/gateway-sync.test.ts`, `mac/host/src/sync/canonical-snapshot.ts`; live: durable `canonical.sqlite` survives daemon restarts, replay dedup | verified-live |
| R1 reconnect durability | `mac/host/test/relay-tunnel.test.ts`, `android/core/network/src/test/.../PathAndReconnectTest.kt`; live: device-data tunnel → mTLS → assertion → `READY`; 100-cycle soak not implemented | verified-live; soak **not-implemented** |
| R1 leaf change convergence | covered within `canonical-snapshot.test.ts` fixtures | verified-unit |
| R1 writer lease / mode handoff | `mac/host/src/daemon/terminal-runtime.ts`, `mac/host/test/terminal-backend.test.ts` | verified-unit |
| R1 canonical recovery (append id vs leaf, backward branch) | `mac/host/test/canonical-snapshot.test.ts` | verified-unit |
| R1 revocation kills sessions | `mac/host/test/security-revocation.test.ts`, `mac/host/src/daemon/daemon.ts` (`devices.revoke` → cert + route-key + relay revoke) | verified-unit |
| R2 capability coverage / matrix drift gate | `protocol/catalog/`, `docs/capability-matrix.md`; generated matrix drift test **not-implemented** | partial; drift gate not-implemented |
| R2 exact delta assembly | `mac/host/test/pi-core.test.ts` (delta assembler, LF framer, raw projector), `mac/host/src/pi/delta-assembler.ts`, `lf-json-framer.ts` | verified-unit |
| R2 `toolCallId` correlation | `mac/host/test/pi-core.test.ts` | verified-unit |
| R2 LF-only framing / oversize fault | `mac/host/test/pi-core.test.ts`, `mac/host/test/gateway-framing.test.ts` | verified-unit |
| R2 gap/epoch discard | `mac/host/test/gateway-sync.test.ts`, `android/core/protocol/src/test/.../PimbFixtureTest.kt` | verified-unit |
| R2 raw retention/references | `mac/host/test/pi-core.test.ts`, `mac/host/test/host-store.test.ts`, `protocol/ts/test/pimb.test.ts` | verified-unit |
| R2 prompt images / blob flow | `mac/host/src/daemon/blob-store.ts`, `mac/host/test/daemon-composition.test.ts`; full orphan-sweep matrix partial | verified-unit (partial matrix) |
| R2 steer vs follow-up / accepted-not-completed / no proxied TUI commands | `mac/host/test/gateway-command.test.ts`, `mac/host/test/agent-tracker.test.ts`, `mac/host/test/session-service.test.ts` | verified-unit |
| R3 environment fidelity / full-dist-tree Pi integrity | `mac/pi-patch/test/patch.test.ts`, `mac/pi-patch/test/runtime.test.ts`, `mac/pi-patch/manifest/pi-0.84.0.json` | verified-unit |
| R3 per-extension scenarios (semantic + PTY per package/local extension) | harness scaffold in `mac/compatibility/`; scenario suite **not-implemented** | not-implemented |
| R3 UI method coverage | `protocol/catalog/`, `docs/capability-matrix.md` | verified-unit (catalog), full fixture coverage partial |
| R3 invocation routing / pre-route / watchdog | `mac/compatibility/src/index.ts`, `mac/host/test/session-service.test.ts` | partial verified-unit |
| R3 approval interception (patch, preload, broker) | `mac/pi-patch/test/*.ts`, `mac/preload/test/preload.test.ts`, `mac/approval/test/broker.test.ts` | verified-unit |
| R3 approval fail-closed boundaries (FIFO 8, 30/120/150 s) | `mac/approval/test/broker.test.ts`; Android deadline UI: `android/feature/session/src/test/.../ApprovalOfferDeadlineTest.kt` | verified-unit |
| R3 unsandboxed extension limit | documented in `docs/security.md`; explicit fixture **not-implemented** | not-implemented |
| R3 terminal engine (deterministic bundle, canary, shim) | `scripts/build-terminal.mjs`, `scripts/terminal-assets.test.mjs`, `android/terminal/web/asset-manifest.json`; `android/app/src/androidTest/.../TerminalCanaryTest.kt` green on API 29 emulator | verified-instrumentation (API 29 only; API 34/36 lanes unverified) |
| R3 terminal reconnect/history | `mac/host/test/terminal-history-runtime.test.ts`, `android/terminal/src/androidTest/.../TerminalRuntimeInstrumentedTest.kt` | verified-instrumentation (API 29 only) |
| R3 no false approval affordance | `android/feature/session/src/.../ApprovalOfferSheet.kt`, unit state tests | verified-unit |
| R4 screen states (empty/loading/error/offline/revoked/dormant/indeterminate) | `android/feature/session/src/test/**`, `SessionStatusErrorTest.kt`, `SessionUiStateTest.kt`; instrumentation: `SessionScreensTest.kt`, `SessionStatusSurfacesTest.kt` | verified-instrumentation (emulator-only) |
| R4 layouts / rotation / process death | `android/core/push/src/androidTest/.../ColdProcessRestoreTest.kt`; foldable/rotation matrix partial | partial; foldable **physical-gate-pending** |
| R4 trust and path visibility | `android/feature/session`, status surfaces tests | verified-unit |
| R4 contrast / non-color state / accessibility | `android/feature/session/src/androidTest/**`, `SettingsScreenComposeTest.kt`; TalkBack manual evidence **physical-gate-pending** | verified-instrumentation partial (emulator-only) |
| R4 raw inspector | session timeline tests (`TimelinePresentationTest.kt`) | verified-unit |
| R5 benchmark implementation + physical budgets | `android/benchmark/src/main/kotlin/io/github/verybigsad/pimobile/benchmark/PiMobileBenchmark.kt`; `android/app/src/main/kotlin/io/github/verybigsad/pimobile/BenchmarkTimelineHarness.kt`; `BenchmarkTimelineHarnessTest.kt`; app `baselineProfile`/ProfileInstaller wiring | verified-unit (fixture semantics); no executed Macrobenchmark / physical-gate-pending |
| R5 latency/host/relay footprint | not implemented | not-implemented |
| R6 no auth bypass | `mac/host/test/security-*.test.ts`, `android/core/security/src/test/**`; CI secret scan `secret-scan.yml` | verified-unit |
| R6 DAL | `scripts/verify-dal.mjs`, `scripts/verify-dal.test.mjs`, `.github/workflows/dal.yml`; live 200/JSON/no-redirect + both relations verified | verified-unit + live check green; independent fingerprint review **physical-gate-pending** |
| R6 origin/RP derivation | `scripts/verify-release-identity.mjs`, `mac/host/test/security-webauthn-options.test.ts`, `android/core/security/src/test/.../PasskeyPolicyTest.kt` | verified-unit |
| R6 real signing identity | `scripts/release-signing-env`, local v3-signed APK digest matches live DAL | verified-unit; off-machine backup/rotation drill **physical-gate-pending** |
| R6 ceremony verification (challenge/replay/UV/UP/counter) | `mac/host/test/security-webauthn.test.ts`, `security-webauthn-options.test.ts` | verified-unit |
| R6 provider matrix (API 29–33 Play services, 34+ third-party) | `android/core/security/src/test/.../PasskeyPolicyTest.kt` covers bounded API 34 candidate-service eligibility counting; `android/core/security/src/androidTest/.../PasskeyProviderApi29Test.kt` green on API 29 emulator; live: passkey ceremony (debug authenticator) in relay pairing E2E 2026-08-12 | verified-live (debug authenticator); API 29 Play services and API 34+ third-party provider **physical-gate-pending** |
| R6 Bitwarden acceptance | no physical device | physical-gate-pending |
| R6 API 29 floor / API 28 negative | API 29 instrumentation green (113 tests, `emulator-5590`); CI lane `android-api29` | verified-instrumentation (emulator-only) |
| R7 relay/control auth & privacy | `relay/internal/auth/proof_test.go`, `relay/internal/httpapi/*_test.go`, `relay/internal/rendezvous/rendezvous_test.go`, `mac/host/test/relay-*.test.ts`; live: rendezvous exchange, route-key registration, splice over deployed relay | verified-live |
| R7 inner confidentiality (pinned pairing, mTLS, hostile relay) | `mac/host/test/inner-tls.test.ts`, `direct-tls.test.ts`, `android/core/network/src/test/.../TlsTransportTest.kt`, `TlsHandshakeCarryoverTest.kt`; live: inner provisional TLS 1.3 then mTLS over relay splice | verified-live; hostile-relay end-to-end **not-implemented** |
| R7 certificate lifecycle | `mac/host/test/security-pki.test.ts`, `tls-material.test.ts`, `android/core/security/src/androidTest/.../DeviceCertificateStoreTest.kt` | verified-instrumentation partial |
| R7 deterministic protocol faults | `protocol/ts/test/fuzz.test.ts`, `pimb.test.ts`, `conformance.test.ts`, `android/core/protocol/src/test/**` | verified-unit |
| R7 at-most-once journal | `mac/host/test/journal.test.ts` (crash matrix, dormant recovery, duplicates); live: fail-closed journal dispatch in semantic round trip | verified-live |
| R7 key storage | `mac/host/test/security-key-storage.test.ts`, `android/core/security/src/androidTest/.../DeviceKeysTest.kt` | verified-instrumentation (emulator-only); hardware backing **physical-gate-pending** |
| R7 redaction | `mac/host/test/*` redaction assertions; terminal-byte redaction covered in terminal tests | verified-unit (partial) |
| R8 UnifiedPush, no FCM | `android/core/push/src/test/**` (7 unit suites), `android/core/push/src/androidTest/**` (4 instrumented suites, green API 29), `mac/host/test/admin-push.test.ts` | verified-instrumentation (emulator-only) |
| R8 no-Google AVD smoke | `PiApp_API_34_AOSP_UI`/`PiApp_API_34_AOSP` lanes defined; executed no-Google suite **not-implemented** | not-implemented |
| R8 physical no-Google | — | physical-gate-pending |
| R8 settle-only wake / opaque payload / forged wake | `mac/host/test/admin-push.test.ts`, `android/core/push/src/test/.../OpaqueWakePayloadTest.kt` | verified-unit |
| R8 dedupe / catch-up / no-distributor path | `WakeReceiptStoreTest.kt`, `WakeReconnectWorkerTest.kt` (instrumented), `UnifiedPushRuntimeTest.kt` | verified-instrumentation (emulator-only) |
| R8 Doze (emulator simulation / physical) | adb Doze suite **not-implemented**; physical soak **physical-gate-pending** | not-implemented / physical-gate-pending |
| R9 key containment | `mac/host/test/voice.test.ts`, `voice-gateway-runtime.test.ts`; `~/.groq_key` mode `0600` verified | verified-unit |
| R9 Android VAD boundaries / host ordering and seam merge | `android/core/voice/src/test/.../VadVoiceChunkerTest.kt`, `VoiceBoundaryTest.kt`; `mac/host/test/voice-component-e2e.test.ts` (serialized cumulative output, normalized seam cases) | verified-unit |
| R9 draft isolation / no auto-send | `android/core/voice/src/test/.../VoiceTranscriptTest.kt`, `VoiceCaptureControllerTest.kt`; instrumented `VoiceTranscriptGateInstrumentedTest.kt` | verified-instrumentation (emulator-only) |
| R9 terminal stream failure, durable rollback-safe limits, billing/budgets, bounded Retry-After | `mac/host/test/voice-component-e2e.test.ts` (first/middle failure and late completion); `mac/host/test/voice.test.ts` (persisted effective time, restart, concurrent rollback, windows/budgets, billing, Retry-After) | verified-unit |
| R9 live Mac-host transcription (opt-in) | `PI_GROQ_LIVE=1 npx vitest run mac/host/test/voice-live.test.ts`: macOS-synthesized 16 kHz mono s16le speech passed directly through `GroqTranscriber`, with keyword/duration/ledger/cleanup assertions; no Android, phone microphone, relay, or phone-to-Mac E2E | verified-host-live |
| R9 real microphone | `AndroidAudioRecordSourceTest.kt` instrumented on emulator; physical dictation | emulator-only / physical-gate-pending |
| R10 direct LAN | `mac/host/test/direct-tls.test.ts`, `android/core/network/src/test/.../TlsTransportTest.kt` | verified-unit |
| R10 one-VM isolation / cost gate / destroy proof | `infra/terraform/` (validation `max_monthly_cost_rub ≤ 1500` in `main.tf`/`variables.tf`), terraform CI job `fmt/validate`; live: applied, VM 158.160.210.102 (`standard-v4a`), hardened cloud-init, ntfy deny-all `up*` ACL | verified-live (apply); destroy proof pending |
| R10 relay state/privacy | `relay/internal/registry/registry_test.go`, `relay/internal/httpapi/*_test.go` | verified-unit |
| R10 lock file committed | `.terraform.lock.hcl` handling per `infra/terraform/` + `.gitignore` | verified (static) |
| R11 suites exist / cross-language parity | `protocol/ts/test/conformance.test.ts` + `android/core/protocol/src/test/.../ConformanceFixtureTest.kt` against shared `protocol/fixtures/pimb-v1.json`; CI runs both | verified-unit |
| R12 reproducible builds / supply chain | `scripts/terminal-assets.test.mjs` (deterministic bundle), `verify-release-identity.mjs`, `scripts/supply-chain.mjs` + tests (deterministic CycloneDX npm/Gradle/Go SBOMs, fail-closed licenses), `ci.yml` (pinned Grype SBOM and rebuilt relay-image SCA), `secret-scan.yml`, `relay-image.yml` (digest-pinned cosign-signed image) | verified-unit + CI gate |
| R12 assisted self-update | `android/core/update/src/test/**` (7 unit suites), `android/core/update/src/androidTest/**` (instrumented API 29), `scripts/generate-update-metadata.mjs` + test, ADR-0020; live: signed in-place `pimobile-update` rollout exercised repeatedly | verified-live (rollout); feed publication pending `RELEASE_PUBLISH_TOKEN` |
| R13 docs currency | this document regenerated from the tree 2026-08-12 | continuous |

## Approval gate traceability

| Claim | Proof | Status |
|---|---|---|
| Final interception | `mac/pi-patch/test/patch.test.ts`, `runtime.test.ts`; preload `mac/preload/test/preload.test.ts` | verified-unit |
| Operation did not run before allow | `mac/approval/test/broker.test.ts` (block/deny/timeout paths) | verified-unit |
| User saw final truth | `approval.offer` payload tests in `mac/approval/test/broker.test.ts`, Android `ApprovalOfferDeadlineTest.kt` | verified-unit |
| Failure resumes safely | broker timeout/overflow/disconnect tests | verified-unit |
| Allow is single-use | broker decision-consumption tests | verified-unit |
| No false assurance | no generic approve affordance; `docs/security.md` documents non-sandbox limit | verified-unit |
| Live end-to-end approval on a physical device | — | physical-gate-pending |

## External gap register

| Gap | Requirements | Why it cannot be closed here | Substitute and its weakness |
|---|---|---|---|
| No physical Android device | R4/R5/R6/R7/R8/R9 physical rows | No real third-party provider/hardware/OEM/representative timing; passkey ceremonies (API 29 Play services, API 34+ third-party), hardware-backed key, Doze/OEM battery, Gboard/Bluetooth input | API 29 emulator evidence (113 green tests + live relay E2E 2026-08-12 with debug authenticator); weaker — never claimed as physical |
| API 34+ emulator lanes unexecuted | R6/R8 and full terminal matrix | Only API 29 instrumentation has recorded green results | API 34/36 AVDs installed; runs pending |
| Release-key backup/cross-check incomplete | R6, R12 | Bitwarden is locked and physical release ceremony absent | Local mode-0600 EC keystore + Keychain + published fingerprint; not sufficient backup |
| Terminal-mode E2E on device | R3 terminal rows | Live on-device terminal run not executed | Emulator terminal canary + runtime instrumentation; API 34 terminal CI lane green |
| Voice dictation E2E with microphone | R9 | Live microphone dictation run not executed | Emulator `AndroidAudioRecordSourceTest`; host voice unit suites; host-only live synthesized-speech Groq test — none proves phone E2E |
| GHCR package public visibility | R12 (optional provenance) | Package visibility change not made; runtime uses YC CR | YC Container Registry pull path proven live |
| Update feed publication | R12 | Needs `RELEASE_PUBLISH_TOKEN` | Signed in-place `pimobile-update` rollout path proven live without published feed |

## Local/intentional gaps, not blocked-external

- No Firebase credentials: intentional; optional FCM is not a release gate.
- Sleeping/offline Mac: inherent product limitation, not missing evidence.
- Macrobenchmark harness is implemented (R5), but no measurement has run and Pixel-class physical budget evidence remains pending.
- Multi-session sync completeness: app session list may show a subset until the ack/fence loop converges — under work.
- Timeline live-render of streamed message content: verified at projection layer; full UI render evidence pending.
