# AGENTS.md

Operating rules for anyone, human or agent, changing this repository. The frozen decisions live in [docs/adr/](docs/adr/README.md) and [docs/plan.md](docs/plan.md); this file is how you work inside them.

## Mission

Build a secure native Android control surface for Pi hosted on a paired Mac. Pi behavior and the user's real extension set stay authoritative on the Mac. The phone must be fast, honest about what it cannot do, and never a place where secrets accumulate.

Fixed identities, referenced by Digital Asset Links, the WebAuthn configuration, and UnifiedPush registration. Changing any of them invalidates issued credentials and requires an ADR plus a migration plan:

- Application ID: `io.github.verybigsad.pimobile`; `minSdk 29` (API 28 unsupported)
- Passkey RP ID: `verybigsad.github.io`
- Protocol major: 1, framed with the 12-byte `PIMB` header

## Module layout and ownership

One owner per concern. Never create a competing implementation of a concern that already has an owner. After Stage 0, lanes hold exclusive paths and two agents are never assigned the same path concurrently.

| Path | Language | Owns | Must not |
|---|---|---|---|
| `protocol/**` | schema-first, JSON Schema + fixtures | Frame constants, envelope, error catalogue, state machines, golden JSON/binary fixtures | Contain transport, I/O, or platform code |
| `mac/host/src/pi/**` | TypeScript, Node 22 | Subprocess supervision, strict LF framer, exact delta assembler, stderr bounds | Re-derive protocol types by hand |
| `mac/host/src/journal/**` | TypeScript | SQLite WAL `synchronous=FULL` at-most-once command journal | Cache responses in memory as the durability story |
| `mac/host/src/sync/**` | TypeScript | Session actor, writer lease, epoch/sequence, idle-only canonical snapshot fence, resync | Bypass the journal for mutations or merge live provisional state into a snapshot |
| `mac/host/src/transport/**`, `auth/**` | TypeScript | Direct TLS, standing relay control/data adapters, PKI, key wrapping, WebAuthn verifier, P-256 route auth, revocation | Hold Android concerns |
| `mac/host/src/terminal/**` | TypeScript | node-pty display client, private tmux server, control-client key injection | Send every key through the display client |
| `mac/host/src/voice/**` | TypeScript | VAD, chunking, ordered Groq requests, seam merge | Transmit the Groq key anywhere |
| `mac/host/src/notifications/**` | TypeScript | Wake outbox, opaque publish | Put content in a payload |
| `mac/approval/**` | TypeScript | Shared classifier, Unix-socket broker, normalized final-argument binding, expiry | Execute operations, use Pi dialogs, or persist broad allow rules |
| `mac/pi-patch/**`, `mac/preload/**` | reviewed Pi 0.84 patch + Node preload | Immutable out-of-band final `tool_call`/`user_bash` policy hook after extension handlers; global broker client for every nested in-process `AgentSession` | Add UI/policy logic to Pi, expose hook mutation/unregister APIs, or assume `extensions:false` disables the hook |
| `mac/compatibility/**` | TypeScript + generated data | Invocation-level `requiresTerminal`/side-effect manifest, slash-command pre-routing, watchdog | Infer a `custom()` event that RPC never emits |
| `android/core/protocol/**`, `network/**`, `security/**` | Kotlin | Codec, TLS stream, sequence checks, Keystore, CSR, passkey client | Contain UI |
| `android/core/model/**`, `storage/**` | Kotlin | Immutable state, reducers, Room + SQLCipher cache, Paging, drafts | Contain composables |
| `android/feature/**` | Kotlin | One surface each: `sessions`, `conversation`, `review`, `settings`, `notifications`, `voice` | Reach into another feature module |
| `android/terminal/**` | Kotlin + bundled assets | Hardened WebView, xterm assets, message channel, key row, IME bridge | Load anything from a CDN |
| `relay/**` | Go | Authenticated standing control channels, one-use data pairing, persisted route public keys/revocation, opaque byte splice | Parse the inner protocol or persist routes' session/content data |
| `infra/**` | HCL + service config | The one dedicated VM, Caddy, ntfy, policy tests | Contain secrets or committed state |
| root build files, version catalogs, workflows, `README.md`, `AGENTS.md`, central `docs/**`, `tests/e2e/**` | — | Integration lane only | Be edited by feature lanes after scaffold |

