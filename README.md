# Pi Mobile

Native Android control surface for the Pi coding agent running on your Mac. The Mac keeps every credential, runs Pi, and owns session truth; the phone is a fast, secure client for triage, steering, review, and dictation.

> **Status: final-audited architecture/plan; implementation and executable contracts not started.** No Android module, Mac host, relay, or Terraform exists. This is reviewed design backed by [ADRs](docs/adr/README.md), initial reviews/spikes, and two final audits—not shipped code. Machine facts are marked verified.

Application ID: `io.github.verybigsad.pimobile` · `minSdk 29` · Passkey RP: `verybigsad.github.io` · Pi baseline: 0.84.0 + reviewed patch

## What it will do

- Attach to Pi sessions on the Mac, list them by state, and continue one from the phone.
- Stream assistant text, thinking, tool calls, bash output, diffs, and extension events, with unknown events kept inspectable.
- Answer Pi's real interactive dialogs (`select`, `confirm`, `input`, `editor`) as native mobile UI.
- Gate destructive tool/user-bash calls on their final extension-mutated arguments through a fail-closed Mac broker.
- Pre-route manifest-known custom-TUI invocations to real terminal mode, because RPC returns no detectable `ctx.ui.custom()` event.
- Notify on completion without FCM, via UnifiedPush and a self-hosted ntfy distributor. Full no-Google use requires Android 14+ and a compatible third-party passkey provider such as Bitwarden; API 29–33 still needs Play services for passkey auth.
- Dictate into the composer with Groq `whisper-large-v3-turbo`, with `~/.groq_key` never leaving the Mac.

Both modes are release requirements: **semantic** (native UI over `pi --mode rpc`) and **terminal** (bundled xterm over a real PTY).

## Architecture

```text
Remote:
Mac ===== standing authenticated control WSS =====> YC relay
Android -- authenticated data WSS --> relay -- notice --> control WSS
Android <== one-use paired byte splice; inner TLS 1.3 ==> Mac
          server-auth pinned while pairing; mTLS after certificate

LAN:
Android ---------------- direct TLS 1.3 mTLS ------------- Mac

Mac host (Node 22, TypeScript)
├── project-pinned, minimally patched Pi 0.84 RPC subprocess
├── NODE_OPTIONS preload + Unix-socket final-policy broker client
├── strict LF byte framer + exact Pi delta assembler
├── SQLite fail-closed at-most-once command journal
├── idle-only canonical snapshot actor and writer lease
├── WebAuthn verifier + pairing CA
├── Groq VAD transcription worker
└── node-pty + private tmux display/control clients
```

Trust zones:

1. **Mac** — Pi, provider credentials, `~/.groq_key`, sessions, WebAuthn verification, all plaintext.
2. **Phone** — separate non-exportable P-256 TLS and relay route-auth keys, passkey, encrypted cache. No provider secrets.
3. **Relay and ntfy** — content-blind. The relay retains only route-auth public keys/revocation, signals the Mac's standing control WSS, pairs one-use data sockets, and copies bytes; it terminates outer WSS but never inner TLS.

Key decisions and why:

