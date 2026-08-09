# Testing strategy

Last updated: 2026-08-09

Rule that shapes everything below: **tests assert semantics, not shape.** A test that checks a field exists is not a test. Fault paths are as mandatory as happy paths, because every interesting failure here is a fault path: a dropped socket, a crash between journal states, a coalesced update, a denied approval, a chunk that returned out of order, a tmux pane that mangled a key release.

Fixtures are sanitized, checked in, and never produced by mutating `~/.pi`.

## Layers

| Layer | Runs on | Scope | Gate |
|---|---|---|---|
| Contract and fuzz | CI, both languages | Identical golden fixtures for the framed protocol; allocation bounds | Every commit |
| Pi framing and assembly | CI | LF scanner, oversized records, exact delta assembly | Every commit |
| Unit | CI | Framing/reducers/journal/VAD/merge/redaction, P-256 route challenges/one-use notices | Every commit |
| Integration | CI and local | Mac host against a real `pi --mode rpc` process | Every commit |
| Journal crash matrix | CI | Kill at every transition and stdin boundary | Every commit |
| Android instrumentation | Emulator | Compose behavior, navigation, accessibility semantics | Every commit |
| Transport and auth | CI and emulator | Direct TLS, TLS over WSS, certificate lifecycle, WebAuthn verifier | Pre-merge |
| E2E | Emulator plus local host and relay | Phone-to-Mac scenarios over the real transport | Pre-merge |
| Fault injection | CI and emulator | Disconnects, gaps, epochs, crashes, malformed frames, clock skew | Pre-merge |
| Terminal | Emulator plus real PTY | Rendering, keys, resize, reconnect, isolation | Pre-merge |
| Security | CI | Replay, revocation, redaction, DAL, opacity, dependency scan | Pre-merge |
| Supply chain | CI | Locks, checksums, SBOM, SCA, licenses, secret scan, signed package smoke | Pre-release |
| Performance | Physical device; emulator indicative only | Release-build Macrobenchmark against the budgets below | Pre-release |
| Manual | Emulator and physical device | The matrices below, with sanitized artifacts | Pre-release |

## Contract corpus

Kotlin and TypeScript codecs must consume **identical** checked-in fixtures. A fixture only one side can parse fails CI.

Mandatory cases:

1. Fragmented and coalesced frame headers; every kind; maximum and oversized payload lengths; invalid magic, major, flags, and UTF-8; no resynchronization scan on an authenticated stream.
2. Bounds before allocation: 1 MiB frame, 256 KiB JSON, 64 KiB binary, 128 events/256 KiB batch, raw ≤128 KiB plus escaped-envelope check, 512 frames/8 MiB queue and 10-second stall. The lower bound wins.
3. Binary stream lifecycle: `stream.open` before data, contiguous sequence and offset, duplicate chunk, overflow, digest mismatch, data after close, and cancellation.
4. Exact Pi line contract: `rawJson` UTF-8 bytes excluding LF, parsed projection, size, SHA-256; shared bounded-projector equality and raw-reference digest fetch for raw-size or escaped-envelope overflow. Parsed reserialization is never called exact.
5. Unknown types/fields remain in exact bytes and inspectable, never executed.
6. Eight-lowercase-hex/null leaf IDs; UUID rejection. Active gaps wait idle. Canonical capture stores final append-order `lastAppendId` independently from branch leaf, validates `since: lastAppendId`, tags adjuncts, retries on append/leaf change, and replays post-fence. A fixture whose active leaf moved backward behind later off-branch appends must publish without livelock.
7. Dormant recovered `RECEIVED`, current READY same-id/hash resubmit with full revalidation, non-dispatching `command.query`, and all command states.
8. Prompt-image flow plus exact 8/64/256 MiB and 32-upload quotas; 15-minute orphan, 24-hour dormant, one-hour terminal cleanup; startup sweep never deletes a live row.
9. Separate registration/assertion messages, `PAIRING_PROVISIONAL`, TLS-exporter+invitation+CSR-hash binding, and no mTLS claim before certificate.
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

The journal is the reason a mobile client can be trusted with mutations, so it gets its own matrix.

- Kill before `RECEIVED`, after `RECEIVED`, before `ARMED`, after `ARMED` but before the first stdin byte, mid-write, and after the write but before `ACKED`.
- Recovered `RECEIVED` remains dormant across time/restart and `command.query`; neither dispatches. Only same-id/hash resubmission on a current READY, user-authenticated connection may proceed after auth, lease, leaf, blobs, classification, and approval are revalidated.
- Recovered `ARMED` becomes `INDETERMINATE` and never redispatches. A prior approval cannot be reused after recovery.
- Fake-clock retention covers 24-hour dormant expiry, 30-day payload purge, 365-day/100,000-row tombstones, capacity rejection, and concurrent sweep/submit.
- 100 duplicate/concurrent submissions of one `commandId`/hash produce **at most one** Pi line.
- Duplicate id with a different canonical hash closes with `COMMAND_ID_REUSE`.
- Journal integrity failure or lock loss rejects all mutations rather than proceeding.
- Read-only queries bypass the journal only when enumerated in the frozen schema.

