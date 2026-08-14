# Plan reviews and targeted feasibility spikes

Last updated: 2026-08-09
Verdict carried forward: **GO to contract freeze; NO-GO to cloud apply or 1.0 release** until the external and physical-device gates below close.

A digest of how the plan changed under review and targeted spikes. Full dispositions live in [../reviews/plan-verification.md](../reviews/plan-verification.md); decisions in [../adr/README.md](../adr/README.md). The final audit supersedes older approval, remote-attachment, pairing, recovery, custom-routing, and API-floor details below: ADRs 0012–0017 are authoritative.

## Review round 1 — two independent reviewers on the initial plan

Reviewer A examined technical feasibility and returned **no-go as written**. Reviewer B examined product and security and returned **conditional go after notification and auth correction**.

The findings that actually changed the design:

| Theme | Finding and evidence | Resolution |
|---|---|---|
| Mutation safety | Same-id bash executed twice; response cache unsafe. | Journal: recovered ARMED indeterminate; final audit adds dormant recovered RECEIVED, explicit current READY same-id/hash resubmit/full revalidation, and non-dispatching `command.query`. [ADR-0016](../adr/0016-canonical-recovery.md) |
| Streaming correctness | RPC `message_update` is delta-only; `message_end.message` is authoritative. Both reviewers flagged that a missing delta could silently corrupt a transcript. | Strict `contentIndex` assembler, end-block and full-message replacement, `toolCallId` correlation, epoch and sequence gap detection, provisional discard plus snapshot. Cross-language golden-trace gate. Research had already been corrected in commit `fe8db73`. |
| Terminal fidelity | Termux `TerminalSession` owns a local JNI PTY and lacks Kitty release encoding, so the originally proposed remote path could not deliver parity. | Termux fork rejected. Bundled xterm plus `node-pty` plus a private tmux server with split input, proven by spike 1. [ADR-0007](../adr/0007-terminal-compatibility.md) |
| Extension parity overclaim | "100% extension compatibility" was false: callback widgets and custom panels vanish in RPC, the census counted only npm packages and **omitted 5 user-local extensions**, raw ANSI appeared in structured `setStatus`, and two local extensions emitted stale-context `extension_error` at settlement. | Claim withdrawn. Exact manifest and capability harness covering packages **and** local extensions, semantic plus PTY scenarios each, an allowlisted ANSI sanitizer, and an upgrade block on drift. [capability-matrix.md](../capability-matrix.md) |
| Approval was decorative | Pi has no sandbox; ordinary hooks can mutate args or be absent in nested sessions. | Final audit: pinned Pi patch calls preload broker after all handlers in every nested session; direct host actions gate separately; hard deadline block/resume; separate approval protocol; Node/fs side effects unsandboxed. [ADR-0012](../adr/0012-final-policy-broker.md) |
| Push required Google | FCM needed external Firebase credentials and fails on no-GMS devices. | UnifiedPush with a self-hosted ntfy distributor is primary; optional FCM sits behind the same opaque-wake interface and can never block a build or release. [ADR-0006](../adr/0006-unifiedpush-ntfy.md) |
| Notification content leaked | Specific text in the payload exposes session detail to the push server and lock screen; push must never authorize anything. | Opaque bounded wake only, authenticated catch-up, detail rendered locally after unlock, three lock-screen privacy levels. A forged wake grants nothing. |
| Transport could not reach the Mac | A forward CONNECT proxy cannot rendezvous with a NATed Mac, and independent data attachments lacked host liveness/cold auth. | Final audit: standing P-256-authenticated Mac control WSS, one-use outbound data WSS after device notice, public-key/revocation-only relay state; inner TLS; direct LAN. [ADR-0013](../adr/0013-authenticated-control-data-rendezvous.md) |
| Keys could not be used as designed | A non-exportable Keychain `SecKey` cannot be handed to Node TLS. | Encrypted PKCS#8 CA and server material at mode `0600`, wrapped by a Keychain-held secret. On Android, Keystore TLS signing required `DIGEST_NONE`; SHA-256-only authorization failed. |
| Voice cost and cadence | Every sub-10 s attempt rounds to 10 billed seconds; all org windows and retries matter. | 8–12 s VAD; durable 18 RPM/1,800 RPD/6,480 ASH/25,920 ASD defaults; exact `$0.04` arithmetic; `$0.25` day/`$2` month budgets; bounded 429 retry. [ADR-0009](../adr/0009-groq-vad-chunking.md) |
| Cost and isolation | The `$5-12` YC estimate was optimistic and Terraform isolation was absent. | Exactly one dedicated `standard-v3` 2 vCPU at 20% / 2 GiB VM, ₽900-1,500 planning envelope with a hard pre-apply calculator gate, dedicated state, before-and-after proof for unrelated resources, destroy and orphan checks. [ADR-0010](../adr/0010-one-dedicated-yc-vm.md) |
| Unstable RP identity | `sslip.io` and `nip.io` are disposable and unsafe as a permanent relying party. | `sslip.io` may name relay TLS only. The passkey RP is the stable account Pages host. [ADR-0005](../adr/0005-mac-webauthn-verifier.md) |
| Unfrozen toolchain | Android dependency tuple, wrapper, JDK, and build tools were not pinned. | Stage 0 freezes wrapper/JDK21/build-tools/catalog locks, full xterm/node-pty integrity+packed hashes, deterministic Chromium-91 bundle, and API29 shim canary. |
| Premature fan-out | Stage fan-out depended on unfrozen protocol and security decisions. | Stage 0 freezes schemas, fixtures, security contracts, and the capability manifest behind a dual-review gate before any parallel work, with exclusive path ownership per lane. |

