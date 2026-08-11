# Security design and threat model

Last updated: 2026-08-11
Status: implemented and unit/integration-tested as referenced in [requirements-traceability.md](requirements-traceability.md). Physical-device and live-remote evidence remain open gates.

## Security objectives

1. Pi/provider credentials, sessions, prompts, tool data, terminal data, and `~/.groq_key` remain on the Mac or inside authenticated end-to-end channels.
2. The relay and ntfy cannot read application content.
3. A stolen relay capability cannot impersonate a paired device.
4. Replayed, reordered, changed, revoked, or cross-device commands fail closed.
5. No production auth bypass exists.
6. A phone-triggered destructive action is blocked until a real pre-execution decision.
7. Logs, notifications, screenshots, backups, and crash reports do not leak content by default.

Non-goal: Pi and extensions are not sandboxed. Tool paths are gated, but direct extension Node/fs/process code still runs with Mac-user privileges.

## Trust boundaries

| Component | Trusted for | Not trusted for |
|---|---|---|
| Android app/Keystore | UI, local cache, TLS and route-auth private keys | Mac/provider credentials |
| Mac host/Keychain | Pi execution, truth, user verification, CA, journal | Containing arbitrary extension/process code |
| GitHub Pages | Serving static DAL/pairing page | Authentication decisions or session traffic |
| YC relay/Caddy | Availability and opaque byte forwarding | Plaintext, command authorization, replay decisions |
| ntfy distributor/server | Best-effort opaque wake delivery | Content or authorization |
| Groq | Transcribing explicitly submitted audio | Persistent project/session access |
| Pi extensions/tools | Their documented execution | Acting as a security sandbox |

Assume the public network and relay are hostile; assume a normal unrooted Android device and a Mac account not already compromised. Rooted phone, compromised Mac account, malicious Pi extension, shoulder-surfing after unlock, and denial of service remain outside guarantees.

## Production identity

- Application ID: `io.github.verybigsad.pimobile`.
- RP ID: `verybigsad.github.io`.
- DAL URL: `https://verybigsad.github.io/.well-known/assetlinks.json`.
- User verification and discoverable credentials: required.
- Attestation: `none`; no hardware-attestation claim.

DAL must return HTTPS 200, `application/json`, no redirect, both `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`, and the exact package plus dedicated release-signing fingerprint. Production excludes debug fingerprints. The Pages repository includes `.nojekyll` so `/.well-known` is published.

The dedicated EC release key is outside Git at mode `0600`, with its password in macOS Keychain. Its certificate SHA-256 is `CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE` and Android-origin value `zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4`. A locally signed v3 APK matches that digest. Off-machine/Bitwarden backup, rotation drill, and protected CI delivery remain required before release. Build automation derives from the same certificate:

- colon-hex SHA-256 fingerprint for DAL;
- unpadded base64url SHA-256 for `android:apk-key-hash:${RELEASE_CERT_SHA256_BASE64URL}`.

The Mac verifier pins the exact allowed origin; it never learns trust dynamically from DAL. Debug uses a different application ID and local/mock verifier and cannot authenticate to production.

## Passkey ceremony

Credential Manager handles public-key credentials. The Mac uses an exact-locked `@simplewebauthn/server` release, with Android-origin regression fixtures, to generate options and verify registration/assertion. GitHub Pages is static and the relay is a blind transport.

The Mac checks:

- exact RP ID and `rpIdHash`;
- exact Android release origin;
- cryptographically random single-use challenge, five-minute expiry;
- for normal assertions, binding to paired device certificate and current TLS exporter; for pairing, binding to ceremony kind, invitation, provisional TLS exporter, and CSR hash;
- credential ID, stored COSE public key, supported algorithm, signature;
- user-presence and user-verification flags;
- credential revocation;
- signature counter when meaningful, while permitting valid synchronized passkeys whose counter stays zero.

Authorization is attached to the current mTLS connection. Android sends `auth.lock` immediately when the device locks and after five continuous minutes backgrounded; the Mac independently expires a missing foreground lease on the same timer. The phase downgrades to device-authenticated and return requires a fresh assertion. Device-authenticated connections may exchange hello, auth, revocation, and generic wake metadata; they cannot fetch session content or mutate Pi before user authentication.

Bitwarden compatibility follows the WebAuthn/Credential Manager contract but is not claimed until tested on a release-signed physical Android 14+ device with Bitwarden selected as provider. API 29–33 production passkeys require the Google Play services provider; API 34+ can use a compatible third-party provider. With no provider the app remains locked—device mTLS, biometrics, and debug credentials are not auth substitutes. AOSP emulator lanes use debug-only fake auth solely for transport/push tests.

