# Requirements traceability

Last updated: 2026-08-09

Maps each requirement in [requirements.md](requirements.md) to the stage and ownership lane from [plan.md](plan.md) that delivers it, and to the specific evidence that proves it. Evidence names are planned identifiers; none of this code exists yet.

Status vocabulary, used strictly:

- **planned** — designed and gated, no code, no evidence.
- **partial** — some evidence exists and is named; the remainder is listed.
- **satisfied** — all listed evidence exists and has been run.
- **blocked-external** — this specific evidence requires unavailable physical/account/external state; substitute and weakness named.

Nothing is satisfied by design alone. Since implementation does not exist, all executable evidence is `planned`. A row that mixes planned code with external proof must be split; `blocked-external` may not hide unimplemented work, and a substitute does not make a row `partial`.

Identity-bound evidence uses application ID `io.github.verybigsad.pimobile` and passkey RP `verybigsad.github.io`.

## Stage map

| Stage | Deliverable | Lanes | Exit criteria |
|---|---|---|---|
| 0 | Contract/security freeze: schemas, fixtures, threat tests, exact invocation manifest, pinned Pi patch/preload, build locks | 0A protocol, 0B security, 0C compatibility, 0D build | Cross-language fixtures cover exact raw lines, content-only thinking end plus signed/redacted authoritative final messages, pairing/approval/blob/dormant commands/idle snapshots; final hook runs last in nested sessions; source counts/paths match; no P0/P1; no cloud resource |
| 1 | Foundations | 1A Mac runtime, 1B Android core, 1C transport/auth, 1D local relay/infra, 1E compatibility harness | Direct/relayed echo; provisional pairing then mTLS; standing control/one-use data/P-256 cold reconnect; dormant recovery; idle canonical snapshot; API 29 floor; API 28 negative; no cloud apply |
| 2 | Semantic vertical slice | Mac RPC facade, Android UX/data, approval, compatibility | Full semantic flow; exact raw/blob image flow; known custom paths pre-route and watchdog recovers drift; final-arg nested/direct approval blocks safely including broker loss; no generic approval affordance |
| 3 | Push, voice, release identity | push, voice, identity | Push/Groq suites; durable RPM/RPD/ASH/ASD, retry and daily/monthly cost guards; live test cleanup; DAL exact package/fingerprint/origin plus both relations and signer migration |
| 4 | Mandatory terminal compatibility | terminal, compatibility | Rendering/input/custom paths pass; connected xterm keeps 5k lines; reconnect restores visible pane only; bounded capture history is separate; no replay/full-scrollback claim; never two Pi writers |
| 5 | Cloud, hardening, E2E, release | serial with parallel suites | Reviewed Terraform plan and current cost check; one dedicated VM created with unrelated resources proven unchanged; remote and direct suites pass; release Macrobenchmarks meet budgets; manual emulator and physical-device evidence recorded; SBOM, scans, signed artifacts, destroy and orphan proof; dual final review with all P0/P1 fixed |

## Requirement to evidence