Notable **rejections** preserved: Protobuf mirroring of Pi semantics (high drift, loses unknown events); a persistent Android socket or `remoteMessaging` foreground service (unreliable and policy-hostile); an optional 15-minute periodic sync promising exact timing (false guarantee); reviewer B's proposal to ship biometric-first and passkeys later (the account Pages RP validated, so passkeys stay a 1.0 requirement with no weaker production bypass); and running Pi on the phone under Termux (moves credentials off the Mac).

## Review round 2 — the corrected plan

The same reviewers re-read the corrected plan. No unresolved architectural P0 or P1 remained. The round-2 corrections that mattered:

| Finding | Resolution |
|---|---|
| Completion still keyed off `agent_end` in one place, which would notify prematurely and repeatedly because retry, compaction retry, or a queued continuation can follow. | `agent_settled` is the sole trigger everywhere, with a test asserting no wake on `agent_end` with `willRetry`. |
| Two protocol type definitions were implied, one per language, which guarantees drift. | Schema-first `protocol/**`, identical golden fixtures replayed by both codecs, and a hard CI parity gate. |
| An unanswered approval had no defined outcome, a fail-open hazard. | Timeout, disconnect, classifier error, changed arguments, and an absent channel all deny, and the sheet says so. |
| Coalescing high-rate updates could silently drop content. | Coalescing tests assert the cadence **and** zero content loss; queue exhaustion applies flow control then closes with `RESOURCE_EXHAUSTED` rather than dropping authoritative data. |
| Traceability risked marking device-only items satisfied. | A `blocked-external` status plus an external gap register naming each substitute and its weakness. |
| Accessibility said "accessible" without criteria. | Exact criteria: 48 dp targets, verified TalkBack traversal for inbox, timeline, composer, approval, dialogs, review, and the terminal key row, polite streaming announcements, 200% font scale without truncation, and state never encoded by color alone. |
| Line-level diff commenting on phone exceeded even the surveyed prior art, which keeps it on desktop. | Deferred past v1; hunk-level actions plus open-on-desktop. |

Also rejected in round 2: mirroring the full Pi TUI as the *primary* interface. It would need a second rendering pipeline, touch-hostile Kitty key handling, and a large security surface, and it would demote the good native surfaces. Terminal mode ships as a mandatory but explicitly labeled compatibility mode instead.