## Contract-first workflow

This is how protocol drift is made impossible, not a style preference.

1. Change `protocol/**` first: schema, version, error codes, and at least one golden fixture demonstrating the new shape.
2. Kotlin and TypeScript codecs both consume the **identical** checked-in fixtures. A fixture only one side can parse is a failing build.
3. Carry each Pi line as exact `rawJson` UTF-8 bytes (excluding LF), its SHA-256 digest, and a parsed reducer projection rather than pretending a parsed/re-serialized object is byte-exact. Projection is a deterministic bounded subset. Inline requires raw ≤128 KiB and escaped frame ≤256 KiB; otherwise use digest/size raw references.
4. Retain unknown fields and unknown `type` values; store and show them in the raw inspector, never execute them.
5. Every bound in [docs/protocol-v1.md](docs/protocol-v1.md) is checked before allocation. The lower applicable bound wins.
6. Wire-visible change bumps the minor additively; an incompatible change bumps the major and fails visibly with `UNSUPPORTED_VERSION`. There is no insecure fallback.
7. Changing a frozen decision requires an ADR, a fixture and schema update, both language implementations, and repeated independent review.

## Security invariants

Violating any of these is a release blocker, not a review nit.

1. Never commit API keys, Pi credentials, passkey material, pairing CA or server keys, keystores, signing keys, relay or ntfy admin secrets, cloud tokens, Terraform state, or `.tfvars`. `.terraform.lock.hcl` **must** be committed: pinned provider versions and checksums are a supply-chain property.
2. Provider credentials, `~/.pi/agent/auth.json`, and `~/.groq_key` never leave the Mac, in any encoding, including inside error messages. The Groq key is read at request time and never serialized into a frame.
3. TLS 1.3 only. Pairing alone uses inner server-auth TLS pinned by the QR; application data is forbidden there. After certificate issuance every direct or relayed data connection is mutual X.509. Remotely the inner stream crosses one-use paired binary WebSockets; boundaries carry no protocol meaning. LAN uses one direct TLS connection.
4. The relay is content-blind: metadata, timing, route id, and ciphertext only. Durable state is route public keys/revocation. First-route bootstrap token is root-only, outside Terraform state, SSH-retrieved, one-use, then erased; notices/replay stay in memory. No offline data queue, session store, inner parsing, or arbitrary host target.
5. Passkeys authenticate the user; mTLS certificates authenticate a device. Separate credentials, separate revocation. Revoking one never implicitly grants the other.
6. Android TLS/CSR and relay route-auth keys are separate non-exportable P-256 Keystore keys. Mac CA/server material is encrypted PKCS#8 mode `0600`, wrapped by a Keychain-held secret.
7. Every state-changing command is journaled `ARMED` before the first Pi stdin byte. Recovered `ARMED` becomes `INDETERMINATE`; recovered `RECEIVED` is dormant and cannot dispatch until the same id/hash is resubmitted on the current READY, user-authenticated connection and auth, lease, leaf, blobs, classification, and approval all pass again. Journal failure rejects mutations. Claim at-most-once dispatch; never exactly-once execution.
8. `command.query` observes journal state only. Duplicate `commandId` with a different canonical hash is a protocol violation. Same-id/same-hash resubmission is the only way to wake recovered `RECEIVED`; it never revives `ARMED`/`INDETERMINATE`.
9. Approval uses only `approval.offer`, `approval.decision`, and `approval.expired`, never Pi `confirm`. The project-pinned Pi patch invokes the immutable broker client after tool handlers on final args, including nested AgentSessions; patched resolved `executeBash` covers direct RPC/interactive/programmatic normal paths once, and bridge-owned actions gate separately. One global offer/FIFO eight, 30 s queue wait, up to 120 s decision, and a monotonic 150 s cap from hook invocation are fixed; overflow, timeout, broker loss, disconnect, changed args, or classifier error blocks/denies and resumes the turn. No "always allow". Never label steering "Approve".
10. Push payloads are opaque bounded wakes: no session names, prompts, file names, tool output, or results. A forged wake must grant no authority beyond triggering authenticated catch-up.
11. No production password, debug-certificate, biometric, device-certificate-only, or offline authentication bypass. Debug aids are `debug`-source-set only and loudly labeled. API 29–33 requires Play services for production passkeys; API 34+ may use Bitwarden/another compatible provider. No provider means locked; AOSP fake auth proves transport only.
12. Terminal input is ephemeral and never replayed after uncertain delivery. A connected xterm retains 5,000 lines; reconnect restores only the visible pane and offers a separate bounded server-side `tmux capture-pane` history drawer—never claim full scrollback restoration. Terminal WebView loads local assets only, with strict CSP, no network/file access, an exact-origin channel, and release debugging off.
13. ANSI control sequences in structured extension strings are removed or converted by an allowlisted parser before display.
14. Logs and crash reports record stable codes and opaque ids only, never prompts, Pi raw payloads, terminal bytes, audio, credentials, or keys. Redaction has tests.
15. `assetlinks.json` is generated from the real dedicated release signing configuration with both `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`; CI asserts HTTP 200, `application/json`, no redirect, exact package/fingerprint/relations, and cross-checks the Digital Asset Links API. The Mac never expands its pinned Android origin from DAL.
16. Arbitrary extension Node/fs/process side effects are not sandboxed and can bypass tool hooks; invocation classification and the final hook are guardrails, never a containment claim.