## Pairing and device PKI

Passkeys identify the user; mTLS certificates identify a paired device. Revoking either does not silently revoke the other.

1. Mac CLI (`pi-mobile-host pair`) creates one five-minute, single-use invitation, invalidating any previous pending invitation. QR carries a max-2-KiB JCS envelope under `pimobile://pair`: relay/direct candidates, route/key IDs, invitation ID, `macInstanceId` (UUID identifying this Mac host instance), 32-byte nonce, five-minute expiry, provisional server-certificate SHA-256, and a Mac-route ECDSA P-256 signature over the JCS-canonical payload (`mac/host/src/security/pairing-invitation.ts`); it never reaches Pages. The signature binds the invitation to the exact Mac route key, so a relay or network attacker cannot substitute a different host identity.
2. Before transport, Android generates a non-exportable P-256 TLS key with `DIGEST_NONE` allowed, creates/hashes the canonical CSR, and creates a separate non-exportable P-256 route-auth key. Hardware backing is used when available and reported honestly.
3. Android opens outer WSS with the invitation, then inner TLS 1.3 with **server authentication only**, pins the QR fingerprint, and enters `PAIRING_PROVISIONAL`. No client certificate or application data is accepted.
4. Android sends invitation, CSR hash/CSR, and device route public key. Mac binds the challenge record to invitation ID, provisional TLS exporter, CSR hash, ceremony kind, RP, origin, and expiry. The exporter uses the fixed label `EXPORTER-Pi-Mobile-Pairing-v1` (shared constant: `mac/host/src/security/pairing-ceremony.ts`, `android/core/network/.../TlsExporter.kt`), so the passkey assertion is cryptographically bound to the exact provisional TLS channel.
5. With no owner credential, only a WebAuthn registration ceremony is valid; later devices must assert the existing owner credential. Registration and assertion have distinct messages and replay domains.
6. Both displays show a transcript-bound short code; explicit local Mac confirmation follows successful WebAuthn.
7. Mac verifies CSR possession/binding, atomically consumes the invitation, registers the device route public key, signs a bounded-lifetime client certificate, and returns it.
8. Provisional TLS closes. Only a new TLS 1.3 mTLS connection may access normal negotiation, auth, sync, or commands.

Mac CA and server private keys are encrypted PKCS#8 files mode `0600`, wrapped by a random Keychain-held secret. This is chosen because Node TLS cannot directly use a generic non-exportable Keychain `SecKey`. Backups never include an unwrapped private key.

The P-256 local CA is valid five years. Server and device leaves are valid 30 days and renew with seven days remaining while the old certificate is valid and user-authenticated; otherwise re-pair. Leaves include URI SAN identities (`urn:pimobile:mac:{instanceId}` and `urn:pimobile:device:{deviceId}`) plus server DNS/IP SANs where applicable, critical basic constraints, digital-signature key usage, and only serverAuth/clientAuth EKU as appropriate. Both peers validate chain, TLS 1.3, profile, validity, expected SAN identity, and revocation every handshake. Revocation closes active connections. Server-leaf rotation overlaps 24 hours; CA rotation requires re-pair.

Recovery commands can revoke one device/passkey/route key, overlap-rotate route keys or server leaves, or reset owner credentials/pairings without deleting Pi sessions. CA rotation requires explicit re-pairing.

**Revocation kills sessions.** `pi-mobile-host revoke <deviceId>` (`devices.revoke` in `mac/host/src/daemon/daemon.ts`) revokes the device certificate in the revocation registry, revokes its relay route key locally, and propagates the revocation to the relay (`relayManager.admin().revokeKey`). Revoked peers are rejected at every subsequent handshake, and active connections carrying a revoked identity are closed rather than merely failing future requests.

## Transport

VM first boot creates a root-only one-use 256-bit registration token outside Terraform state. The Mac retrieves it over authenticated SSH, registers its route public key under TLS, and both erase it; no durable bearer remains. Bootstrap is **atomic**: cloud-init stages bootstrap/relay/ntfy secrets in a mode-`0700` staging directory (`0600` files, correct per-service ownership), validates preconditions (existing auth database without a completion marker aborts rather than regenerating credentials), and then publishes with `mv -f` renames (`infra/terraform/cloud-init.yaml.tftpl`). A failed boot cannot leave half-written credentials. Control/device/Mac-data WSS use P-256 ECDSA-SHA256 over JCS, SPKI keys, 32-byte nonces, 30-second audience-bound challenges, and two-minute replay retention. Control pings every 30 seconds and closes after 90. First-device data uses the Mac-route-signed invitation; paired data uses its device key. A 20-second one-use notice summons Mac data, then relay byte-splices.

