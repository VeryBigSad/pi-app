# ADR-0005: Verify WebAuthn on the Mac

Status: Accepted; pairing sequence superseded by ADR-0014
Date: 2026-08-09

## Context

Android passkeys require a stable public RP and root Digital Asset Links file, but the verifier endpoint need not use the RP hostname. Session truth and device authorization already live on the Mac.

## Decision

Use RP `verybigsad.github.io`, package `io.github.verybigsad.pimobile`, Credential Manager discoverable credentials, required UV, and attestation none. GitHub Pages serves DAL only. The Mac uses exact-locked `@simplewebauthn/server` with Android-origin regression fixtures and issues/consumes five-minute challenges and pins exact RP, Android release origin, credential, signature, UV/UP, and device/TLS binding. Passkey user identity is separate from mTLS device identity.

## Rejected

- Public cloud verifier: unnecessary plaintext/auth dependency.
- `github.io` as RP: public suffix and invalid.
- Relay hostname or `sslip.io` as RP: unstable identity.
- Production debug fingerprint/password/biometric bypass: weakens the fixed identity.

## Consequences

A dedicated release signing key and account Pages repository are hard prerequisites. Offline Mac means no fresh assertion. Real release-signed Bitwarden behavior must be tested physically. Certificate and passkey revocation remain independent.
