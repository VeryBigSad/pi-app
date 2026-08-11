# Requirements and acceptance

Last updated: 2026-08-09
Status: frozen after initial reviews/spikes and two final audits. Dispositions: [reviews/plan-verification.md](reviews/plan-verification.md); spikes: [research/plan-spikes.md](research/plan-spikes.md).

Every criterion below is written so a reviewer can say "passed" or "failed" without interpretation. Criteria that could not fail were rewritten during review, because an unfalsifiable requirement is how overclaiming happens.

Fixed baseline: app ID `io.github.verybigsad.pimobile`, SDK `36/36/29`, Gradle/AGP/Kotlin `8.13/8.13.2/2.4.10`, JDK21/JVM17, Node `22.23.2`, Apple Silicon macOS 14+, RP `verybigsad.github.io`, protocol 1, pinned/patched Pi 0.84.0.

## R1 — Android client synchronized with a Mac

- Pairing: Android generates the P-256 CSR key first; outer WSS then QR-pinned inner server-auth TLS enters `PAIRING_PROVISIONAL`. The first owner uses WebAuthn registration; later devices assert the existing owner credential. Distinct messages bind challenge to invitation, TLS exporter, and CSR hash; local Mac confirmation precedes atomic invitation consumption/certificate issuance. mTLS starts only on a new post-certificate connection.
- The session list matches Pi's own session files for the configured working directories, including tree-structured sessions.
- **Resync is exact.** Android persists `(sessionId, streamEpoch, sequence, leafId)` after reducer commit; Pi leaf is null or eight lowercase hex, never UUID. A gap resets provisional state. Actor blocks mutations and waits idle/settled, freezes fence `F`, captures one canonical response, records final append ID separately from leaf, tags adjuncts with `F`, validates `since: lastAppendId`, retries on append/leaf change, pages it, then replays post-`F`. An active gap visibly marks canonical data unavailable. Result is byte-identical to fresh load.
- Replay storage is bounded: 10,000 events/64 MiB/session, 256 MiB global, 24 hours. A miss resets canonically. Android encrypted cache is 50,000 finalized messages/512 MiB; drafts/trust/cursors are never LRU-evicted.
- 100 consecutive reconnect cycles cause no lost, duplicated, or reordered committed event.
- A `leafId` change from a fork or branch switch on the Mac converges on the phone without restarting the app.
- Revoking the device certificate on the Mac terminates access within one handshake and shows an explicit revoked state, not a generic error.
- Semantic and terminal processes never write one session concurrently; mode handoff requires settled or aborted state and proven exit of the previous process. A separately launched desktop Pi editing a bridge-owned session faults the lease visibly.

## R2 — Pi feature coverage, with exact delta assembly

- [capability-matrix.md](capability-matrix.md) maps every RPC command group, every event type, and every extension UI method to native UI, terminal mode, or an explicitly justified omission. An unmapped surface fails the requirement.
- **Exact delta assembly.** For every fixture, text/thinking/tool end events replace the fields actually carried by RPC: `text_end.content`, `thinking_end.content`, and `toolcall_end.toolCall`. RPC omits upstream `partial`, so signature/redaction appear only when authoritative `message_end.message` replaces the full provisional message. Corpus includes changed end content, signed/redacted final blocks, interleaving, parallel tools, split arguments, empty blocks, emoji/combining, and Unicode separators.
- Tool execution correlates by `toolCallId`, never by position.
- Framing splits on LF only and strips one CR immediately before LF. A generic line reader must fail the Unicode-separator fixture. A Pi record over 16 MiB, malformed JSON, or EOF mid-record faults that subprocess rather than corrupting state.
- Any unexpected transition, index, sequence gap, or epoch change discards every provisional block and forces snapshot recovery. Appending after a gap is a failure.
- Each Pi line carries exact `rawJson` UTF-8 excluding LF, bounded deterministic projection, size, and SHA-256. Inline requires raw ≤128 KiB and final escaped frame ≤256 KiB; otherwise use verified raw reference. Raw refs are globally 512 MiB/30 days; eviction preserves digest/size and says unavailable, never substitutes projection. Unknowns stay exact and are never executed.
- Prompt images must complete upload/chunks/close/digest/`blob.ready` before command refs; payload hash covers ref fields. Cross-device/not-ready/mismatch fails before Pi; cancel/disconnect/expiry/startup sweeps orphans without deleting blobs needed by dormant commands.
- `prompt` during streaming always carries an explicit `streamingBehavior`; steering and follow-up are distinct actions in the UI because they land at different points.
- `success: true` renders as accepted, never completed. Post-acceptance failures surface from the event stream.
- Built-in TUI slash commands such as `/settings` and `/hotkeys` are absent from `get_commands` and are not proxied; the command palette is built from the live `get_commands` result and native settings cover the rest.

