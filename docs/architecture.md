# Architecture

Last updated: 2026-08-09
Status: frozen plan for Pi Mobile 1.0

Pi Mobile is a native Android control surface for Pi processes running on a paired Mac. Pi configuration, provider credentials, extension code, sessions, and `~/.groq_key` stay on the Mac. Version 1.0 includes both a native semantic client and a terminal compatibility client.

## Compatibility baseline

| Component | Frozen version |
|---|---|
| Pi | `@earendil-works/pi-coding-agent` 0.84.0, exact package integrity + reviewed final-policy patch hash |
| `pi-mcp-adapter` | 2.21.1 |
| `@juicesharp/rpiv-ask-user-question` | 2.4.0 |
| `pi-memory` | 0.4.0 |
| `pi-web-access` | 0.19.0 |
| `@tintinweb/pi-subagents` | 0.14.3 |
| `@narumitw/pi-plan-mode` | 0.49.3 |
| `@narumitw/pi-goal` | 0.49.7 |
| `@tmustier/pi-usage-extension` | 0.9.4 |

The harness regenerates package versions, integrity hashes, local extensions, commands, tools, and UI paths from the actual Pi settings. Any drift blocks the compatibility claim until semantic and terminal suites pass.

## System shape

```text
Remote:
Mac ===== standing P-256-authenticated control WSS =====> YC relay
Android -- P-256-authenticated one-use data WSS --> relay
relay -- opaque connection notice --> Mac control WSS
Android <==== paired byte splice; inner TLS 1.3 ====> Mac
           QR-pinned server auth while pairing; mTLS afterward

LAN:
Android ---------------- direct TLS 1.3 mTLS ------------ Mac

Mac host
├── project-pinned, minimally patched Pi 0.84 RPC subprocess
├── NODE_OPTIONS preload + Unix-socket final-policy client
├── strict LF framer + Pi delta assembler
├── SQLite command journal + session/event cache
├── idle-only canonical snapshot actor
├── WebAuthn verifier + pairing CA
├── Groq transcription worker
└── node-pty + private tmux server/control client
```

The Mac keeps one authenticated control WSS open with heartbeat and exponential backoff. On Android connection, the relay authenticates a P-256 challenge, notifies that control channel, and accepts one outbound, one-use Mac data WSS. It pairs those data sockets in memory and copies bytes. A signed pairing invitation is the only bootstrap for an unregistered first device. After pairing, P-256 route keys support cold reconnect; rotation publishes old and new keys for a bounded overlap before revocation.

The relay durably stores only route registration public keys, key IDs, and revocation state. It terminates outer WSS but never inner TLS; inner bytes ignore WebSocket boundaries. It sees endpoint metadata, timing, route ID, and ciphertext, with no offline data queue or session store. The direct path is one TLS connection. Path racing accepts the first fully authenticated generation and closes the loser; a command never migrates between live generations.

## Components

### Android

Kotlin, Jetpack Compose Material 3, coroutines/Flow, Room with SQLCipher, Paging, Credential Manager, Android Keystore, OkHttp, kotlinx.serialization, Coil, WorkManager, and a hardened WebView for xterm. `minSdk` is 29 because API 28 lacks platform TLS 1.3. Production application ID is `io.github.verybigsad.pimobile`.

The app owns:

- passkey UI plus separate non-exportable P-256 TLS/CSR and relay route-auth keys; API 29–33 production passkeys require the Play-services provider, while API 34+ may use Bitwarden or another compatible third-party provider;
- TLS client, reconnect, protocol framing, sequence checks, and bounded queues;
- encrypted local cache and drafts;
- session inbox, timeline, composer, review, extension dialogs, raw inspector, settings, and terminal;
- foreground audio capture and UnifiedPush receiver;
- reducers that publish coalesced UI state rather than one recomposition per token.

### Mac host

