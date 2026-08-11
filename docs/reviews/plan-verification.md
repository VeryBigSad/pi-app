# Corrected plan verification

Last updated: 2026-08-11
Verdict: **Stages 0–4 implemented and locally verified; NO-GO to release/cloud apply until the external, physical-device, and Stage 5 gates below close**

Two reviewers examined the initial plan, followed by PTY/push/passkey-relay spikes and two final reviews. The final-audited plan adopts every P0/P1 and sensible P2. This record preserves superseded/rejected findings rather than rewriting history.

Disposition labels:

- **Resolved:** corrected architecture/plan and assigned an executable gate.
- **Superseded:** finding valid, but a later evidenced design resolves it differently.
- **External gate:** design complete; release blocked on credential/device/external evidence.
- **Rejected:** recommendation not adopted, with reason.

## Empirical evidence retained

- A real Pi RPC probe sent identical-ID bash twice and observed two executions, proving IDs are correlation only.
- Real Pi 0.84 traces contained delta-only `message_update` records and authoritative `message_end.message`; completion probes confirmed `agent_settled` semantics.
- An isolated Compose APK built with JDK 21, Gradle 8.11.1, AGP 8.10.1, Kotlin/Compose 2.1.21, SDK 36, and minimum SDK 29. Final audit sets the floor to API 29 because API 28 lacks platform TLS 1.3.
- An API 34 emulator completed outer TLS 1.3, CONNECT byte adaptation, and inner TLS 1.3 mTLS. The final transport replaces CONNECT with WSS rendezvous but retains the proven inner TLS/Keystore mechanism. Keystore TLS signing required `DIGEST_NONE`; SHA-256-only authorization failed.
- Node 22 arm64 spawned node-pty after executable-helper correction; tmux 3.5a was present.
- The PTY spike passed truecolor, Unicode width, synchronized output, resize, reconnect/redraw, bracketed paste, SGR mouse, modified keys, and a real key-release custom UI. Android IME/hardware-key evidence was not produced.
- UnifiedPush/ntfy inspection confirmed arbitrary-app distributor registration, raise-to-foreground handoff, self-hosting, and an FCM-free notification path. Full production no-Google use additionally needs Android 14+ and a third-party passkey provider; OEM/Doze and provider evidence remain physical.
- Public-suffix and origin-library checks confirmed `verybigsad.github.io` is a valid RP, `github.io` is not, and exact Android origins can be pinned separately from RP ID. At that review the root site/DAL returned 404 and no account Pages repository existed; P0 T3-1 records the later resolution.
- Current machine confirms Android platforms/images/AVDs 28/29/34/36, build-tools 36.0.0, Node 22, and `~/.groq_key` mode `0600`. API 29/34/36 Google APIs, API 34 no-Google default UI and AOSP ATD AVDs exist; API 28 is negative-only.

## Reviewer A — technical feasibility

Original verdict: no-go as written.

### P0

| ID | Finding/evidence | Disposition |
|---|---|---|
| A-P0-1 | Pi RPC IDs correlate only; duplicate same-ID bash executed twice. Initial cache could replay a mutation after crash. | **Resolved.** SQLite `RECEIVED -> ARMED -> ACKED/REJECTED`, recovered `ARMED -> INDETERMINATE`, no automatic mutation replay, canonical payload hash, fail-closed journal, crash injection at every boundary. Claim is at-most-once dispatch, never exactly once. |
| A-P0-2 | RPC `message_update` is delta-only; `message_end.message` is authoritative. | **Resolved.** Strict `contentIndex` assembler, end-block/full-message replacements, `toolCallId` correlation, epoch/sequence gap detection, provisional discard and snapshot. Cross-language golden trace gate added. Research was already corrected in commit `fe8db73`; docs retain corrected semantics. |
| A-P0-3 | Termux `TerminalSession` owns local JNI PTY; no Kitty release encoding; proposed remote path could not deliver parity. | **Superseded by PTY spike.** Reject Termux fork. Use bundled xterm + node-pty + private tmux display/control split-input. Real key-release custom TUI path was demonstrated. Terminal remains mandatory; physical IME/hardware-key evidence still blocks release. |
| A-P0-4 | Non-exportable Keychain `SecKey` cannot be passed directly to Node TLS. | **Resolved.** Encrypted PKCS#8 CA/server material mode `0600`, wrapped by Keychain-held secret; TLS 1.3, SAN identity, revocation per handshake and active close. Android TLS key permits `DIGEST_NONE`, as spike required. |
| A-P0-5 | Plain forward CONNECT cannot rendezvous with a NATed Mac; no host attachment protocol existed. | **Superseded by final audit.** Standing authenticated Mac control WSS summons one-use data WSS; P-256 route auth and public-key/revocation persistence. See FA-P0-7/ADR-0013. |
| A-P0-6 | Production passkey lacked stable RP/signing identity; DAL values differ from Android origin. | **External gate + resolved design.** RP fixed to `verybigsad.github.io`, package fixed, Mac pins exact Android origin, production key is dedicated, DAL automation specified. Account Pages repository/DAL and release key must exist before release. |