Relay durable state is limited to route registration key IDs/public keys and revocation. It stores no shared bearer/HMAC secret, session names, invitation contents, offline data, or inner bytes. Route-key rotation registers old+new for a 24-hour overlap, switches clients, then revokes old; challenge signatures, nonce/expiry/audience binding, replay cache, one-use notices, compression-off, buffers/rates, and no payload logs are contract-tested. The Mac remains inner TLS server: QR-pinned server-auth in pairing, mTLS afterward.

Direct LAN uses one TLS 1.3 mTLS TCP connection after pairing. Discovery is only a hint and grants no trust.

TLS rejects record replay, reordering, mutation, and truncation. Across reconnects, single-use WebAuthn challenges, fresh TLS traffic keys, event epochs/sequences, UUID command IDs, payload hashes, and the durable journal reject semantic replay.

## Command safety

### Durable journal

Every state-changing command commits `RECEIVED`, then `ARMED` before any Pi stdin byte. Recovery leaves `RECEIVED` dormant. `command.query` may observe it but never dispatch it; only a deliberate same-id/same-hash submit on the current READY/user-authenticated connection can wake it after auth, lease, expected leaf, blob, policy, and approval are revalidated. Prior approval is invalid. Recovered `ARMED` becomes `INDETERMINATE` and never dispatches again. Different hash is an attack; journal failure disables mutations. See [protocol-v1.md](protocol-v1.md).

This guarantees at-most-once dispatch, not execution or exactly-once effects. Crashes can yield zero execution or unknown outcome. Android shows dormant versus indeterminate honestly.

Terminal bytes are not journaled or replayed. Lost acknowledgment means delivery unknown.

### Approval gate

One shared classifier/Unix-socket broker enforces versioned policy. The host checks destructive bridge-owned actions. An integrity-pinned Pi 0.84 patch calls a preload-registered immutable client after tool handlers mutate input and once inside `AgentSession.executeBash` after shell-prefix resolution, covering normal direct RPC, interactive, and programmatic bash without double prompting. `NODE_OPTIONS` loads it before Pi, and every nested in-process `AgentSession` invokes the same patched core even when created with `extensions:false`. Source locator, patch hash, nested-session, direct-RPC single-offer, and resolved-prefix tests block Pi drift. A `user_bash` extension that executes and returns its own result is arbitrary extension code and remains outside containment.

The pinned Pi installation is verified against a **full-dist-tree integrity manifest** (`mac/pi-patch/manifest/pi-0.84.0.json`): every file of the pinned 0.84.0 distribution has a recorded SHA-256, the two patched files (`AgentSession`, bash tool) have both original and patched hashes, and install/verify walks the whole tree (`assertTreeMatches`) rather than spot-checking. Any drift — added, removed, or modified file — blocks startup.

The broker has one globally active approval and a FIFO of at most eight waiting operations across sessions/devices. The preload starts a 150-second monotonic cap at hook invocation. Queue overflow or failure to promote within 30 seconds blocks immediately; promotion starts a decision window of up to 120 seconds, clipped by the hook cap. Connect, queue, decision, and response therefore all resolve within that cap. Denial, expiry, disconnect, missing socket, malformed response, changed final args, or classifier failure returns a deterministic block result and lets the Pi turn continue; it never wedges waiting for UI. Approval travels only as `approval.offer/decision/expired`, never as Pi `confirm`.

Policy covers recursive/protected deletion, destructive VCS/system/account/database/Terraform/cloud actions, reviewed destructive MCP operations, and unclassifiable dynamic shell. The offer displays normalized final operation, cwd/resource, digest, reasons, and expiry; decision binds operation/offer/hash and is single-use. Known extension slash commands are invocation-manifest classified. Destructive/unknown side-effect commands gate at invocation or route terminal.

Routine reads/builds/tests do not prompt. No “always allow” or generic approval button exists. This is not containment: an extension can call Node `fs`, spawn a process, mutate globals, or open a GUI outside Pi tool/user-bash paths. Tests and UX state that residual explicitly.

## Android storage and UI privacy

- TLS and route-auth private keys: Android Keystore, separate/non-exportable; inspect `KeyInfo` without overclaiming StrongBox.
- App cache/drafts: Room + SQLCipher; database key wrapped by Keystore.
- `android:allowBackup="false"`; exclude sensitive files from device transfer where applicable.
- Recents preview is blank/redacted while locked; release screenshots are blocked on sensitive screens.
- Clipboard copy is explicit, time-limited where supported, and never automatic.
- Crash reporting is opt-in and content-redacted; raw Pi/terminal/audio bodies are excluded.
- Notification payloads contain an opaque random wake ID only. Detailed text is fetched over mTLS and shown only after unlock.
- URI/file imports use Photo Picker or scoped access, enforce MIME/size, and copy into private bounded storage. Prompt images upload as digest-bound blobs; not-ready/cross-device refs fail, and cancelled/disconnected/expired orphan data is swept.
- WebView terminal cannot access files/content, remote network, mixed content, popups, arbitrary navigation, downloads, or unrestricted JS bridges. Assets are local with strict CSP; WebView debugging is off in release.