## Quality invariants

**Kotlin**

- Immutable UI state, one state holder per screen, structured concurrency with explicit scopes.
- No blocking work on the main thread: no network, database, JSON, crypto, markdown, or image work there.
- Lazy lists always pass a stable `key` and a `contentType`; the active streaming row is isolated.
- Never emit a whole transcript per token. Coalesce outside composition and publish at frame cadence.
- Cache expensive per-message derivations by message id plus content version.
- Retain 500 finalized semantic messages and 5,000 lines only for the currently connected xterm. After reconnect restore the visible pane; fetch bounded server-side tmux history into a separate read-only drawer.

**TypeScript**

- `strict` mode; runtime validation at every trust boundary; a validated type never comes from a cast.
- Bounded queues with an explicit documented backpressure policy. Queue exhaustion applies flow control, then closes with `RESOURCE_EXHAUSTED`; it never drops authoritative semantic data.
- Every cross-process or cross-network await has a timeout and an abort path. Broker deadline expiry returns a deterministic block result; it must never leave a Pi turn hanging.
- Split the Pi stream on LF only, stripping one CR immediately before LF. Never use a generic line reader: `U+2028`/`U+2029` are legal inside JSON strings.

**Go**

- Relay durable state is only route registration public keys/revocation; one-use rendezvous stays in memory. Bounded buffers/timeouts and opaque metrics only.

**Both/all**

- Follow ADR-0019: Gradle/AGP/Kotlin `8.13/8.13.2/2.4.10`, KSP `2.3.11`, Compose BOM `2026.06.01`, JDK21/JVM17, SDK `36/36/29`, Node `22.23.2`, TypeScript `6.0.3`, Go toolchain `1.26.5`.
- No code comments except API or function documentation and short notes on non-obvious security invariants.
- Errors carry a stable code and actionable context, and never carry secrets.

## Review rule

Every non-trivial plan and every non-trivial implementation gets **two independent reviews from different model families**, at minimum one Codex-large and one Claude-large reviewer, running in parallel and unaware of each other's findings. Trivial means documentation typos and mechanical renames.

Findings are ranked P0/P1/P2. Every P0 and P1 is fixed, or is recorded as an explicit dated disposition with a reason, as in [docs/reviews/plan-verification.md](docs/reviews/plan-verification.md). Security-sensitive fixes are re-reviewed. A stage cannot advance with an unresolved P0 or P1, or with required evidence that cannot be produced. Reviews must cite files and lines or state "no findings"; "looks good" is not a review. Rejected recommendations are preserved with their reason so they are not silently re-litigated.

## Test expectations

No implementation change lands without tests at the layer that change lives in. Tests assert semantics, not shape; a test that only checks a field exists is not a test. Fault paths are as mandatory as happy paths.