### P1

| ID | Finding/evidence | Disposition |
|---|---|---|
| A-P1-1 | Android dependency tuple/wrapper/JDK/build-tools/NDK were not frozen. | **Resolved in Stage 0.** Wrapper/checksum, JDK 21, build-tools 36.0.0, catalog/locks. Supported API 29/34/36 AVDs installed; API 28 negative-only; Termux/NDK eliminated. |
| A-P1-2 | Avoid duplicating all Pi semantics into Protobuf; raw Pi events should remain available. | **Resolved/refined.** Protobuf rejected. Exact UTF-8 is `rawJson`+digest/reference; projection is a deterministic bounded subset, explicitly separate and non-authoritative; escaped envelope size controls inline choice. |
| A-P1-3 | `node-pty@1.1.0` npm tarball helper mode is `0644`; Node 25/native addon and Termux licensing risks. | **Resolved/gated.** Node 22 active. Exact node-pty version/fork is pinned only after executable-helper packaging smoke; xterm/node-pty/tmux licenses retained. Termux code is not used. |
| A-P1-4 | FCM was not provisioned and is best effort. | **Resolved.** UnifiedPush/ntfy is primary and Google-independent; FCM is optional behind one opaque-wake interface and missing credentials never fail normal build/release. |
| A-P1-5 | YC `$5–12` estimate was optimistic; Terraform isolation absent. | **Resolved.** One standard-v3 2×20%/2 GiB VM, ₽900–1,500 planning envelope, hard calculator gate, dedicated state/resources, before/after unrelated-resource proof, destroy/orphan checks. |
| A-P1-6 | `sslip.io`/`nip.io` are disposable and unsafe as permanent RP. | **Resolved.** `sslip.io` is relay TLS only. Passkey RP is stable account Pages `verybigsad.github.io`. |
| A-P1-7 | Voice needs complete rate/cost semantics, not RPM alone. | **Resolved/refined.** Durable pre-send 18 RPM/1,800 RPD/6,480 ASH/25,920 ASD defaults, retry accounting, exact minimum billing, `$0.25` day/`$2` month budgets, and bounded 429 backoff; one in flight/two queued. |

Reviewer A P2s were also incorporated where material: one direct LAN TLS layer, physical Keystore/Bitwarden validation, exact Pi/extension version matrix, and Node 22.

## Reviewer B — product/security

Original verdict: conditional go after notification/auth correction.

### P0