## Integration against real Pi

- Environment fidelity: the hosted process reports the same manifest as a terminal run, including all 8 packages and all 5 local extensions, with integrity hashes.
- Happy path, dialogs by id including a timeout auto-resolution, abort mid-stream, abort during retry.
- Approval: patch integrity/source drift; tool hook runs after handler mutation and resolved `executeBash` in root/nested/`extensions:false`; direct RPC/interactive/programmatic normal bash each produce one offer; one global active offer/FIFO eight; ninth-request overflow, 30-second queue timeout, 120-second decision expiry, and 150-second total cap all block/resume; offer/decision/expired binding; sentinel absent before Allow and after Deny/disconnect.
- Extension limits: a fixture performs direct Node `fs` side effects and proves they are **not** sandboxed. Known `/mcp`, `/usage`, `/agents`, `/btw`, `/llama` paths pre-route; unexpected command watchdog kills/restarts/resyncs and never claims custom-event detection.
- Reconnect: mid-stream byte identity; active-gap unavailable; idle fence; append/leaf retries; post-fence replay. Replay 10,000/64 MiB/session, 256 MiB global, 24-hour edges reset rather than truncate.
- Fork/backward branch fixture returns later off-branch append entries but an older active `leafId`; validation uses final `lastAppendId`, returns empty with unchanged leaf, publishes once, and never loops. A concurrent append or branch move retries the whole attempt.
- `reload_runtime` from the local `self-reload.ts` extension interrupts the transport; the client must recover rather than wedge.
- Writer-lease conflict: a second writer on one session faults visibly.

## Emulator matrix

Present today: Google APIs arm64 images/AVDs for 28/29/34/36, no-Google `android-34;default;arm64-v8a` as `PiApp_API_34_AOSP_UI`, and headless `android-34;aosp_atd;arm64-v8a` as `PiApp_API_34_AOSP`; emulator `37.1.11.0`, platform-tools `37.0.1`, SDK `/opt/homebrew/share/android-commandlinetools`. `PiApp_API_29` is the floor. API 28 remains unsupported negative-only.

| Configuration | Purpose | Note |
|---|---|---|
| API 36 `PiApp_API_36` | Primary instrumentation/E2E | Present, `google_apis` |
| API 34 `domonap` | Permission/FGS behavior | Present, `google_apis`; explicit serial |
| API 29 `PiApp_API_29` | Supported `minSdk` floor, TLS 1.3, terminal engine floor | Present, `google_apis`, WebView 91.0.4472.114; full floor lane plus independent Linux managed device |
| API 28 `PiApp_API_28` | Unsupported negative | Present; assert install incompatibility/exclusion, never product coverage |
| API 34 `PiApp_API_34_AOSP_UI` | No-Google UI/notification path | Present, `default;arm64-v8a`; no credential provider bundled, so use debug-only fake auth |
| API 34 `PiApp_API_34_AOSP` | Headless no-Google transport path | Present, `aosp_atd;arm64-v8a`; no IME/Settings/provider, so never assign composer, permission-UI, battery-settings, or production auth scenarios |
| Resizable/foldable, tablet | Responsive layout | AVD substitute; no real hinge/OEM evidence |

Before any terminal feature test, each API 29/34/36 lane reports the WebView package/version and runs the local xterm canary. API 29 must prove the `structuredClone` shim, `WeakRef`, canvas/font path, Unicode width, write/render, resize, and input. A fixture below WebView 91 or missing a capability must show update-required and make no terminal/network load. The xterm asset builds twice with identical hash.

Emulator limits, stated rather than glossed: graphics timing is not representative so performance numbers are indicative only; key attestation differs from hardware; the Bitwarden provider ceremony is not credibly reproducible; OEM battery policy does not exist.

```bash
emulator -avd PiApp_API_36
adb devices -l
# Identify mapping; do not guess emulator-5554:
adb -s <serial> emu avd name
SERIAL=<serial-that-reports-PiApp_API_36>
adb -s "$SERIAL" wait-for-device
ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest
adb -s "$SERIAL" logcat -d > artifacts/api36-logcat.txt
```

Every `adb` invocation uses `-s` when another emulator/device may exist. Each AVD scenario records serial-to-AVD mapping. Unqualified `adb wait-for-device`, screenshots, logcat, install, and input commands are prohibited in parallel runs.

## Manual matrix

Every row produces a sanitized artifact attached to the change. The integration owner must personally use the app; automated UI tests do not replace that.

### Emulator, supported API 29 floor and API 34/36