## Spike 1 — Android terminal path

**Question.** Can arbitrary custom-TUI extensions work from a phone, and which component owns which part of the pipeline?

**Verdict.** Technically proven. Release acceptance still needs physical Android input evidence.

**Findings.**

- tmux consumes Pi's Kitty negotiation. Sending every key through the display client, or forcing Kitty through tmux naively, mangles key-release sequences. The fix is a **split**: output, text, paste, mouse, replies, and resize travel through the `node-pty` display client, while exact Kitty and CSI-u press, repeat, and release bytes are injected into the pane by a persistent `tmux -C` control client using serialized `send-keys -H` with acknowledgements.
- A real `space-invaders`-style custom UI with key-release state worked end to end through browser xterm, tmux, and Pi. That fixture enters the compatibility suite; it does not waive the device gate.
- Stable xterm lacks full Kitty support. Beta `6.1.0-beta.292` is pinned by full npm integrity and packed SHA-256 with no CDN. Its bundle references `structuredClone`, `WeakRef`, and guarded/selected canvas paths.
- `node-pty`'s packaged helper needed an executable-bit correction, so startup `X_OK`, packaged spawn, and signing smoke are gates.
- tmux cannot reliably carry Kitty images, so images become native cards with no parity claim.
- The API 29 AVD supplies WebView 91.0.4472.114: `WeakRef`/canvas support is adequate but `structuredClone` starts in Chromium 98. Stage 0 adds only a source-locked plain-object clone shim, builds for Chromium 91, and runs an actual API29/34/36 boot/render/Unicode/input canary. Older/missing-feature engines get update guidance, never remote code.
- A WebView terminal is a high-risk parser and bridge, so it gets local assets only, strict CSP, no network/file/content/mixed access, an exact-origin narrow channel, bounds/rates, Safe Browsing, release debugging off, and renderer recovery.

**Independent re-verification on this machine.** `@xterm/headless` 6.0.0 was installed in a scratch directory and fed SGR sequences; per-cell attributes read back correctly:

```json
[{"text":"hello world","fg":2,"bold":true,"underline":true},
 {"text":"red line2","fg":1,"bold":false,"underline":false}]
```

So a headless xterm can also be used on the Mac to produce structured styled spans where a full renderer is unnecessary. Practical note recorded because it otherwise appears as a confusing build error: `@xterm/headless` is CommonJS, so ESM consumers must default-import and destructure — a named `import { Terminal }` throws.

tmux routing was also re-verified directly on tmux 3.5a. `capture-pane -e` returns a styled snapshot:

```
bash-5.3$ printf '\033[1;32mGREEN\033[0m plain\n'
^[[1m^[[32mGREEN^[[0m plain
```

and `pipe-pane -O` streams the live byte stream including bracketed-paste toggles `\e[?2004l` and `\e[?2004h`, confirming it carries raw output rather than a cleaned rendering.

**Split routing adopted.**

| Concern | Owner |
|---|---|
| Process, pane lifecycle, resize, detach and reattach | private tmux server |
| Live output, text, paste, mouse, replies | `node-pty` display client |
| Exact Kitty and CSI-u press, repeat, release | persistent tmux control client, `send-keys -H` |
| Reconnect redraw | tmux, into a fresh xterm generation |
| Rendering | bundled xterm in a hardened WebView |
| Structured styled spans where full rendering is unneeded | `@xterm/headless` on the Mac |

**Caveat.** Pi's own tmux guidance requires `extended-keys on` with `extended-keys-format csi-u` on tmux 3.5+ for modified keys through tmux; the control-client injection path exists precisely because the naive path is unreliable.

**Still open.** Gboard and SwiftKey composition, Bluetooth modifiers, repeat and release, touch selection and mouse, control-injector load, renderer kill, and clipboard are unproven without a physical device. Contingency is a native `InputConnection` or Compose text bridge, never a Termux fork.