| ID | Finding/evidence | Disposition |
|---|---|---|
| B-P0-1 | Same delta-only RPC error; a missing delta could silently corrupt transcript. | **Resolved with A-P0-2.** Exact assembler and forced-gap fixture are Stage 0’s first protocol gate. |
| B-P0-2 | FCM was a human-gated dependency and unreliable/no-GMS hostile. | **Resolved.** Self-hosted ntfy as UnifiedPush distributor is primary; no Google account required. Optional FCM cannot block build or release. |
| B-P0-3 | Extension parity was overclaimed: callback widgets/custom panels fail in RPC, local extensions were omitted, ANSI leaked into structured status, settled-time extension errors exist. | **Superseded/refined by final audit.** Exact manifest/harness remains; invocation-level pre-routing is required because RPC emits no custom event. See FA-P0-4/FA-P1-3. |
| B-P0-4 | Pi has no built-in approval gate; borrowed “Approve” UI would be false security. | **Superseded by final audit.** Host gates bridge actions; pinned Pi tool hook plus resolved `executeBash` hook/global preload covers nested and normal direct RPC once. Separate approval protocol; fail closed. See FA-P0-1. |

The extension audit behind B-P0-3 found: structured fallback works for `rpiv-ask-user-question`; Plan mode’s line widgets work; subagent custom fleet/conversation UI and callback widget disappear in RPC; MCP panels and Usage custom UI disappear; five user-local extensions were absent from the initial package-only census; `get_commands` exposed 26 commands (14 extension, 12 skill); ANSI appeared in structured `setStatus`; two local extensions emitted stale-context `extension_error` at settlement. The Stage 0 harness must reproduce these exact classes and then discover the live set rather than hard-code those counts forever.

### P1

| ID | Finding/evidence | Disposition |
|---|---|---|
| B-P1-5 | Voice/user editing and cost semantics undefined; appending partials could overwrite user text. | **Resolved.** Voice occupies a separate transcription draft; ordered partials do not mutate manually typed text; final transcript is inserted editable and never auto-sent. 8–12 s cadence, all rate windows, retry accounting, and hard cost budgets are explicit. |
| B-P1-6 | PTY decision/licensing unresolved. | **Superseded by PTY spike.** Terminal is mandatory, xterm path chosen, Termux rejected, exact licenses/notices and physical evidence gate recorded. |
| B-P1-7 | Direct/relay and VM ownership unresolved; unrelated large VM existed. | **Resolved.** Opportunistic direct LAN + relay remote path; exactly one new dedicated Terraform VM; unrelated VM reuse/import/mutation forbidden. |
| B-P1-8 | No RP domain; reviewer proposed biometric-first/passkey-later. | **Partly rejected, otherwise external gate.** Account Pages RP was validated, so passkey remains a 1.0 requirement rather than follow-on. There is no weaker production biometric bypass. Pages repo/DAL/key were hard prerequisites; they now exist, while local APK digest and live DAL/API now match; independent review, backup, and rotation evidence remain. |
| B-P1-9 | No AVD/SDK and Bitwarden/Doze/voice realism. | **Corrected.** API 29/34/36 Google APIs, API 34 no-Google default/AOSP ATD, and API 28 negative AVDs exist. AOSP uses fake auth; physical provider/Doze/voice gates remain. |
| B-P1-10 | Initial stage fan-out depended on unfrozen protocol/security decisions. | **Resolved.** Stage 0 freezes schemas/fixtures/security/contracts and dual-review gate before parallel foundations. Exclusive path ownership prevents conflicting edits. |

Reviewer B P2s were adopted: trust state must be visible, accessibility has exact criteria, notifications default to opaque wake, command discovery groups/searches the real dynamic surface, and supply-chain jobs have an owner.

## Targeted loop 1 — Android PTY path

Verdict: xterm path technically proven; release acceptance still needs Android physical input evidence.

