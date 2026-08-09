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
- **Credential fact:** Credential Manager passkey creation supports Android 9 / API 28+ with DAL. **Product decision differs:** Pi Mobile requires platform TLS 1.3, absent on API 28, so `minSdk` is 29.
- **Validation:** use Google's Digital Asset Links API against the host, and separately test App Link verification and on-device state.

Provider support is version-dependent. On Android 13 and earlier, Jetpack Credential Manager passkeys are backed by Google Password Manager through `credentials-play-services-auth`; Android 14+ aggregates enabled third-party providers. Therefore API 29–33 production auth requires current Play services, while Android 14+ can select Bitwarden. A no-Google AOSP image has no provider by default and stays locked outside debug-only fake-auth tests. Sources: [Credential Manager FAQ](https://developer.android.com/identity/sign-in/credential-manager-faq), [Android 14 features](https://developer.android.com/about/versions/14/features), and [credential provider integration](https://developer.android.com/identity/sign-in/credential-provider).

Bitwarden compatibility remains a standards-conformance expectation, not a verified vendor integration for this RP. It requires a release-signed physical Android 14+ test and must not be assumed.

## Debug vs release signing pitfall

Debug and release certificates differ. Generic DAL can list several fingerprints, but Pi Mobile production deliberately lists only the dedicated release signer; debug uses a different application ID/local verifier and cannot authenticate to production. Any future signer overlap is time-bounded and generated from signed artifacts, never from a committed keystore.

## Threat model implications for R7

R7 requires end-to-end protection independent of relay TLS, replay resistance, revocation, and key storage. Conclusions:

- Passkey authentication proves *who is opening the app*. It does not by itself authenticate the *device-to-Mac channel*. A separate pairing credential is needed so the Mac can authorize a specific phone and revoke it later.
- Outer WSS ends at relay and is insufficient; standard inner TLS 1.3 is the end-to-end application tunnel whose keys never reach relay.
- Inner TLS rejects record replay/reorder; application epochs/sequences and durable command IDs/hashes handle semantic reconnect replay.
- Key material belongs in hardware-backed storage on the phone; the Mac side holds its half outside the repository. Pi provider credentials and `~/.groq_key` never leave the Mac per project constraints.
- Logs must redact prompts, tool data, credentials, audio, and ciphertext keys by default (AGENTS.md local policy), which the mobile client must honor including in crash reports.

## Recommendations

1. Treat the public RP host as an authentication-only dependency: it serves `.well-known/assetlinks.json` and the WebAuthn ceremony, and never sees session plaintext.
2. Automate `assetlinks.json` generation from the actual signing configuration and add a CI check that fetches the deployed file, asserts HTTP 200, `application/json`, no redirect, and that the expected fingerprints are present.
3. Keep passkey authentication and device pairing as two distinct credentials with independent revocation paths.
4. Enforce API 29 as product floor for platform TLS 1.3; API 28 remains unsupported. Require Play services for passkeys on API 29–33, permit compatible third-party providers on API 34+, and never add an auth fallback.
5. Write the manual Bitwarden verification runbook now, since it cannot be automated in CI.

## Final project decisions and residual risk

- RP is `verybigsad.github.io`; Pages serves DAL only and Mac verifies WebAuthn. DAL includes both `get_login_creds` and `handle_all_urls`.
- Pairing is CSR-first QR-pinned provisional server-auth TLS; first owner registers, later devices assert; challenge binds invitation/exporter/CSR; local short-code confirmation precedes certificate; mTLS starts afterward.
- Distribution is dedicated sideload signing for 1.0. A later Play move requires an explicit signer/origin/DAL migration.
- Pages compromise can hijack App Links or deny association; release-key compromise can produce the exact Android origin Mac pins. Mac never learns origins from DAL, CI cross-checks the signed APK, and key rotation overlaps fingerprints, but hosting/signing remain residual risks until real key/repo evidence exists.