| Req | Stage | Lane / module | Evidence | Status |
|---|---|---|---|---|
| R1 pairing | 1, 2 | transport/auth; settings | `CsrFirstPairingTest`, `PairingProvisionalServerAuthTest`, `RegistrationVsAssertionTest`, `ExporterInvitationCsrBindingTest`, `LocalConfirmTest`, `InvitationReplayTest` | planned |
| R1 session list fidelity | 1, 2 | `mac/host/src/sync`; `android/feature/sessions` | `SessionRegistryIntegrationTest` against real Pi session files | planned |
| R1 exact resync | 1 | sync; Android protocol | `ActiveGapUnavailableTest`, `IdleMutationFenceTest`, `SingleCanonicalEntriesTest`, `AdjunctCursorTest`, `LeafRecheckRetryTest`, `PostFenceReplayTest`, `ResyncByteIdentityTest` | planned |
| R1 reconnect durability | 1, 5 | sync; Android core | `ReconnectCycleTest` at 100 cycles | planned |
| R1 leaf change | 1 | sync | `LeafIdChangeConvergenceTest` | planned |
| R1 writer lease | 2, 4 | sync; terminal | `WriterLeaseConflictTest`, `ModeHandoffTest` | planned |
| R1 canonical recovery | 0, 1 | protocol; sync | append ID distinct from leaf, backward-branch no-livelock, concurrent append/leaf retry, post-fence replay | planned |
| R2 capability coverage | 0, 2 | 0C compatibility; `capability-matrix.md` | `CapabilityCoverageTest` fails when a protocol surface has no matrix row | planned |
| R2 exact delta assembly | 0, 1 | protocol; codecs | `DeltaAssemblyParityTest`, `ThinkingEndReplacementTest` for carried content and authoritative final signature/redaction | planned |
| R2 `toolCallId` correlation | 0, 1 | protocol; Mac runtime | `ParallelToolCorrelationTest` | planned |
| R2 LF-only framing | 0 | protocol; Mac runtime | `FramerUnicodeSeparatorTest`, `FramerCrlfTest`, `OversizeRecordFaultTest` at 16 MiB | planned |
| R2 gap and epoch discard | 0, 1 | protocol; Android core | `SequenceGapRebuildTest`, `EpochChangeRebuildTest` | planned |
| R2 raw retention/references | 0, 2 | protocol; Android UX | `ExactRawJsonDigestProjectionTest`, `UnknownTypeRetentionTest`, `RawReferenceDownloadTest` | planned |
| R2 prompt images | 0, 1, 2 | protocol; blob store; composer | `PromptImageReadyRefHashTest`, `BlobOwnershipTest`, `BlobOrphanCrashSweepTest` | planned |
| R2 steer vs follow-up | 2 | Mac RPC facade; conversation | `StreamingBehaviorSemanticsTest` | planned |
| R2 accepted vs completed | 2 | conversation | `AcceptedNotCompletedTest` | planned |
| R2 no proxied TUI commands | 2 | settings | `CommandListSourceTest` asserts the palette comes from live `get_commands` | planned |
| R3 environment fidelity | 0, 1 | 0C compatibility; 1E harness | `PiManifestIntegrityTest` comparing hosted and terminal package and extension sets | planned |
| R3 per-extension scenarios | 1, 2, 4 | compatibility | Semantic plus PTY scenario per pinned package and per local extension, generated into the matrix | planned |
| R3 UI method coverage | 0, 2 | compatibility | `EveryPi084UiMethodClassifiedTest`, structured/widget/ANSI/settlement fixtures | planned |
| R3 invocation routing | 0, 2, 4 | compatibility | `InvocationManifestCoverageTest`, mandatory `/mcp` `/usage` `/agents` `/btw` `/llama` pre-route, 6/4/3/2/1 source-count check, `UnexpectedCommandWatchdogResyncTest` | planned |
| R3 approval interception | 0, 2 | Pi patch/preload; approval | `PatchIntegrityTest`, `FinalMutatedArgsTest`, root/nested/`extensions:false` tests, direct RPC test | planned |
| R3 approval fail closed | 2 | approval broker | one global offer/FIFO eight, overflow, 30 s queue, 120 s decision, 150 s total boundaries, broker unavailable, stale hash, disconnect, sentinel, safe resume | planned |
| R3 unsandboxed extension limit | 2 | compatibility/security | `DirectNodeFsSideEffectNotSandboxedTest` plus UX copy assertion | planned |
| R3 terminal engine | 0, 4 | build; terminal | full integrity/packed/bundle hashes, deterministic build, clone-shim source locator, API 29/34/36 canary, too-old refusal | planned: API 29 WebView 91.0.4472.114 observed; app absent |
| R3 terminal reconnect/history | 4 | terminal | connected 5k lines, visible-pane reconnect, bounded/truncated separate capture drawer, no replay/full-history copy | planned |
| R3 no false approval affordance | 2 | conversation, review | `NoGenericApproveAffordanceTest` | planned |
| R4 screen states | 2, 4 | Android UX | empty/loading/error/offline/revoked, active-gap unavailable, dormant, indeterminate, broker-unreachable | planned |
| R4 layouts and process death | 2, 4 | Android app | AVD `FoldableStatePreservationTest`, `RotationRestoreTest`, `ProcessDeathRestoreTest` | planned |
| R4 physical fold transition | 5 | manual | Real foldable transition recording | blocked-external: no physical foldable; resizable AVD is weaker |
| R4 trust and path visibility | 2 | conversation | `ExecutionTargetVisibleTest` | planned |
| R4 contrast and non-color state | 4 | design system | `ThemeContrastTest`, `StateNotColorOnlyTest` | planned |
| R4 accessibility automated/manual | 4, 5 | Android features | `A11ySemanticsTest`, font/announcement tests, emulator TalkBack recording | planned |
| R4 raw inspector reachable | 2 | conversation | `RawInspectorReachableTest` | planned |
| R5 benchmark implementation | 2, 5 | benchmark; Android data | frame/startup/memory/retained-growth Macrobenchmarks and datasets | planned |
| R5 physical budget execution | 5 | manual benchmark | Release build on physical 60 Hz Pixel 7-class+ device | blocked-external: no device; emulator indicative only |
| R5 latency budgets | 5 | transport; benchmark | `SemanticDeltaLatencyBenchmark`, `CatchUpLatencyBenchmark`, `TerminalEchoLatencyBenchmark` at controlled RTT | planned |
| R5 no main-thread work | 2, 5 | Android UX | `MainThreadViolationTest` | planned |
| R5 host and relay footprint | 5 | Mac host; relay | `HostIdleMemoryTest`, `RelayIdleMemoryTest` | planned |
| R6 no bypass | 0, 3 | 0B security; identity | `NoProductionAuthBypassTest` plus a source scan | planned |
| R6 DAL test implementation | 3 | identity | Test asserts 200/JSON/no redirect/package/fingerprint, both relations, signed-APK origin, DAL API | planned |
| R6 live DAL deployment | 3, 5 | identity/manual | Public Pages result and out-of-band fingerprint review | blocked-external: repository/key absent; site 404 |
| R6 origin/RP derivation code | 0, 3 | security; identity | `AndroidOriginDerivationTest`, `RpIdPinningTest`, signer-overlap migration fixture | planned |
| R6 real signing identity | 3, 5 | release | Dedicated release cert, backup, signed APK cross-check | blocked-external: release key absent |
| R6 ceremony verification | 1, 3 | transport/auth | `WebAuthnVerifierTest` covering challenge expiry, replay, UV, UP, counter, credential, and signature | planned |
| R6 provider matrix | 0, 1 | auth/build | API 29–33 Play-services provider required; API 34+ third-party provider; provider-absent locked/setup state | planned |
| R6 Bitwarden acceptance | 5 | manual | Physical release-signed Android 14+ device evidence, including Google-disabled/absent mode | blocked-external: no device; emulator fake/GPM providers are weaker |
| R6 API 29 floor | 0, 1 | build | `MinSdkFloorBuildTest`, `PiApp_API_29`, Linux managed device, API 28 unsupported negative | planned: API 29 and negative API 28 AVDs installed; app absent |
| R7 relay/control auth/privacy | 1, 5 | transport; relay | P-256 cold reconnect/replay/rotation/revoke, one-use data, control loss/restart, DB/log privacy, heartbeat-cost tests | planned |
| R7 inner confidentiality | 1, 5 | transport/auth | provisional pinned server-auth restrictions, direct/relayed mTLS, `HostileRelayTest` | planned |
| R7 certificate lifecycle | 1 | transport/auth | `PeerCertificateMatrixTest` for invalid, missing, expired, revoked | planned |
| R7 deterministic protocol faults | 0, 1 | protocol | `FrameBoundsFaultTest`, `InvalidUtf8FaultTest`, `NoResyncScanTest` | planned |
| R7 at-most-once journal | 1 | journal | crash matrix; dormant query/current resubmit/full revalidation; prior-approval rejection; 100 duplicates; ID/hash/journal failures | planned |
| R7 key storage implementation | 1 | transport/auth; Android security | `NonExportableKeystoreKeyTest`, `WrappedPkcs8PermissionsTest` on emulator | planned |
| R7 physical key behavior | 5 | manual | hardware backing/invalidation evidence | blocked-external: emulator differs; no device |
| R7 redaction | 0, 1 | security; all modules | `LogRedactionTest`, `CrashPathRedactionTest`, `TerminalByteRedactionTest` | planned |
| R8 notification transport needs no FCM | 3 | push | FCM absent, UnifiedPush connector/fake distributor and opaque wake tests | planned |
| R8 no-Google AVD smoke | 0, 3 | build; push/E2E | `PiApp_API_34_AOSP_UI` UI/push with fake auth plus headless `PiApp_API_34_AOSP` transport | planned: both AVDs installed; app absent |
| R8 physical full no-Google smoke | 5 | manual | Android 14+ release app + Bitwarden provider + ntfy with Google disabled/absent | blocked-external: no device |
| R8 settle-only wake | 3 | Mac notifications | `WakeOnSettledOnlyTest` asserting no wake on `agent_end` with `willRetry` | planned |
| R8 opaque payload | 3 | Mac notifications | `WakePayloadOpacityTest` including the distributor size cap | planned |
| R8 forged wake grants nothing | 3 | push | `ForgedWakeNoAuthorityTest` | planned |
| R8 dedupe and catch-up | 3 | Android notifications, data | `DuplicateSettleSuppressionTest`, `AppOpenCatchUpAuthoritativeTest` | planned |
| R8 no distributor path | 3 | settings | `NoDistributorGuidanceTest` | planned |
| R8 emulator Doze simulation | 3, 5 | push/E2E | adb Doze/standby suite with explicit serial | planned |
| R8 physical Doze/OEM behavior | 5 | manual | physical soak | blocked-external: no device/OEM policy |
| R9 key containment | 3 | voice | `GroqKeyNeverSerializedTest`, permission check on `~/.groq_key` | planned |
| R9 VAD boundaries | 3 | voice | `VadPreRollTest`, `PreferredBoundaryAt8sTest`, `ForcedCutAt12sTest`, `OverlapWindowTest` | planned |
| R9 ordering and merge | 3 | voice | `OrderedEmissionTest`, `SeamMergeTest`, `OneInFlightTwoQueuedTest` | planned |
| R9 draft isolation | 3 | `android/feature/voice` | `TranscriptDraftDoesNotOverwriteTypedTextTest`, `TranscriptNotAutoSentTest`, `VoiceCancelDiscardsTest` | planned |
| R9 failure/retry matrix | 3 | voice | silence/format/network; 429 seconds/date/missing/malformed/>120 s; monotonic/jitter/three-retry cap; backlog/temp cleanup | planned |
| R9 durable rate limits | 3 | voice | concurrent pre-send reservations, restart persistence, 18 RPM/1,800 RPD/6,480 ASH/25,920 ASD exact rolling boundaries | planned |
| R9 billing/budgets | 3 | voice | encoded overlap/retry, 10 s minimum, `$0.04` formula, `$0.25` UTC-day/`$2` UTC-month reject/rollover/UI | planned |
| R9 live transcription | 3 | voice | One bounded opt-in live Groq test reading the key on Mac only; reserve limits/budget and clean temp data | planned |
| R9 real microphone | 5 | manual | Physical device dictation | blocked-external |
| R10 direct LAN operation | 1 | transport | `DirectLanPathTest` | planned |
| R10 one VM isolation | 5 | infra | fmt/validate/policy/JSON plan/unrelated before-after proof | planned |
| R10 relay state/privacy | 1, 5 | relay | only public-key/revocation persistence, no payload/private bearer, DB/log inspection, control idle cost | planned |
| R10 cost gate and destroy proof | 5 | infra | Current calculator record, budget alerts, destroy and orphan scan | planned |
| R10 lock file committed | 0, 5 | infra | `.terraform.lock.hcl` tracked; explicit `!**/.terraform.lock.hcl`; state/vars ignored | planned: ignore rule exists, lock does not |
| R11 suites exist and parity holds | 0-5 | CI | Full non-manual suite green; Kotlin and TypeScript fixture parity gate | planned |
| R12 reproducible builds | 0, 5 | build | Wrapper/catalog locks, Pi package integrity+patch hash/source locator, xterm/node-pty npm integrity + packed/bundle hashes and deterministic build, SBOM/licenses/secrets, signed artifacts | planned |
| R13 docs currency | continuous | integration | Docs, ADRs, and traceability updated in the same commit | partial: currently accurate for a planning-only repository |