| Decision | Reason | ADR |
|---|---|---|
| Run an integrity-pinned, minimally patched Pi 0.84 CLI subprocess per session | Preserves real CLI loading/settings/packages/skills/prompts/extensions while adding only the reviewed final-policy call | [0001](docs/adr/0001-pi-rpc-subprocess.md) |
| Bounded 12-byte `PIMB` JSON/binary protocol | Carries exact Pi-line `rawJson` UTF-8 plus digest and parsed projection, with references above 128 KiB; supports terminal, prompt blobs, and PCM under hard bounds | [0002](docs/adr/0002-bounded-json-binary-protocol.md) |
| Fail-closed at-most-once SQLite command journal | Pi RPC ids are correlation ids, not idempotency keys: a probe with a duplicate id executed bash twice, so blind retry after a crash is unsafe | [0003](docs/adr/0003-fail-closed-command-journal.md) |
| Standing Mac control WSS + one-use data rendezvous + inner TLS; direct LAN mTLS | A NATed Mac remains reachable without keeping a relay data tunnel; P-256 route challenge auth survives cold reconnect, and LAN skips relay latency | [0013](docs/adr/0013-authenticated-control-data-rendezvous.md) |
| WebAuthn verified on the Mac, RP on GitHub Pages | The RP host only needs to serve Digital Asset Links; verification belongs where session truth already lives | [0005](docs/adr/0005-mac-webauthn-verifier.md) |
| UnifiedPush with self-hosted ntfy primary | Doze makes background sockets unreliable, and FCM would require external Google credentials; optional FCM stays behind the same wake interface but is not a release gate | [0006](docs/adr/0006-unifiedpush-ntfy.md) |
| Bundled xterm in a hardened WebView + node-pty + private tmux with split input | RPC returns `undefined` from `ctx.ui.custom()`; tmux consumes Kitty negotiation, so exact key bytes go through a control client while output goes through the display PTY | [0007](docs/adr/0007-terminal-compatibility.md) |
| Minimal Pi 0.84 final-policy patch + preload broker | Ordinary extension hooks can mutate arguments and nested AgentSessions may disable extensions; the immutable out-of-band hook runs last in every in-process session, while the host separately gates direct RPC/bridge actions | [0012](docs/adr/0012-final-policy-broker.md) |
| VAD-driven 8-12 s Groq chunks | Batch-only transcription needs ordered chunks; durable RPM/RPD/audio-window limits, 429 backoff, exact 10 s minimum billing, and hard daily/monthly budgets bound cost | [0009](docs/adr/0009-groq-vad-chunking.md) |
| Exactly one dedicated YC VM, created last | Remote rendezvous and no-Google push need one always-on public host; serverless WebSockets are unsuitable for terminal and PCM traffic | [0010](docs/adr/0010-one-dedicated-yc-vm.md) |
| Native Kotlin + Compose | Credential Manager, Keystore, audio, WorkManager, accessibility, and release performance all need native integration | [0011](docs/adr/0011-native-android-stack.md) |

## Roadmap

| Stage | Content | Parallel lanes |
|---|---|---|
| 0 | Contract and security freeze: schemas, golden fixtures, threat tests, capability manifest, build scaffolding | 4 |
| 1 | Foundations: Mac runtime, Android core, transport/auth, local relay, compatibility harness | 5 |
| 2 | Semantic vertical slice: full RPC facade, native UX, performance data, approval gate | 5 |
| 3 | Push, voice, release identity | 3 |
| 4 | Mandatory terminal compatibility | 2 |
| 5 | Cloud apply, hardening, E2E, release | mostly serial |

Full stage gates and ownership boundaries: [docs/plan.md](docs/plan.md). Requirement mapping: [docs/requirements-traceability.md](docs/requirements-traceability.md).

## Security

- Provider credentials, `~/.pi/agent/auth.json`, and `~/.groq_key` never leave the Mac. Verified: the key is mode `0600`, 57 bytes, untracked.
- Passkeys authenticate the user; mTLS client certificates authenticate a paired device. Separate credentials, separate revocation.
- TLS 1.3 only: QR-pinned server auth in restricted pairing, then checked/revoked mTLS on normal data. Relay sees metadata/ciphertext only.
- Mutations are journaled `ARMED` before the first Pi stdin byte. Recovered `ARMED` becomes `INDETERMINATE`; recovered `RECEIVED` stays dormant until the same id/hash is deliberately resubmitted over a current READY, user-authenticated connection and every guard is revalidated.
- Approval is its own `approval.offer/decision/expired` protocol, never a Pi `confirm`. The patched final hook sees final mutated arguments; timeout, broker loss, disconnect, changed arguments, or classifier failure blocks and safely resumes the Pi turn. There is no "always allow".
- Push payloads are opaque bounded wakes with no session content; detail is fetched over the authenticated channel and rendered after unlock. A forged wake grants nothing.
- No production password, debug-certificate, or offline authentication bypass.
- Logs record error codes and opaque ids only, never prompts, Pi raw payloads, terminal bytes, audio, credentials, or keys.