| Scenario | Pass condition |
|---|---|
| API 29 install, TLS 1.3, startup, storage, network, process death | No unsupported-API failure; Linux CI confirms floor |
| API 28 negative | Package declared unsupported or install rejected; never counted as floor pass |
| `PiApp_API_34_AOSP_UI` no-Google | UI, notification permission, UnifiedPush connector/fake distributor, provider-absent guidance work with debug-only fake auth; production passkey is not claimed |
| `PiApp_API_34_AOSP` headless ATD | Core TLS/transport, connector receiver, and fake-distributor delivery work; no IME/Settings UI is assigned |
| Fake first-owner/later-device pairing | CSR/route keys precede provisional TLS; registration vs assertion; exporter binding/local confirm; cert then mTLS; revocable |
| Passkey with Google Password Manager | Ceremony completes; DAL validates |
| Phone, tablet, foldable layouts; light and dark; rotation; process death | Layout correct; scroll position and drafts preserved |
| Semantic stream, steer, follow-up, abort, dialog, diff, image, raw inspector | Correct semantics; exactly one settlement |
| Approval deny/allow/broker unavailable | Sentinel absent on deny or broker loss; Pi turn resumes with blocked result; present once after allow |
| Dormant/indeterminate commands after host crashes | RECEIVED waits explicit current resubmit/revalidation; ARMED is unknown outcome; query never executes |
| Terminal engine/keys, resize, reconnect, history, renderer kill | Local bundle canary passes; connected 5k lines; fresh visible-pane redraw; separate bounded history; no input replay/full-history/CDN claim |
| Airplane mode, relay restart, host restart | Reconnect and resync with no duplication or truncation |
| Certificate revocation | Explicit revoked state; no readable plaintext remains |
| No distributor installed | Clear explanation and in-app catch-up |
| TalkBack order, polite streaming, 200% font, 48 dp targets, contrast | All pass |

### Physical release-signed Android 14+ device

| Scenario | Pass condition |
|---|---|
| Bitwarden passkey creation and assertion, plus DAL | Ceremony completes with the third-party provider |
| Hardware-backed key and invalidation inspection | Behavior recorded without unsupported claims |
| Direct LAN and relayed remote paths | Both work; path is visible in the UI |
| Android 14+ Bitwarden plus self-hosted ntfy with Google disabled/absent | Production passkey assertion and wake both work; proves full no-Google configuration |
| Doze and background completion and approval wakes; OEM battery behavior | Wake on next window; no duplicate; documented latency |
| Real microphone dictation through Groq | Ordered editable transcript, seams merged, never auto-sent |
| Gboard plus Bluetooth keyboard press, repeat, release in terminal mode | Exact key semantics including release |
| Device certificate and passkey revocation | Independent revocation confirmed |
| 5,000-message session scroll and reconnect | Smooth scroll within budget |

## Voice quota and economics

Fake-clock tests cover exact encoded duration including overlap and reserve before network send:

- billed seconds for 0.01/8/10/12.5-second uploads are 10/10/10/12.5; every retry adds another attempt/duration reservation;
- cost is `billedSeconds / 3600 × $0.04`, including `$0.04` for 3,600 billed seconds and `$0.05` for 4,500;
- defaults reject the next request at 18 RPM, 1,800 RPD, 6,480 ASH, or 25,920 ASD and allow it only after the exact sliding-window reset;
- ledger state and reservations survive kill/restart; concurrent reservations cannot oversubscribe a window;
- `$0.25` UTC-day and `$2.00` UTC-month boundaries reject before upload and roll over only at UTC boundaries;
- 429 seconds/date headers use monotonic delay, malformed/missing headers use deterministic-seeded full jitter for tests, values over 120 seconds stop, and no more than three retries follow the first attempt;
- every attempt consumes local request/audio/budget headroom, late duplicate chunk results are ignored, backlog above 30 seconds stops capture, and logs contain no audio/transcript.

## Performance budgets

Release build with R8 and a Baseline Profile, Macrobenchmark, Pixel 7-class or newer physical 60 Hz device. These are assertions. Emulator figures are recorded separately and never used to claim a budget is met. Threshold changes need benchmark evidence and an ADR, not a test-only relaxation.

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

## CI

Every commit runs contract and fuzz, Pi framing and assembly, unit, integration, journal crash matrix, Android instrumentation, and security. Pre-merge adds transport and auth, E2E, fault injection, and terminal. Pre-release adds supply chain, performance, and the manual matrices. Cross-language fixture parity is a hard gate.

The Gradle wrapper with checksum is mandatory since no system Gradle is installed. JDK 21 is the build JDK; JDK 25 is present locally but never assumed. Build-tools are fixed at 36.0.0. xterm/node-pty use full npm integrity and packed hashes; xterm's generated JS/CSS hash must be reproducible.