## Approval gate traceability

Called out separately because Pi itself has no approval boundary; the bridge protocol and patched final hook must prove enforcement together.

| Claim | Proof |
|---|---|
| Final interception | Patch integrity/source locator; hook observes args after all handlers in root/nested/`extensions:false`; host direct RPC separately gated |
| Operation did not run | Sentinel absent before Allow and after Deny/disconnect/deadline/broker loss |
| User saw final truth | `approval.offer` exact final operation/cwd/resource/ID/hash/policy/expiry matches interceptor |
| Failure resumes safely | Overflow, 30 s queue timeout, 120 s decision expiry, 150 s cap, unreachable/malformed/stale response all block and Pi continues |
| Allow is single-use | Decision tuple consumed once; repeat prompts |
| No false assurance | No Pi `confirm` or generic approval label; direct Node/fs fixture proves and documents non-sandbox limit |

## External gap register

| Gap | Requirements | Why it cannot be closed here | Substitute and its weakness |
|---|---|---|---|
| No physical Android device | R4/R5/R6/R7/R8/R9 physical rows | No real third-party provider/hardware/OEM/representative timing | API 29/34/36 Google APIs plus API 34 default/AOSP ATD, fake auth/audio, adb Doze; weaker |
| No public account Pages repository or live DAL | R6, R10 | Requires creating and publishing `VeryBigSad/verybigsad.github.io` with `.nojekyll` and the DAL file | Pattern verified against a production GitHub Pages host serving `assetlinks.json` as `application/json` with 200 and no redirect |
| No dedicated release signing key | R6, R12 | Fingerprint/origin and signer-rotation drill require it; signer compromise is residual | None; hard prerequisite |

## Local/intentional gaps, not blocked-external

- No Firebase credentials: intentional; optional FCM is not a release gate.
- No cloud resource: intentional until Stage 5; local relay/ntfy tests remain planned.
- Sleeping/offline Mac: inherent product limitation, not missing evidence.