See [docs/security.md](docs/security.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/plan.md](docs/plan.md) | Master plan, stage gates, ownership lanes, release gates |
| [docs/architecture.md](docs/architecture.md) | Frozen system shape and components |
| [docs/protocol-v1.md](docs/protocol-v1.md) | Frame format, envelope, sync, command dispatch, bounds |
| [docs/security.md](docs/security.md) | Threat model and security design |
| [docs/infra-and-cost.md](docs/infra-and-cost.md) | One-VM infrastructure, cost envelope, destroy proof |
| [docs/requirements.md](docs/requirements.md) | R1-R13 with testable acceptance criteria |
| [docs/requirements-traceability.md](docs/requirements-traceability.md) | Requirement to stage, lane, and evidence mapping |
| [docs/capability-matrix.md](docs/capability-matrix.md) | Every Pi surface, package, and local extension with its treatment |
| [docs/ux.md](docs/ux.md) | Screens, notifications and privacy, accessibility, motion |
| [docs/testing.md](docs/testing.md) | Test layers, emulator and manual matrices, performance budgets |
| [docs/adr/README.md](docs/adr/README.md) | Decision history and supersession |
| [docs/reviews/plan-verification.md](docs/reviews/plan-verification.md) | Full review dispositions, including rejected recommendations |
| [docs/research/](docs/research/) | Four research tracks with primary sources |
| [docs/research/plan-spikes.md](docs/research/plan-spikes.md) | Review summary and the three targeted feasibility spikes |

## Current local setup (verified)

| Item | State |
|---|---|
| Pi | Global 0.84.0 at `/opt/homebrew/lib/node_modules`; project pin/patch not materialized yet |
| Pi packages | 8 in `~/.pi/agent/settings.json` |
| Local Pi extensions | 5: `btw/`, `macos-input-notifier.ts`, `mcp-tool-search.ts`, `self-reload.ts`, `subagent-model-policy.ts` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`; platforms `android-28`, `android-29`, `android-34`, `android-36`; build-tools `36.0.0`; cmdline-tools `latest` |
| System images | `google_apis` arm64 for API 28/29/34/36 plus no-Google API 34 `default` and `aosp_atd` arm64 images |
| Emulator / platform-tools | `37.1.11.0` / `37.0.1` |
| AVDs | Supported Google APIs: `PiApp_API_29`, `domonap`, `PiApp_API_36`; no-Google UI: `PiApp_API_34_AOSP_UI`; headless ATD: `PiApp_API_34_AOSP`; unsupported negative: `PiApp_API_28` |
| API 29 WebView | `91.0.4472.114`; xterm needs the planned local `structuredClone` shim and runtime canary |
| JDK | Temurin 21.0.8 and 25.0.2 present; no system Gradle, so the wrapper is mandatory |
| Node / Go / Terraform | 22.23.2 / 1.26.2 / 1.5.7 |
| Groq key | `~/.groq_key`, mode `0600`, 57 bytes. Read on the Mac at request time only, never serialized into a frame |
| tmux | 3.5a; `capture-pane -e` and `pipe-pane` both confirmed working |
| GitHub | `gh` authenticated as `VeryBigSad`, SSH git protocol |
| Yandex Cloud | `yc` present, `default` profile active, no project resource created |

## Honest gaps and prerequisites

External blockers and intentionally deferred local work are separated in traceability; none is satisfied:

- **No physical Android device.** Release-signed Bitwarden, Android 14+ no-Google auth, Gboard/Bluetooth input, microphone, hardware-backed keys, and Doze/OEM timing remain externally blocked; emulator substitutes are weaker.
- **No published relying party.** `https://verybigsad.github.io/` returns 404 today and the account Pages repository does not exist, so the Digital Asset Links file is not live.
- **No dedicated release signing key.** The Android origin and DAL fingerprint both derive from it, so it blocks passkey acceptance.
- **No Firebase credentials, by design.** The optional FCM adapter will be compiled/fake-tested without them; live FCM is never a release gate.
- **No cloud resource created.** The single YC VM is applied only in Stage 5, after a reviewed plan and a current cost check.

## Repository

<https://github.com/VeryBigSad/pi-app>