## Spike 2 — no-Google push

**Question.** Can completion notifications work with no Firebase project and no Play Services dependency, and what are the real constraints?

**Verdict.** UnifiedPush with a self-hosted ntfy server is the best primary path.

**Findings.**

- A `remoteMessaging` foreground service has no timeout but is semantically and policy-wrong, and a persistent Pi socket is not messaging continuity. Neither is used.
- Documentation and implementation inspection confirmed arbitrary-app distributor registration, raise-to-foreground handoff, self-hosting, and an FCM-free notification path. Full no-Google use also requires Android 14+ third-party passkey auth.
- Push must not authorize anything: a forged wake may at most trigger an authenticated mTLS and WebAuthn catch-up, and detail renders locally after unlock.
- The ntfy distributor relies on a user-visible `specialUse` foreground service, wake locks, and reconnects, so delivery is **not absolute**. Onboarding must cover distributor and battery setup, and app-open catch-up remains authoritative.
- A self-hosted ntfy server needs `auth-default-access: deny-all`, high-entropy `up*` publish scope, a persistent bounded cache so a restart does not lose queued wakes, TLS, and per-topic and per-IP rate limits.
- FCM remains the strongest documented Doze wake but needs human-provisioned Firebase credentials, so it stays an optional adapter behind the same interface and is never a release gate.

**Independent re-verification on this machine.** `org.unifiedpush.android:connector` is on Maven Central with `3.3.3` as the newest non-release-candidate version and `3.3.4-rc1` latest, so a non-rc pin is the sane default. `org.unifiedpush.android:embedded-fcm-distributor` exists at `3.1.0` and is deliberately **not** adopted, because it reintroduces the Firebase dependency this design avoids. The official connector is Java/Kotlin and `android-connector-ui` is deprecated, so the distributor picker is implemented in-app. ntfy documents a hard **4,096-byte message length limit**, with longer messages converted to attachments, which comfortably bounds an opaque wake and is asserted by test.

## Spike 3 — passkey RP and relay simplification

**Question.** Can a zero-cost static host satisfy Digital Asset Links, and does the verifier need to be in the cloud?

**Verdict.** Feasible. The final plan deliberately chose nested standard TLS over Noise.

**Findings.**

- `github.io` is a public suffix and therefore invalid as an RP; `verybigsad.github.io` is a valid RP for package `io.github.verybigsad.pimobile`. At the time of the spike the root site returned **404** and the account Pages repository did not exist.
- The Android origin and the DAL fingerprint are **different encodings** of the same dedicated release certificate, and the verifier endpoint hostname need not equal the RP ID. So GitHub Pages serves DAL only, and the Mac verifies, pinning the exact Android origin separately from the RP.
- Hand-rolled AES-GCM was rejected as unsafe. The spike recommended audited Noise with nested TLS as a fallback; the final plan **chose nested TLS 1.3 mTLS** because Android nested-TLS feasibility was already demonstrated and it avoids introducing and auditing a second shared native crypto core. WSS is rendezvous and NAT traversal only.
- API 29–33 passkeys require the Play-services Credential Manager adapter; Android 14+ can use Bitwarden as a third-party provider. Discoverable primary-login credentials and user verification remain required, with physical release evidence.
- The QR invitation must bind invitation, Mac identity, device key, expiry, and local confirmation, and be consumed atomically.
- YC API Gateway WebSockets are unsuitable and expensive for terminal and audio traffic, so a small dedicated VM is used.

**Independent re-verification on this machine.** GitHub Pages does serve the required shape: `https://simplex.chat/.well-known/assetlinks.json` returns **HTTP 200** with `server: GitHub.com` and `Content-Type: application/json; charset=utf-8`, no redirect, and parses as valid JSON with `namespace: android_app`, a package name, and multiple `sha256_cert_fingerprints`. Google's Digital Asset Links API returns statements for that host, so the file is machine-verifiable rather than merely fetchable. `https://pages.github.com/versions.json` independently confirms `application/json` for `.json` on Pages, and plain HTTP redirects to HTTPS with 301, which is fine because the requirement forbids a redirect on the HTTPS URL itself.