| Rank/ID | Finding/evidence | Disposition |
|---|---|---|
| P0 T1-1 | tmux consumes Kitty negotiation; naive all-input-through-display or forcing Kitty through tmux mangles release sequences. | **Resolved.** Display node-pty handles output/text/paste/mouse/replies; persistent `tmux -C` control client injects parsed exact Kitty/CSI-u bytes with serialized `send-keys -H` and acknowledgements. |
| P0 T1-2 | Real `space-invaders` custom UI key-release behavior worked end to end through browser xterm, tmux, and Pi. | **Accepted as design evidence.** Terminal remains in 1.0; exact fixture enters compatibility suite. It does not waive device gate. |
| P0 T1-3 | Gboard/SwiftKey composition, Bluetooth modifiers/repeat/release, touch selection/mouse, control injector load, renderer kill, clipboard remain unproven. | **External gate.** Instrumentation plus physical Gboard/Bluetooth tests block release. Contingency is native `InputConnection`/Compose text bridge, not a Termux fork. |
| P1 T1-4 | Stable xterm lacks full Kitty; beta/API29 engine compatibility can drift. | **Resolved/gated.** Full npm/packed/bundle hashes; Chromium-91 target; source-locked clone shim; deterministic build; API29/34/36 runtime canary; no CDN. |
| P1 T1-5 | `node-pty` helper executable bit/package signing not yet proven. | **Resolved/gated.** Startup `X_OK`, packaged-app spawn, signing/notarization smoke; exact dependency selected during Stage 0. |
| P1 T1-6 | tmux reconnect must redraw; stale xterm history is unsafe. | **Resolved/clarified.** Connected xterm keeps 5k lines; reconnect restores visible pane only; bounded server capture is a separate history drawer. No full-scrollback claim. |
| P1 T1-7 | WebView terminal is a high-risk parser/bridge. | **Resolved.** Local assets, CSP, no network/file/content/mixed access, narrow origin channel, Safe Browsing, engine refusal, bounds/rates, debugging off, renderer recovery. |
| P1 T1-8 | tmux cannot reliably carry Kitty images. | **Accepted limitation.** Native read-only image/artifact cards; no literal image-parity claim. |

## Targeted loop 2 — no-Google push

Verdict: UnifiedPush with self-hosted ntfy is the best primary path.

| Rank/ID | Finding/evidence | Disposition |
|---|---|---|
| P0 T2-1 | `remoteMessaging` FGS has no timeout but is semantically/policy wrong; persistent Pi socket is not messaging continuity. | **Resolved.** Pi Mobile does not use a permanent socket or `remoteMessaging` FGS. UnifiedPush distributor owns delivery mechanics. |
| P0 T2-2 | A Google-independent wake is required; direct socket/WorkManager alone cannot provide timely background completion. | **Resolved.** UnifiedPush connector + ntfy distributor/server primary, opaque wake, authenticated catch-up. |
| P0 T2-3 | Push must not authorize or leak content. | **Resolved.** Random bounded wake only; forged wake causes at most mTLS/WebAuthn catch-up; detail rendered locally after unlock. |
| P1 T2-4 | ntfy distributor relies on a user-visible `specialUse` FGS, wake lock/reconnect, and battery policy; delivery is not absolute. | **Accepted limitation + test gate.** Onboarding documents distributor/battery setup, force-stop/OEM behavior; Doze emulator and physical soak required; app-open catch-up is authoritative. |
| P1 T2-5 | Self-hosted ntfy needs `up*` publish policy, persistent bounded cache, TLS, limits, and binary UnifiedPush handling. | **Resolved.** One dedicated VM config and infra tests specify all items. |
| P1 T2-6 | FCM remains strongest documented Doze wake but needs human Firebase credentials. | **Resolved in design.** Optional adapter is planned for credential-free compile/fake tests; real FCM non-blocking. |
| P1 T2-7 | Own optional FGS/periodic 15-minute floor could add complexity or false guarantees. | **Rejected for core v1.** No Pi Mobile permanent/optional socket FGS in architecture. Foreground live connection plus UnifiedPush and resume/WorkManager catch-up suffice; WorkManager timing is never promised as exact. |

## Targeted loop 3 — passkey and relay simplification

Verdict: RP/Mac verifier/one-VM relay feasible; final plan intentionally selected nested standard TLS instead of Noise.