The TypeScript host runs on Node 22 LTS. Each semantic session starts an integrity-pinned Pi 0.84 CLI with the user's normal auto-discovered environment. A minimal reviewed patch adds one immutable out-of-band final-policy call after all extension `tool_call` and `user_bash` handlers. A project preload is inherited through `NODE_OPTIONS`; it registers a frozen Unix-socket broker client before Pi loads, so every nested in-process `AgentSession`—including pi-subagents and sessions created with `extensions:false`—still reaches the final hook.

```bash
NODE_OPTIONS="--require=$PI_MOBILE_POLICY_PRELOAD" \
  "$PI_MOBILE_PINNED_PI" --mode rpc --session "$PI_SESSION_FILE"
```

The patch sees final mutated arguments. The broker globally serializes offers (one active, FIFO eight), allows 30 seconds to reach the front and up to 120 seconds for a decision, while a preload monotonic cap covers the full 150 seconds from hook invocation. Denial, timeout, disconnect, overflow, or broker failure returns a deterministic block result so the turn resumes rather than hangs. The host separately gates direct RPC `bash` and destructive bridge-owned actions. This is a guardrail: arbitrary extension Node/fs/process side effects outside Pi tool/user-bash paths are not sandboxed.

The host also owns the writer lease, LF-only scanner, strict delta assembly, command journal, idle-only snapshots, WebAuthn, inner/direct TLS, Groq, and opaque wakes. Pi is not generally embedded by the bridge; the preload coverage exists because installed extensions such as pi-subagents create nested in-process sessions.

### Relay and push host

One dedicated Terraform-managed YC VM runs pinned Caddy, the Go rendezvous relay, and ntfy. Relay and ntfy share the VM to minimize cost but use separate processes, users, storage, limits, and logs. The relay persists route-auth public keys/revocation only; one-use data rendezvous stays in memory. The VM is never a Pi runtime or source of session truth.

GitHub Pages at `verybigsad.github.io` serves the root Digital Asset Links file and a static pairing landing page. The WebAuthn verifier remains on the Mac.

## Pi semantic model

Pi RPC records are strict LF-delimited JSON. Each line is retained as exact `rawJson` UTF-8 text plus digest and parsed projection. `message_update` is delta-only. The host assembles by `contentIndex`; `text_end` and `thinking_end` replace provisional content only, while `toolcall_end` replaces its tool block. RPC strips upstream partial metadata, so authoritative `message_end.message` replaces the whole provisional message and supplies signatures/redaction. Parse/size/transition/gap/epoch faults discard provisional state and force canonical resynchronization.

`agent_end` is intermediate. Only `agent_settled` means Pi has no automatic retry, compaction retry, or queued continuation; only that event creates completion state and push.

Native mode covers documented RPC commands, structured dialogs, fire-and-forget status/widget/title/editor updates, prompt-image blob references, raw events, and native equivalents for common TUI settings. Stage 0 generates an invocation-level manifest with `requiresTerminal` and side-effect class. `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and every other known custom path are routed before RPC invocation. RPC emits no detectable `custom()` event; if an unclassified extension command wedges, a watchdog kills the subprocess, restarts it, and resynchronizes while explaining the compatibility fault.

## Synchronization and ownership

A host restart creates a new `streamEpoch`; events inside it have increasing `sequence`. Android commits `(epoch, sequence, leafId)`, where a non-null Pi leaf ID is eight lowercase hex characters. Reconnect replays only a contiguous retained range; otherwise it resets and discards provisional state.

Canonical recovery is idle-only. The session actor blocks bridge mutations and waits for Pi to become idle/settled, establishes event fence `F`, and makes one canonical `get_entries` call whose entries plus leaf are the transcript snapshot for that attempt. Runtime/model/tree/command queries are cursor-tagged adjuncts, never competing transcript truth. The host records the final append-order entry as `lastAppendId` separately from the branch `leafId`, then validates with `get_entries {since: lastAppendId}` (or a repeated full query when empty); any new entry or leaf change discards/retries the attempt. A leaf is never used as the append cursor because branching can move it behind later off-branch entries. It pages the frozen result, commits `snapshot.end(F, leaf)`, then replays events after `F`. During an active-gap wait Android plainly marks canonical live data unavailable and retains drafts; it never presents incomplete provisional content as recovered truth.

Pi’s session tree is durable truth. The protocol event log and Android cache are recovery aids. Semantic and terminal processes never write one session concurrently. Mode handoff requires settled/aborted state and proven exit of the old process. A separately launched desktop Pi touching the same session is an unsupported race and faults the lease when detected.

## At-most-once semantic commands

Every state-changing semantic command has a UUIDv4 `commandId` and canonical payload hash. A single-writer SQLite journal uses WAL and `synchronous=FULL`:

```text
RECEIVED --full current validation--> ARMED -> ACKED | REJECTED
    |                                  \-> INDETERMINATE after recovery
    \-> DORMANT when recovered