Counter-evidence deliberately retained: several other GitHub Pages sites return 404 for `/.well-known/assetlinks.json` and at least one returns 301. Correct behavior is therefore **not automatic** — the file must be committed at the exact path with `.nojekyll` and served without a redirect, which is why CI asserts it rather than assuming it. And `https://verybigsad.github.io/` still returns 404 today, so this is a verified pattern with an unbuilt deployment.

## Local environment facts confirmed during this round

| Item | State |
|---|---|
| Pi | 0.84.0 at `/opt/homebrew/lib/node_modules/@earendil-works/pi-coding-agent` |
| Pi packages | 8 in `~/.pi/agent/settings.json` |
| Local Pi extensions | 5: `btw/`, `macos-input-notifier.ts`, `mcp-tool-search.ts`, `self-reload.ts`, `subagent-model-policy.ts` |
| `ui.custom` source call sites | subagents 6, ask-user-question 4, MCP 3, usage 2, btw 1; bundled `/llama` separate inline TUI class |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`; platforms 28/29/34/36; build-tools `36.0.0`; cmdline-tools `latest` |
| System images | Google APIs arm64 for 28/29/34/36 plus no-Google API 34 default and AOSP ATD |
| Emulator / platform-tools | `37.1.11.0` / `37.0.1` |
| AVDs | Google APIs `PiApp_API_29`, `domonap`, `PiApp_API_36`; no-Google UI `PiApp_API_34_AOSP_UI`; headless `PiApp_API_34_AOSP`; unsupported negative `PiApp_API_28` |
| API 29 WebView | 91.0.4472.114; xterm audit requires local `structuredClone` shim/canary |
| JDK | Temurin 21.0.8 and 25.0.2; no system Gradle, so the wrapper is mandatory |
| Node / Go / Terraform | 22.23.2 / 1.26.5 / 1.5.7 |
| Groq key | `~/.groq_key`, mode `0600`, 57 bytes. **Fixed mode:** read on the Mac at request time only, never serialized into a frame |
| tmux | 3.5a; `capture-pane -e` and `pipe-pane` confirmed |
| `@xterm/headless` | 6.0.0, CommonJS, per-cell attribute readback confirmed |
| UnifiedPush connector | Maven Central; `3.3.3` release, `3.3.4-rc1` latest; `embedded-fcm-distributor` `3.1.0` available but not adopted |
| ntfy | 4,096-byte message length limit documented |
| GitHub | `gh` authenticated as `VeryBigSad`, SSH protocol |
| Yandex Cloud | `yc` installed, `default` profile active; no project resource created |

Stale claims that must not be restated: that the Android SDK or AVDs are missing, or that the Groq key is world-readable. Both are false.

## Honest gaps carried forward

| Gap | Consequence |
|---|---|
| Physical-device matrix incomplete | Pixel 8 Pro + Bitwarden registration/certificate issuance is verified; steady-state auth-to-sync, input/microphone/hardware/Doze/OEM/performance evidence remains blocked; substitutes are weaker. |
| Public DAL | Repository is deployed; root assetlinks returns 200 JSON/no redirect and Google DAL API resolves both relations. Independent fingerprint review remains. |
| Release signing evidence | Dedicated EC cert now exists outside Git with Keychain password and published DAL fingerprint; local signed APK and live DAL/API match; independent review, off-machine/Bitwarden backup, and rotation drill remain. |
| No Firebase credentials, by design | Optional adapter compile/fake tests are planned; live FCM is never a release gate. |
| No cloud resource created | The single YC VM is applied only in Stage 5, after a reviewed plan and a current cost check. |
| Sleeping or offline Mac | Pi cannot run, no fresh assertion is possible, and no wake is sent. Inherent, documented rather than mitigated. |
