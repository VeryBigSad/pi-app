# Requirements traceability

Last updated: 2026-08-11

Maps each requirement in [requirements.md](requirements.md) to real, runnable evidence. Evidence names are paths in this repository, not planned identifiers. Where a row has no executable evidence it says so.

Status vocabulary, used strictly:

- **verified-unit** — implemented, with green unit/contract tests at the named paths (Node vitest, JVM `testDebugUnitTest`, or Go tests).
- **verified-instrumentation** — additionally proven on-device by green Android instrumentation results. All current instrumentation evidence is from the API 29 emulator `PiApp_API_29` at serial `emulator-5590` (result XMLs under each module's `build/outputs/androidTest-results/connected/debug/`).
- **emulator-only** — passes on an emulator; explicitly weaker than physical evidence and never a substitute for a physical gate.
- **physical-gate-pending** — requires a physical device, external account state, or live ceremony that does not exist here. No emulator substitute satisfies it.
- **not-implemented** — no code, or code without meaningful test coverage.

General evidence anchors, not repeated per row:

- Node suites (`npm test`, 372 tests/45 files green 2026-08-11): `mac/host/test/`, `protocol/ts/test/`, `mac/approval/test/`, `mac/pi-patch/test/`, `mac/preload/test/`, `scripts/*.test.mjs`.
- JVM unit suites (545 executions green in the last recorded run — 311 debug + 234 release variant, 0 failures): `android/*/src/test/**`.
- API 29 instrumentation (113 tests, 0 failures, `emulator-5590`): `android/*/src/androidTest/**`.
- Go relay suites: `relay/**/…_test.go`, green via `go test ./...`.
- CI: `.github/workflows/ci.yml` (node, android, android-api29, terraform, go), `dal.yml`, `relay-image.yml`, `secret-scan.yml`, `android-release.yml`.

## Requirement to evidence

| Req | Evidence | Status |
|---|---|---|
| R1 pairing ceremony (CSR-first, provisional server-auth, exporter binding) | `mac/host/test/security-ceremony.test.ts`, `mac/host/test/pairing-invitation.test.ts`, `mac/host/test/security-pki.test.ts`, `mac/host/test/inner-tls.test.ts`, `mac/host/test/direct-tls.test.ts`; Android: `android/core/network/src/test/.../PairingOrchestratorTest.kt`, `RelayPairingClientTest.kt`, `TlsExporterAndroidTest.kt` (instrumented) | verified-instrumentation (emulator-only for Android TLS paths) |
| R1 session list fidelity | `mac/host/test/session-service.test.ts`, `mac/host/src/daemon/session-service.ts` | verified-unit |
| R1 exact resync / idle fence / canonical snapshot | `mac/host/test/canonical-snapshot.test.ts`, `mac/host/test/gateway-sync.test.ts`, `mac/host/src/sync/canonical-snapshot.ts` | verified-unit |
| R1 reconnect durability | `mac/host/test/relay-tunnel.test.ts`, `android/core/network/src/test/.../PathAndReconnectTest.kt`; 100-cycle soak not implemented | partial verified-unit; soak **not-implemented** |
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
| R5 benchmark implementation + physical budgets | no Macrobenchmark suite in tree | not-implemented / physical-gate-pending |
| R5 latency/host/relay footprint | not implemented | not-implemented |
| R6 no auth bypass | `mac/host/test/security-*.test.ts`, `android/core/security/src/test/**`; CI secret scan `secret-scan.yml` | verified-unit |
| R6 DAL | `scripts/verify-dal.mjs`, `scripts/verify-dal.test.mjs`, `.github/workflows/dal.yml`; live 200/JSON/no-redirect + both relations verified | verified-unit + live check green; independent fingerprint review **physical-gate-pending** |
| R6 origin/RP derivation | `scripts/verify-release-identity.mjs`, `mac/host/test/security-webauthn-options.test.ts`, `android/core/security/src/test/.../PasskeyPolicyTest.kt` | verified-unit |
| R6 real signing identity | `scripts/release-signing-env`, local v3-signed APK digest matches live DAL | verified-unit; off-machine backup/rotation drill **physical-gate-pending** |
| R6 ceremony verification (challenge/replay/UV/UP/counter) | `mac/host/test/security-webauthn.test.ts`, `security-webauthn-options.test.ts` | verified-unit |
| R6 provider matrix (API 29–33 Play services, 34+ third-party) | `android/core/security/src/androidTest/.../PasskeyProviderApi29Test.kt` green on API 29 emulator | verified-instrumentation (API 29 only; API 34+ third-party provider **physical-gate-pending**) |
| R6 Bitwarden acceptance | no physical device | physical-gate-pending |
| R6 API 29 floor / API 28 negative | API 29 instrumentation green (113 tests, `emulator-5590`); CI lane `android-api29` | verified-instrumentation (emulator-only) |
| R7 relay/control auth & privacy | `relay/internal/auth/proof_test.go`, `relay/internal/httpapi/*_test.go`, `relay/internal/rendezvous/rendezvous_test.go`, `mac/host/test/relay-*.test.ts` | verified-unit (cloud deploy not applied) |
| R7 inner confidentiality (pinned pairing, mTLS, hostile relay) | `mac/host/test/inner-tls.test.ts`, `direct-tls.test.ts`, `android/core/network/src/test/.../TlsTransportTest.kt`, `TlsHandshakeCarryoverTest.kt` | verified-unit; hostile-relay end-to-end **not-implemented** |
| R7 certificate lifecycle | `mac/host/test/security-pki.test.ts`, `tls-material.test.ts`, `android/core/security/src/androidTest/.../DeviceCertificateStoreTest.kt` | verified-instrumentation partial |
| R7 deterministic protocol faults | `protocol/ts/test/fuzz.test.ts`, `pimb.test.ts`, `conformance.test.ts`, `android/core/protocol/src/test/**` | verified-unit |
| R7 at-most-once journal | `mac/host/test/journal.test.ts` (crash matrix, dormant recovery, duplicates) | verified-unit |
| R7 key storage | `mac/host/test/security-key-storage.test.ts`, `android/core/security/src/androidTest/.../DeviceKeysTest.kt` | verified-instrumentation (emulator-only); hardware backing **physical-gate-pending** |
| R7 redaction | `mac/host/test/*` redaction assertions; terminal-byte redaction covered in terminal tests | verified-unit (partial) |
| R8 UnifiedPush, no FCM | `android/core/push/src/test/**` (7 unit suites), `android/core/push/src/androidTest/**` (4 instrumented suites, green API 29), `mac/host/test/admin-push.test.ts` | verified-instrumentation (emulator-only) |
| R8 no-Google AVD smoke | `PiApp_API_34_AOSP_UI`/`PiApp_API_34_AOSP` lanes defined; executed no-Google suite **not-implemented** | not-implemented |
| R8 physical no-Google | — | physical-gate-pending |
| R8 settle-only wake / opaque payload / forged wake | `mac/host/test/admin-push.test.ts`, `android/core/push/src/test/.../OpaqueWakePayloadTest.kt` | verified-unit |
| R8 dedupe / catch-up / no-distributor path | `WakeReceiptStoreTest.kt`, `WakeReconnectWorkerTest.kt` (instrumented), `UnifiedPushRuntimeTest.kt` | verified-instrumentation (emulator-only) |
| R8 Doze (emulator simulation / physical) | adb Doze suite **not-implemented**; physical soak **physical-gate-pending** | not-implemented / physical-gate-pending |
| R9 key containment | `mac/host/test/voice.test.ts`, `voice-gateway-runtime.test.ts`; `~/.groq_key` mode `0600` verified | verified-unit |
| R9 VAD boundaries / ordering / merge | `mac/host/test/voice.test.ts`, `android/core/voice/src/test/.../VadVoiceChunkerTest.kt`, `VoiceBoundaryTest.kt` | verified-unit |
| R9 draft isolation / no auto-send | `android/core/voice/src/test/.../VoiceTranscriptTest.kt`, `VoiceCaptureControllerTest.kt`; instrumented `VoiceTranscriptGateInstrumentedTest.kt` | verified-instrumentation (emulator-only) |
| R9 failure/retry matrix, durable rate limits, billing/budgets | `mac/host/test/voice.test.ts` (rate ledger, 429, budgets) | verified-unit |
| R9 live transcription (opt-in) | not run here | not-implemented (external) |
| R9 real microphone | `AndroidAudioRecordSourceTest.kt` instrumented on emulator; physical dictation | emulator-only / physical-gate-pending |
| R10 direct LAN | `mac/host/test/direct-tls.test.ts`, `android/core/network/src/test/.../TlsTransportTest.kt` | verified-unit |
| R10 one-VM isolation / cost gate / destroy proof | `infra/terraform/` (validation `max_monthly_cost_rub ≤ 1500` in `main.tf`/`variables.tf`), terraform CI job `fmt/validate`; no apply yet, so no before/after or destroy proof | partial; apply evidence not-implemented |
| R10 relay state/privacy | `relay/internal/registry/registry_test.go`, `relay/internal/httpapi/*_test.go` | verified-unit |
| R10 lock file committed | `.terraform.lock.hcl` handling per `infra/terraform/` + `.gitignore` | verified (static) |
| R11 suites exist / cross-language parity | `protocol/ts/test/conformance.test.ts` + `android/core/protocol/src/test/.../ConformanceFixtureTest.kt` against shared `protocol/fixtures/pimb-v1.json`; CI runs both | verified-unit |
| R12 reproducible builds / supply chain | `scripts/terminal-assets.test.mjs` (deterministic bundle), `verify-release-identity.mjs`, `secret-scan.yml`, `relay-image.yml` (digest-pinned cosign-signed image), SBOM job partial | partial verified-unit |
| R12 assisted self-update | `android/core/update/src/test/**` (7 unit suites), `android/core/update/src/androidTest/**` (instrumented API 29), `scripts/generate-update-metadata.mjs` + test, ADR-0020 | verified-instrumentation (emulator-only); live rollout **not-implemented** |
| R13 docs currency | this document regenerated from the tree 2026-08-11 | continuous |

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
| No physical Android device | R4/R5/R6/R7/R8/R9 physical rows | No real third-party provider/hardware/OEM/representative timing | API 29 emulator evidence (113 green tests, `emulator-5590`); weaker — never claimed as physical |
| API 34+ emulator lanes unexecuted | R6/R8 and full terminal matrix | Only API 29 instrumentation has recorded green results | API 34/36 AVDs installed; runs pending |
| Release-key backup/cross-check incomplete | R6, R12 | Bitwarden is locked and physical release ceremony absent | Local mode-0600 EC keystore + Keychain + published fingerprint; not sufficient backup |
| No cloud resource created | R8 remote delivery, R10 | Intentional until Stage 5; relay/ntfy remote path unproven | Local Go relay tests only |

## Local/intentional gaps, not blocked-external

- No Firebase credentials: intentional; optional FCM is not a release gate.
- Sleeping/offline Mac: inherent product limitation, not missing evidence.
- Macrobenchmark/performance suite not yet implemented (R5) — this is missing work, not an external block.