```

`ARMED` commits before the first Pi stdin byte. Recovered `RECEIVED` never dispatches by itself: only same-id/same-hash `command.submit` on the current READY, user-authenticated connection may wake it, after auth, lease, expected leaf, blob, classification, and approval are all revalidated. `command.query` only observes state. Recovered `ARMED` becomes `INDETERMINATE`; a new execution then needs a new deliberate ID. Journal failure rejects mutations. This is at-most-once dispatch, not exactly-once execution.

Terminal input is ephemeral and never replayed after uncertain delivery.

## Authentication and trust boundaries

Passkeys authenticate the user; mTLS certificates authenticate a paired device. They have separate revocation.

- RP ID: `verybigsad.github.io`.
- Android origin: exact `android:apk-key-hash:${RELEASE_CERT_SHA256_BASE64URL}`, derived during the release build.
- DAL package: `io.github.verybigsad.pimobile`.
- Mac verifies RP hash, origin, challenge, UV/UP, signature, credential, expiry, and replay.
- Android device keys are non-exportable P-256 keys in Keystore.
- Mac CA and server material are encrypted PKCS#8 wrapped by a Keychain-held secret.
- TLS is 1.3 only; normal data uses checked/revoked peer certificates, while `PAIRING_PROVISIONAL` checks the QR-pinned server only.

Credential-provider support is versioned: API 29–33 needs Google Play services/Google Password Manager for production passkeys; API 34+ may use Bitwarden without making notification delivery depend on Google. With no compatible provider the app remains locked. AOSP emulator lanes use debug-only fake auth and do not prove production authentication.

Pairing starts by generating the Android P-256 key and CSR. The phone opens outer WSS, then inner TLS 1.3 with server authentication pinned by the five-minute single-use QR and enters `PAIRING_PROVISIONAL`; mTLS is not claimed or accepted yet. The first owner performs WebAuthn **registration**; later devices perform owner-credential **assertion**. The challenge is bound server-side to invitation ID, TLS exporter, CSR hash, and ceremony kind. Only then do transcript short code, explicit local Mac confirmation, invitation consumption, and certificate issuance occur. The provisional connection closes; mTLS begins on the next connection. Registration and assertion use separate protocol messages. There is no production bypass.

See [security.md](security.md).

## Terminal compatibility

Android reproducibly bundles `@xterm/xterm@6.1.0-beta.292` assets pinned by npm integrity, packed/bundle hashes, and Chromium-91 target, then serves them only via `WebViewAssetLoader`. API 29's installed WebView 91 needs the project `structuredClone` shim; a source-locator constrains it to xterm's plain mode templates. A boot canary verifies engine/features/write/render/Unicode/resize/input on API 29/34/36; failure gives update guidance and keeps terminal unavailable rather than loading remote code. The Mac starts Pi in a private tmux 3.5+ server using `node-pty@1.2.0-beta.15`. A node-pty tmux display client carries output, resize, text, paste, mouse, and terminal replies. A persistent tmux control client injects exact Kitty/CSI-u key bytes into the pane because tmux otherwise consumes negotiation and mangles release events.

While connected, xterm retains 5,000 lines. Reconnect creates a fresh xterm/display generation; tmux restores the **visible pane only**, and stale generations/unacknowledged input are discarded. A separate read-only history drawer requests a bounded server-side `tmux capture-pane` snapshot; it is not presented as restored/full xterm scrollback. Native image/artifact cards compensate for tmux’s unreliable Kitty image path.

## Mobile approval gate

A shared Mac classifier/Unix-socket broker checks host direct RPC bash and destructive bridge actions. For agent tool/user-bash paths, the pinned Pi patch invokes the preload-registered final hook **after all extension handlers**, binding the final normalized arguments to `toolCallId` or host `commandId`. The wire protocol is `approval.offer/decision/expired`, never Pi `confirm`. Allow once or Deny only; one offer is globally active with FIFO capacity eight, 30-second queue wait, up-to-120-second decision window, and a monotonic 150-second total cap from hook invocation. Expiry, overflow, disconnect, absent broker, changed args, or classifier failure blocks and resumes safely. Known extension slash commands are invocation-manifest classified; destructive/unknown side-effect handlers gate or route terminal. Arbitrary extension Node/fs side effects remain outside containment.

## Push and voice

UnifiedPush with the ntfy Android distributor is primary. The Mac posts an opaque bounded wake; Android performs authenticated catch-up and renders detailed local text only after unlock. No permanent Pi Mobile background socket exists. Optional FCM implements the same wake interface but is not a release gate.

Voice uses foreground `AudioRecord`, 16 kHz mono PCM, 20 ms frames. Mac VAD prefers 8 s, forces 12 s, and overlaps ~500 ms. One request is in flight/two queued. A durable preflight ledger enforces RPM, RPD, hourly/daily encoded-audio windows, `$0.25` daily/`$2` monthly default budgets, and 10-second minimum billing across retries. `Retry-After` or bounded jitter controls 429 retry. Final transcript is editable and never auto-sent.

## Performance and data bounds

The protocol caps JSON at 256 KiB, frame payloads at 1 MiB, binary chunks at 64 KiB, batches at 128 events/256 KiB, and each outbound queue at 512 frames/8 MiB. Android retains 500 finalized semantic messages and, only while connected, 5,000 xterm lines. Older semantic history is paged from encrypted storage; reconnect history is a separate bounded server capture.

The timeline uses stable item keys/content types, an isolated active streaming row, cached render artifacts, and frame-cadence coalescing. Release Macrobenchmarks—not debug scrolling—enforce the budgets in [plan.md](plan.md).

## Honest limitations

- A sleeping/offline Mac cannot run Pi, verify a fresh assertion, or send a completion wake.
- UnifiedPush latency depends on ntfy, OEM battery policy, force-stop state, and notification permission.
- Groq transcription is ordered chunked batch transcription, not token streaming.
- tmux does not preserve Pi terminal images reliably; images use native cards.
- Gboard/IME, hardware-key release, Bitwarden, Doze, microphone, and hardware-backed key behavior require physical-device evidence.
- Arbitrary extensions that launch Mac GUI windows or perform Node/fs/process side effects are not sandboxed; such windows cannot be reproduced on Android.
- Independently running Pi processes must not edit a bridge-owned session.
- Pi/extension upgrades invalidate the compatibility claim until the pinned harness passes.
- The required `VeryBigSad/verybigsad.github.io` repository, DAL, release signing key, and physical-device evidence are external release prerequisites.

## Decisions

See [ADR index](adr/README.md), [protocol-v1.md](protocol-v1.md), [infra-and-cost.md](infra-and-cost.md), and [plan verification](reviews/plan-verification.md).
