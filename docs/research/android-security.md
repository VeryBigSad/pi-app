# Research: Android authentication and security

Last updated: 2026-08-09

## Decisive constraint

**Passkeys require a public, stable relying-party domain serving Digital Asset Links.** A purely LAN or ad-hoc pairing deployment cannot satisfy R6 on its own, because Credential Manager will not create or retrieve a passkey without an HTTPS RP host whose `assetlinks.json` is fetchable and correct. This is the reason a small always-on public component may be justified even if session traffic never transits it.

Primary sources:

- Credential Manager prerequisites — <https://developer.android.com/identity/credential-manager/prerequisites>
- Create a passkey — <https://developer.android.com/identity/passkeys/create-passkeys>
- Configure asset links — <https://developer.android.com/training/app-links/configure-assetlinks>
- App Links overview — <https://developer.android.com/training/app-links/about>
- Test App Links — <https://developer.android.com/training/app-links/test-applinks>

## Verified passkey requirements

Statement file, hosted on the same sign-in / RP domain:

```json
[
  {
    "relation": [
      "delegate_permission/common.get_login_creds",
      "delegate_permission/common.handle_all_urls"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.example.app",
      "sha256_cert_fingerprints": [
        "AA:BB:CC:..."
      ]
    }
  }
]
```

- **URL:** `https://<sign-in-domain>/.well-known/assetlinks.json`, exact filename and path. Every host involved must serve it if multiple domains or subdomains are used.
- **Transport/response:** HTTPS, publicly accessible, HTTP 200, `Content-Type: application/json`, and **no 301/302 redirect**. If `robots.txt` restricts crawlers, `/.well-known/` must remain retrievable.
- **Statement fields:** `target.namespace` must be `android_app`; `package_name` must exactly equal the application ID; `sha256_cert_fingerprints` must contain the SHA-256 signing-certificate fingerprint(s). With Play App Signing, use the Play app-signing certificate fingerprint, not usually the locally generated key. Multiple fingerprints or entries are supported for separate builds or apps.
- **Relations:** `delegate_permission/common.get_login_creds` is the credential-sharing relation; `delegate_permission/common.handle_all_urls` enables App Links. The Credential Manager prerequisite guide shows both together for app-to-website sign-in.
- **App-side metadata:** the guide directs adding, under `<application>`:

  ```xml
  <meta-data
      android:name="asset_statements"
      android:resource="@string/asset_statements" />
  ```

  The `asset_statements` include pointing at the hosted DAL file is documented as required specifically for **password** credential sharing; passkeys still require DAL setup.
- **Platform floor:** Credential Manager passkey creation requires Digital Asset Links and Android 9 / API 28+.
- **Validation:** use Google's Digital Asset Links API against the host, and separately test App Link verification and on-device state.

Bitwarden compatibility is a consequence of standards conformance rather than a vendor integration: because Credential Manager brokers third-party credential providers, a correctly configured RP and WebAuthn ceremony is what makes Bitwarden usable. Not verified in this round: Bitwarden's current Android provider behavior for this specific RP configuration. That requires a manual device test and must not be assumed.

## Debug vs release signing pitfall

Debug builds and release builds have different signing certificates, so the `sha256_cert_fingerprints` array must include every fingerprint in use: the local debug keystore, any CI-generated key, and the Play app-signing certificate if distribution goes through Play. A single-fingerprint file is the most likely cause of "passkey works for me, fails on your build" reports. AGENTS.md already forbids committing signing keys, so the fingerprint list must be sourced from a documented runbook rather than a checked-in keystore.

## Threat model implications for R7

Requirement R7 asks for TLS plus application-layer end-to-end encryption, replay resistance, revocation, and key storage. Research-level conclusions:

- Passkey authentication proves *who is opening the app*. It does not by itself authenticate the *device-to-Mac channel*. A separate pairing credential is needed so the Mac can authorize a specific phone and revoke it later.
- Because any relay is required to be content-blind (R10), transport TLS is insufficient by itself; payloads need an application-layer envelope whose keys never reach the relay.
- Replay resistance needs monotonic sequencing or nonces at the envelope layer, not merely at the TLS layer, since a relay can reorder or re-deliver frames.
- Key material belongs in hardware-backed storage on the phone; the Mac side holds its half outside the repository. Pi provider credentials and `~/.groq_key` never leave the Mac per project constraints.
- Logs must redact prompts, tool data, credentials, audio, and ciphertext keys by default (AGENTS.md local policy), which the mobile client must honor including in crash reports.

## Recommendations

1. Treat the public RP host as an authentication-only dependency: it serves `.well-known/assetlinks.json` and the WebAuthn ceremony, and never sees session plaintext.
2. Automate `assetlinks.json` generation from the actual signing configuration and add a CI check that fetches the deployed file, asserts HTTP 200, `application/json`, no redirect, and that the expected fingerprints are present.
3. Keep passkey authentication and device pairing as two distinct credentials with independent revocation paths.
4. Enforce API 28+ as the floor for passkey flows; if a lower `minSdk` is ever chosen, define an explicit non-bypass fallback rather than a debug-style shortcut, because production auth bypasses are forbidden.
5. Write the manual Bitwarden verification runbook now, since it cannot be automated in CI.

## Unresolved tradeoffs

- **RP hosting choice.** A static object-storage site plus CDN is the cheapest way to satisfy the DAL requirements, but the WebAuthn ceremony itself needs some server-side verification. Whether that runs as a tiny always-on service or a scheduled/serverless endpoint is undecided and directly affects the R10 cost story.
- **Domain ownership.** The RP domain must be stable for the lifetime of every issued passkey; changing it invalidates credentials. No domain has been selected.
- **Play App Signing.** If distribution stays sideloaded, the Play fingerprint never applies; if it later moves to Play, the fingerprint set must be updated before release or existing passkeys break.
- **Pairing bootstrap UX.** QR-based pairing, short-code pairing, and out-of-band key entry differ substantially in phishing resistance; not yet evaluated.