| Layer | Requirement |
|---|---|
| Contract | Identical fixtures cover framing/bounds/UTF-8, exact raw JSON+digest+projection, raw refs, prompt blobs, pairing phases, approval messages, event gaps, idle snapshot fence, dormant/query command states, and terminal history. Fuzzers prove bounded allocation. |
| Pi framing | LF-only splitting with `U+2028`/`U+2029` inside strings, CRLF tolerance, malformed JSON, and a record over 16 MiB faulting the subprocess. |
| Delta assembly | Assembled content is byte-identical to authoritative `message_end.message`, including content-only `thinking_end`, authoritative final signature/redaction replacement at `message_end`, interleaved thinking, and parallel tools; a forced gap triggers idle-only canonical recovery. |
| Lifecycle | Retry, compaction, and queued continuation keep the session working; exactly one settlement per `agent_settled`; nothing publishes on `agent_end`. |
| Journal | Crash injection at every state/write boundary; recovered `RECEIVED` stays dormant until same-id/hash READY resubmission and full revalidation; `command.query` cannot dispatch; 100 duplicates produce at most one Pi line; hash reuse and journal failure fail closed. |
| Sync | Loss/duplicate/gap/epoch; idle fence; final append ID distinct from leaf; backward-branch no-livelock; append/leaf retry; adjunct tags; post-fence replay; 100 reconnects. |
| Transport and auth | Provisional pairing/server pin/registration-vs-assertion/exporter+CSR binding; P-256 control/data cold reconnect/rotation; direct/relay mTLS, hostile relay, revocation, RP/origin/UV. |
| Approval | Source-patch checksum/drift; final mutated args after every handler; nested AgentSession with `extensions:false`; direct RPC bash; hard broker deadline/unreachable; offer/decision/expired; sentinel absent before allow and after deny/disconnect. |
| Push | Registration, opaque payload, settle dedupe, Doze catch-up, permission and force-stop states. |
| Voice | Silence/overlap/order/final flush; durable 18 RPM/1,800 RPD/6,480 ASH/25,920 ASD; exact billing and `$0.25` day/`$2` month budgets; 429 header/jitter/retry cap; backlog/cancel/cleanup/no auto-send. |
| Terminal | Deterministic xterm hashes; Chromium-91/API29 shim source locator; API29/34/36 canary and old-engine refusal; truecolor/OSC8/Unicode/resize/paste/mouse/keys; 5k/reconnect/history/renderer/isolation. |
| Extensions | Invocation-level manifest for every known path; `/mcp`, `/usage`, `/agents`, `/btw`, `/llama` pre-route; unexpected-command watchdog restart/resync; semantic and PTY scenarios; explicit Node/fs non-sandbox test. |
| Android instrumentation | Compose behavior, navigation, and accessibility semantics per screen. |
| Performance | Pinned `:android:benchmark` Macrobenchmark/Baseline Profile harness targets profileable `benchmarkRelease`; emulator output is diagnostic only. Pixel 7-class-or-newer physical release evidence against [docs/testing.md](docs/testing.md) remains mandatory. Debug scrolling proves nothing. |
| Supply chain | Locks/checksums, Pi tarball+patch hash/source locator, SBOM/SCA/licenses/secrets, signed package smoke. |
| Manual | The emulator and physical-device matrices in [docs/testing.md](docs/testing.md), with sanitized artifacts attached. |

Fixtures must be sanitized and must never be produced by mutating `~/.pi`. Compatibility claims are pinned to exact Pi and extension versions and must be regenerated after any upgrade.

## Documentation duty

Every behavior-changing commit updates `README.md`, `AGENTS.md`, and affected `docs/` in the same commit, including ADRs when a decision changes. Traceability updates before release claims. Only executed evidence may be satisfied; unimplemented evidence is planned, while truly external evidence is split into blocked-external rows. Never restate stale environment claims: API 29 is the floor; API 29/34/36 Google APIs AVDs, no-Google UI `PiApp_API_34_AOSP_UI`, and headless `PiApp_API_34_AOSP` exist; API 28 is unsupported negative-only; the Groq key is `0600`.

## Infrastructure duty

All cloud resources use `infra/**` Terraform with dedicated state. Commit `.terraform.lock.hcl` and keep explicit `!**/.terraform.lock.hcl`; never state/vars/plans. No resource before Stage 5 local gates. Record/prove unrelated IDs unchanged, prove no destroy orphan, recalculate cost, and stop above envelope. No console drift.