## R3 — Extension compatibility, including a real approval gate

- The Mac starts an integrity-pinned, minimally patched Pi 0.84 CLI with normal auto-discovery. `NODE_OPTIONS` preloads a frozen Unix-socket policy client; patched core invokes it after all extension handlers in root and nested in-process AgentSessions, including `extensions:false`. A generated manifest records Pi/patch/preload/package/local/bundled-extension integrity; drift blocks compatibility.
- Every pinned package and every local extension has both a semantic scenario and a PTY scenario with a recorded outcome, not an assumption.
- Invocation manifest records exact `requiresTerminal`, side-effect class, expected activity, and watchdog per path. `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and all known custom paths pre-route. Real source call-site audit is subagents 6, ask-user-question 4, MCP 3, usage 2, btw 1. RPC has no custom event; unexpected-command timeout kills/restarts/resyncs, never retries, marks direct side effects unknown, and does not claim detection.
- Harness covers every Pi 0.84 UI method, including `onTerminalInput`, working visibility/hidden-thinking label, autocomplete/editor getters, and fallback theme; structured fallback/widgets/ANSI/settlement errors remain fixtures.
- `extension_error` diagnostics must not be presented as task failure.
- **Real approval gate.** Host gates destructive bridge-owned actions; patched Pi gates tool args after handlers and resolved `AgentSession.executeBash`, covering normal direct RPC/interactive/programmatic bash once. `approval.offer/decision/expired`—never Pi `confirm`—binds final operation/cwd/resource/reasons/ID/hash/policy/expiry. Sentinel is absent before Allow and after Deny/disconnect/broker loss; Allow once executes once; repeat prompts; changed args deny.
- Broker concurrency is exact: one globally active offer, FIFO capacity eight across sessions/devices, 30-second maximum queue wait, then up to 120 seconds to decide. A local monotonic 150-second cap starts at hook invocation and clips the decision deadline, covering connect through response. Overflow/queue timeout blocks unseen. Missing broker, stale offer, disconnect, or classifier error also blocks and resumes the Pi turn.
- No generic "Approve" affordance may appear on steering or review controls, because that would imply enforcement that does not exist there.
- Guardrail, not sandbox: approved code runs with Mac permissions, and arbitrary extension Node/fs/process side effects can bypass tool hooks. A test demonstrates this limit and UI must state it.
- Terminal assets are local and reproducible: exact npm/package/bundle hashes, Chromium-91 build target, and only the narrow API-29 `structuredClone` compatibility shim. A source locator plus API 29/34/36 boot canary proves required globals, render/write, Unicode width, resize, input, and renderer recovery. Unsupported/failed WebView stays unavailable with update guidance; no CDN fallback.
- Terminal keeps 5,000 xterm lines only while connected. Reconnect restores visible pane only; a separately labeled bounded `capture-pane` history drawer reports truncation. No full/restored scrollback claim.

## R4 — Mobile-first UX quality

- [ux.md](ux.md) specifies empty/loading/error/offline/revoked, active-gap canonical-data-unavailable, dormant, indeterminate, and broker-unreachable states.
- Phone, tablet, and foldable layouts implemented and screenshotted, including rotation, fold transition, and process death, all preserving scroll position and drafts.
- Trust and execution state are always visible: which Mac, which repository and worktree, direct or relayed path.
- Light and dark themes both pass contrast checks; state is never encoded by color alone.
- Accessibility: content descriptions on every interactive element, 48 dp minimum targets, TalkBack traversal order verified for inbox, timeline, composer, approval, dialogs, and review, polite rather than assertive streaming announcements, and usability at 200% font scale with no truncation of actionable text.
- The raw inspector is reachable for any event, so a user is never told less than the protocol knows.

## R5 — Performance

Measured on a release build with R8 and a Baseline Profile via Macrobenchmark, on a Pixel 7-class or newer physical 60 Hz device. Budgets in [testing.md](testing.md) are assertions. Emulator numbers are indicative only and cannot satisfy this requirement. Threshold changes require benchmark evidence and an ADR, never a test-only relaxation.

- Cold time-to-interactive after unlock, frame p95 and p99, frozen-frame count, PSS ceiling and retained growth across five open/reconnect cycles, visible semantic delta latency direct and at controlled relay RTT, catch-up time, and terminal input-to-echo latency all meet the documented budgets.
- No network, database, markdown, or image work occurs on the main thread, asserted by test.
- Mac host and relay idle memory stay within the documented ceilings.

## R6 — Passkey authentication

- Production access requires an Android Credential Manager passkey with discoverable credentials and required user verification, attestation none. API 29–33 requires the Google Play services credential provider; API 34+ may use Bitwarden or another compatible third-party provider. With no provider the app remains locked. There is no production password, debug-certificate, biometric, device-certificate-only, or offline bypass, asserted by a source scan.
- RP ID is `verybigsad.github.io`. `github.io` alone is invalid because it is a public suffix.
- Pages serves DAL with HTTP 200, JSON, no redirect, `android_app`, exact package/fingerprint, and both `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`; CI cross-checks the signed APK, exact Android origin, and Digital Asset Links API.
- The Android origin is the exact `android:apk-key-hash:` form derived from the same dedicated release certificate, and the Mac pins it separately from the RP ID.
- The Mac is the verifier: it pins RP hash, origin, challenge, expiry, UV and UP, signature, credential, counter, and replay state. Five-minute single-use challenges. `auth.lock` downgrades immediately on device lock and after five continuous background minutes; an independent Mac lease enforces loss of the message/socket.
- Manual acceptance requires a release-signed build and the Bitwarden Android provider on a physical device. This cannot be automated and cannot be claimed from an emulator.
- `minSdk` is 29 because API 28 lacks platform TLS 1.3. App builds/runs on `PiApp_API_29`; `PiApp_API_28` is unsupported negative-only.

## R7 — Transport security

- TLS 1.3 only. Pairing uses QR-pinned inner server-auth TLS in `PAIRING_PROVISIONAL`; mTLS is forbidden until certificate issuance. Normal remote data crosses one-use paired WSS; LAN uses direct mTLS. Path racing accepts first authenticated generation and never migrates commands.
- First-boot route registration uses a root-only one-use token generated outside Terraform state, retrieved over SSH and erased after public-key registration. Mac control/device/Mac-data WSS use JCS/DER P-256 proofs, 30-second audience challenges, two-minute replay retention, 30/90-second ping/liveness, and 20-second notices. Relay persists only route public keys/revocation; QR bootstrap is max 2 KiB/five minutes/Mac-signed; rotation overlap is 24 hours. Cold reconnect, key overlap rotation/revoke, control loss, restart, hostile relay, cost, and database/log privacy are tested.
- Normal connections validate full certificate profile. P-256 CA validity is five years; server/device leaves are 30 days and renew with seven days remaining; server overlap is 24 hours. Invalid/missing/expired/revoked peers fail and revocation closes live connections. Provisional pairing accepts no client cert or data.
- Sequence gaps, epoch changes, malformed frames, oversized frames, and invalid UTF-8 close or fault deterministically with a stable error code; no resynchronization scan is attempted on an authenticated stream.
- **At-most-once mutations.** `ARMED` commits before Pi stdin; recovered ARMED is indeterminate. Recovered `RECEIVED` remains dormant until same-id/hash deliberate submit over current READY/user-auth connection revalidates auth/lease/leaf/blob/policy/approval. `command.query` cannot dispatch. Hash reuse mismatch/journal failure closes or rejects. Claim at-most-once dispatch, never exactly-once.
- Retention is fail-closed: dormant `RECEIVED` 24 hours; full terminal-state payloads 30 days; ID/hash/state tombstones 365 days or 100,000 rows. Prompt blobs cap at 8 MiB each/64 MiB device/256 MiB global/32 uploads; orphan 15 minutes, dormant owner 24 hours, terminal state one hour. Capacity/integrity failure rejects mutations.
- Crash injection at every state transition and stdin boundary proves at most one Pi line for 100 duplicate submissions.
- Android TLS/CSR and relay route-auth keys are separate non-exportable P-256 Keystore keys; Mac CA/server material is encrypted PKCS#8 mode `0600`, Keychain-wrapped.
- Redaction tests assert no prompt, Pi raw payload, terminal byte, audio buffer, credential, or key reaches any log sink, including crash paths.

## R8 — Completion notifications without Google services

- Delivery uses the UnifiedPush connector with the ntfy Android distributor and a self-hosted ntfy server; this notification transport has no Google dependency. A fully usable no-Google production configuration requires API 34+ plus a compatible third-party passkey provider such as Bitwarden. API 29–33 still needs Play services for authentication, not notification delivery. Optional FCM implements the same opaque-wake interface, and missing Firebase credentials never fails a build or blocks release.
- The Mac sends a wake only after a durable `agent_settled` or a blocking input request. Publishing on `agent_end` is a failure, since retry, compaction retry, or a queued continuation may follow.
- The payload is an opaque bounded wake with no session name, prompt, file name, or result, and stays within the distributor's message-size limit. Detail is fetched over the authenticated channel and rendered locally after unlock. A forged wake grants no authority beyond triggering catch-up.
- Duplicate wakes for one settlement produce one notification. App-open catch-up is authoritative.
- Delivery limits are documented honestly: Doze, OEM battery policy, force-stop, notification permission, and distributor availability. There is no delivery guarantee.
- With no distributor installed, the app explains the situation and offers in-app catch-up rather than silently failing.

## R9 — Voice input with defined semantics

- Capture is 16 kHz mono signed 16-bit PCM in 20 ms frames, foreground only. Audio travels to the Mac over the bounded protocol; the Mac calls Groq `whisper-large-v3-turbo`. `~/.groq_key` stays at mode `0600` on the Mac and is never serialized into a frame.
- Chunking: VAD keeps 300 ms pre-roll, starts on speech, prefers a boundary after 8 s of speech, forces a cut at 12 s, and overlaps about 500 ms. One request is in flight with at most two queued; responses emit strictly in order; stop flushes the final speech fragment.
- Merge is by timestamps then normalized seam matching, verified against known-overlap fixtures.
- Partials occupy a separate transcription draft and never overwrite manually typed text. The final transcript is inserted as editable text and is never auto-sent. Cancelling discards audio and leaves the composer unchanged.
- Backlog beyond 30 s stops visibly rather than buffering without bound.
- Failure paths tested: silence, oversize, unsupported format, mid-chunk network failure, permission denial, and 429 with valid seconds/date/missing/malformed/long `Retry-After`. Retry uses monotonic header delay or full-jitter exponential fallback (1 s base, 30 s cap), at most three retries after the first attempt; longer-than-120 s server delay stops rather than retries early. Each path retains already-transcribed text and cleans temporary audio.
- A restart-durable Mac ledger reserves every upload attempt before send. Conservative defaults are 18 RPM, 1,800 RPD, 6,480 encoded audio seconds/hour, and 25,920/day; explicit current organization values may override them. It counts overlap and retries. Exhaustion stops before upload and shows reset time.
- Conservative billed seconds are `sum(max(encodedDuration,10s))` for every attempt; estimated upper-bound cost is billed hours × `$0.04`. Hard defaults are `$0.25` per UTC day and `$2.00` per UTC month, explicitly configurable. UI shows attempts, encoded/billed duration, estimate, and reset without logging audio/transcript.
- Translation is not offered; turbo is transcription-only.

## R10 — Minimal, low-cost middleman

- Direct LAN operation works with no cloud resource involved.
- Exactly one new dedicated non-preemptible YC VM is created after local gates. It runs pinned Caddy, relay, and ntfy separately. Relay stores only route public keys/revocation and in-memory one-use rendezvous; control-heartbeat egress/cost and persistence privacy are measured. Reusing/importing/mutating unrelated VMs is forbidden.
- GitHub Pages serves the Digital Asset Links file at no direct fee. The Mac remains the WebAuthn verifier.
- Terraform owns dedicated state, pins providers, commits `.terraform.lock.hcl`, and `.gitignore` explicitly negates it with `!**/.terraform.lock.hcl`. State/vars never commit; apply/destroy is protected/manual.
- Pre-apply: record unrelated resource ids, confirm the state contains only project resources, review the JSON plan for any reference to unrelated compute, and stop for explicit approval if the fixed monthly estimate exceeds the approved envelope. Post-apply: prove unrelated resources unchanged. Post-destroy: prove no orphaned disk, address, snapshot, DNS record, service account, security group, or budget alert.
- `sslip.io` may name relay TLS but must never be the passkey RP.

## R11 — Tests

All layers in [testing.md](testing.md) exist and run: contract and fuzz, Pi framing, delta assembly, lifecycle, journal crash matrix, sync, transport and auth, approval sentinel, push, voice, terminal, extension matrix, Android instrumentation, E2E, fault injection, performance, supply chain, and manual. Kotlin and TypeScript fixture parity is a hard CI gate. Fixtures are sanitized and never produced by mutating `~/.pi`.

## R12 — Delivery

Reproducible builds use ADR-0019, wrapper checksum, catalogs/locks, exact Pi 0.84 integrity + patch/source locator, and pinned xterm/node-pty hashes. Mac host 1.0 is Apple Silicon macOS 14+ only; native dependencies get arm64 packaged smoke and no Intel claim. CI runs tests, secret scan, SBOM/licenses; release artifacts use the dedicated signer and private GitHub history is pushed.

## R13 — Maintainability

`README.md`, `AGENTS.md`, ADRs, architecture, protocol, security, infrastructure, capability matrix, UX, testing, and traceability stay accurate with behavior changes. Unimplemented work is `planned`; truly external evidence is split as `blocked-external`. Current facts: supported API 29/34/36 Google APIs AVDs, no-Google API 34 default UI and AOSP ATD AVDs are present; API 28 is negative-only; Groq key is `0600`.

## Constraints

- Provider credentials, `~/.pi/agent/auth.json`, and `~/.groq_key` never leave the Mac.
- No Google Play Services or Firebase dependency is required for notifications.
- Steps needing an external account or a physical device have exact runbooks and named weaker substitutes, and are never silently marked satisfied.
- No cloud resource exists before Stage 5.

## Known evidence and environment gaps

| Gap | Requirements affected | Substitute and its weakness |
|---|---|---|
| No physical Android device | R6 Bitwarden, R7 hardware key, R5 timing, R9 microphone, R8 Doze/OEM, R4 input | API 29/34/36 Google APIs plus API 34 default/AOSP ATD, fake auth/audio, adb Doze; none prove physical/provider/OEM behavior |
| Release-key backup/cross-check incomplete | R6, R12 | Dedicated mode-0600 EC keystore + Keychain exists; local signed APK matches DAL; Bitwarden/off-machine backup and rotation drill absent |
| No Firebase credentials, by design | R8 optional adapter | Planned fake-distributor tests; live FCM never a release gate |
| No cloud resource created | R10 | Local containers for relay and ntfy until Stage 5 |
| Sleeping or offline Mac | R1, R6, R8 | Nothing runs, no fresh assertion, no wake; this is inherent, not a defect |