| Rank/ID | Finding/evidence | Disposition |
|---|---|---|
| P0 T3-1 | `github.io` is a public suffix; `verybigsad.github.io` is a valid RP. Root DAL was 404; repo absent. | **Resolved 2026-08-09.** Public repo + `.nojekyll`; live 200 JSON/no redirect; signed APK digest and both DAL API relations match. Independent fingerprint review remains. |
| P0 T3-2 | Android origin and DAL fingerprint are different encodings; verifier endpoint hostname need not equal RP. | **Resolved.** Build derives both from one dedicated release cert; Mac pins exact Android origin and RP; verifier stays on Mac. |
| P0 T3-3 | Hand-rolled AES-GCM is unsafe; spike recommended audited Noise and said fallback to nested TLS if cross-platform vectors fail. | **Superseded.** Custom AEAD remains rejected. Final plan chooses standard TLS 1.3 mTLS because Android nested-TLS feasibility was already demonstrated and it avoids introducing/auditing shared Rust Noise bindings. WSS is rendezvous/proxy traversal only. |
| P0 T3-4 | Naive request retry/dedup cannot claim exactly once. | **Resolved with A-P0-1.** No retry of uncertain mutation; durable fail-closed journal. |
| P1 T3-5 | Provider support differs by Android version. | **External gate + resolved config.** API 29–33 production passkeys use Play services; API 34+ may use Bitwarden. Discoverable + UV required; provider-absent stays locked; physical release-signed Bitwarden test blocks release. |
| P1 T3-6 | One-use QR must bind invitation, Mac identity, device key, expiry, local confirmation, and short code. | **Resolved.** Provisional pinned TLS + passkey + Keystore CSR + Mac confirmation + atomic invitation consumption. |
| P1 T3-7 | API Gateway WebSockets are unsuitable/expensive for terminal/audio; a regular VM is needed. | **Resolved.** One small dedicated YC VM; API Gateway rejected. |
| P1 T3-8 | `sslip.io` VM economics and identity are acceptable only outside permanent passkey RP. | **Resolved.** It may name relay TLS; RP remains GitHub Pages. Cost re-priced at apply with ₽1,500 hard gate. |

## Cross-review exit audit

| Required final decision | Evidence in docs | Status |
|---|---|---|
| Strict Pi RPC delta assembly | architecture, protocol, plan Stage 0 | Resolved |
| Exact raw UTF-8 + projection + digest protocol | protocol contract + fixture/fuzz gate | Resolved in final audit |
| Fail-closed journal | dormant RECEIVED/query + ARMED indeterminate crash matrix | Resolved in final audit |
| Standing control + one-use data rendezvous + inner TLS | architecture/security/ADR-0013 | Resolved in final audit |
| Direct LAN TLS | architecture/security/ADR | Resolved |
| Mac WebAuthn, exact RP/package/DAL | security/plan | Design resolved; external release gate |
| UnifiedPush/ntfy primary | architecture/infra/plan | Resolved; physical delivery gate |
| xterm/node-pty/private tmux split input | architecture/plan | Design proven; physical input gate |
| Real mobile approval gate | security/plan/ADR-0012 | Final post-handler patch + preload broker resolved; implementation gate |
| Groq VAD 8–12 s | architecture/protocol/plan | Resolved; live test gate |
| One dedicated YC VM | infra/plan | Resolved; apply deferred to Stage 5 |
| Environment claims | API 29 floor; API 28 negative; Google APIs vs default/AOSP lanes; Groq key `0600` | Corrected |

## Final audit — two latest plan reviews

Date: 2026-08-09. Verdict: **all review P0/P1 and sensible P2 have a design disposition; implementation/cloud/release remain NO-GO.** Earlier rows are historical where this table says superseded.