## Self-update threat model

The assisted updater (`android/core/update`, [ADR-0020](adr/0020-secure-self-update.md)) is designed so that a compromised metadata host or mirror cannot push code:

- Metadata (`update-v1.json`) is hard-bounded at 16 KiB, fail-closed parsed (unknown keys rejected), and `versionCode` is the sole ordering authority behind a persisted monotonic high-water mark; replayed old metadata is rejected.
- Trust comes from the APK signature, not the transport. The downloaded APK must have exactly one signer whose SHA-256 equals the compile-time pin (`CC:36:…:D4:FE`); debuggable builds disable the updater entirely.
- Every download and every install requires explicit in-app user action plus the OS confirmation; notifications only deep-link to the update sheet.
- Release signing secrets live only in the protected release Environment / operator Keychain; CI never sees them.

Signing-identity rotation requires shipping a new pin as a normal app update first; downgrades are not supported.

## Secrets

`~/.groq_key` currently exists with mode `0600`. The host trims it at read time. It is never sent to Android, Terraform, relay, logs, crash reports, or test fixtures. Temporary audio files are mode `0600`, removed after response, and cleaned at startup. Provider credentials and Pi auth files are never copied from the Mac.

Terraform state contains no application, relay-admin, signing, WebAuthn, or Groq secret. Relay/admin material is generated on first boot and retrieved once into Mac Keychain through an authenticated bootstrap.

## Abuse cases and required evidence

| Attack/failure | Required behavior |
|---|---|
| Relay replays/reorders/modifies bytes | TLS failure; no application command |
| Forged/replayed route challenge or one-use notice | P-256 signature/nonce/audience/expiry/replay check rejects; inner TLS still required |
| Relay database disclosure | Reveals route public keys/revocation only, not bearer secrets or content |
| Replayed WebAuthn assertion | Single-use challenge rejection |
| Wrong RP/origin or missing UV | Authentication rejection |
| Revoked device/passkey | Existing connection closed; future auth rejected |
| Duplicate command, same payload | Stored state returned; only dormant RECEIVED may resume once after full revalidation; no second Pi line |
| Duplicate ID, changed payload | Protocol violation |
| Crash before arming | Recovered `RECEIVED` dormant; query cannot dispatch; resubmit revalidates every guard |
| Crash around Pi stdin write | Recovered `ARMED` indeterminate, never replayed |
| Approval disconnect/deadline/broker loss | Final hook blocks and resumes; sentinel absent |
| Extension mutates tool args | Final post-handler hook hashes/classifies mutated args, not originals |
| Extension performs direct Node/fs side effect | Outside sandbox guarantee; invocation policy/terminal routing only, visibly documented |
| Oversized/malformed frame/raw event | Bounded failure and connection/process fault |
| Forged push | At most an authenticated catch-up attempt |
| ANSI/OSC in structured fields | Sanitized; no WebView/native action |
| Terminal WebView renderer compromise | No network/files/keys; destroy and fresh tmux attach |
| Lost/stolen phone | Revoke device cert and passkey; encrypted cache remains locked |

Security-sensitive changes require independent Codex-large and Claude-large review after fixes, plus protocol fuzzing, secret scan, dependency/SBOM/license checks, and physical-device verification.

## Residual risk

Approved arbitrary shell/tool code can damage the Mac, and arbitrary extension Node/fs/process behavior is not sandboxed by the final hook. Relay/ntfy can deny service and observe metadata. A compromised unlocked phone can act within its authorization lifetime; a compromised Mac account defeats host protections. OEM push behavior is uncontrollable.

DAL and release signing remain identity supply-chain risks. A Pages-account change can hijack App Link routing or deny credential association; compromise of the dedicated signing key can produce the exact Android origin the Mac pins. The verifier never imports allowed origins from DAL, CI derives package/fingerprint/origin from the signed APK and checks both DAL relations, account protection and out-of-band fingerprint review are required, and signer rotation uses an explicit overlapping-DAL/app migration before old-key removal. These measures reduce but do not erase hosting/signing compromise. Live Pages/DAL 200/MIME/no-redirect checks and both Google DAL API relations pass. Identity release remains incomplete until independent fingerprint review, off-machine key backup, account protection, and rotation drill pass.