| ID | Rank | Final finding | Disposition |
|---|---|---|---|
| FA-P0-1 | P0 | Extension adapter can run before argument mutation, disappear in nested `extensions:false`, and misuse Pi confirm. | **Resolved:** pinned Pi 0.84 final tool hook plus resolved executeBash hook; `NODE_OPTIONS` frozen Unix-socket client covers nested sessions; host gates bridge actions and resolved executeBash covers normal direct RPC once; one-active/FIFO-eight 30/120/150-second block/resume contract; `approval.offer/decision/expired`; arbitrary Node/fs remains unsandboxed. ADR-0012. |
| FA-P0-2 | P0 | API 28 cannot satisfy required platform TLS 1.3. | **Resolved:** `minSdk 29`; `PiApp_API_29` installed/supported; API 28 negative-only. ADR-0015. |
| FA-P0-3 | P0 | Pairing called mTLS before a client cert and conflated owner registration with later assertion. | **Resolved:** CSR first; outer WSS + QR-pinned inner server-auth TLS; `PAIRING_PROVISIONAL`; exporter/invitation/CSR binding; separate registration/assertion; local confirm/cert; mTLS next connection; both DAL relations. ADR-0014. |
| FA-P0-4 | P0 | RPC emits no custom-UI event, so post-attempt fallback can hang. | **Resolved:** invocation-level manifest; pre-route `/mcp`, `/usage`, `/agents`, `/btw`, `/llama` and all known paths; generic watchdog kill/restart/resync; no detection claim. ADR-0017. |
| FA-P0-5 | P0 | Snapshot mixed live queries/leaves and could canonize incomplete active deltas. | **Resolved:** wait idle/settled with mutations fenced; one canonical entries+leaf response at cursor; adjunct tags; leaf verify/retry; post-fence replay; active-gap unavailable UX. ADR-0016. |
| FA-P0-6 | P0 | Recovered `RECEIVED` could auto-dispatch under stale authority. | **Resolved:** dormant until same-id/hash current READY submit and full revalidation; `command.query` observes only; recovered ARMED remains indeterminate. ADR-0016. |
| FA-P1-1 | P1 | Delta/raw/leaf/image contracts were incomplete. | **Corrected after focused verification:** text/thinking end replace carried content only; RPC strips upstream partial metadata, so signed/redacted state arrives at authoritative `message_end`. Eight-hex leaf IDs, exact `rawJson`+projection+digest, and full prompt-image ready/ref/hash/orphan flow remain. |
| FA-P0-7 | P0 | Two independent data WSS attachments did not define host reachability, cold auth, or durable relay state. | **Resolved:** standing authenticated Mac control WSS, heartbeat/backoff, P-256 challenge cold reconnect, one-use outbound data WSS, public-key/revocation-only persistence, invitation bootstrap, overlap rotation. ADR-0013. |
| FA-P1-2 | P1 | Reconnect wording implied fake full terminal scrollback. | **Resolved:** connected xterm 5k; reconnect visible pane only; separate bounded server `capture-pane` drawer with truncation. |
| FA-P1-3 | P1 | Pi source census and UI surface were wrong/incomplete. | **Resolved:** call sites 6/4/3/2/1; missing UI methods classified; bundled `/llama` inline capability; generated drift gate. |
| FA-P2-1 | P2 | Trace status, voice rationale, lockfile ignore, adb targeting, no-Google evidence, broker-loss UX, and DAL signer risk were unclear. | **Resolved:** planned/external rows split; durable all-window rate, retry, billing, and budget guards stated; explicit lock negation; serial-scoped adb; Google-APIs distinction; blocked/resumed broker copy; residual signer/Pages risk and overlap rotation. |

## Remaining release blockers

No unresolved architectural P0/P1 remains. Implementation gates from earlier revisions are now closed: codecs, the pinned Pi patch/preload/broker, relay/auth/pairing, journal/snapshot/blob suites, and the invocation/terminal matrix all exist with green unit suites, and API 29 instrumentation passes on `emulator-5590` (113 tests). These evidence gates legitimately remain:

1. Independent DAL/signing fingerprint review, release-key off-machine backup, account protection, and rotation evidence.
2. Physical Bitwarden/input/microphone/Keystore/ntfy/Doze/OEM evidence. API 34+ and no-Google AVD lanes also still lack executed runs.
3. Macrobenchmark/performance suite (R5) is not implemented; budgets are unverified assertions.
4. Reviewed Terraform plan/cost and isolated creation of the one dedicated VM, plus remote relay/ntfy E2E.
5. SBOM/licenses supply-chain job, signed release artifacts, and destroy/orphan proof.
6. Final dual review of the as-built system and fixes.

Cloud apply or 1.0 release before those gates is a no-go.
