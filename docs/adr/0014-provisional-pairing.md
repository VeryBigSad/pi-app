# ADR-0014: CSR-first provisional pairing

Status: Accepted; supersedes the pairing sequence in ADR-0005
Date: 2026-08-09

## Context

A new device has no client certificate, so describing initial pairing as mTLS is circular. First-owner enrollment and later-device authorization have different WebAuthn security semantics.

## Decision

Android generates its non-exportable P-256 TLS key/CSR first and a separate route-auth key. It opens outer WSS, then inner TLS 1.3 with server authentication pinned by the five-minute QR and enters `PAIRING_PROVISIONAL`; no application data or mTLS is allowed. First owner uses distinct WebAuthn registration messages; later devices use assertion messages for the existing owner. Mac binds challenge to ceremony kind, invitation, CSR hash, and the 32-byte exporter `EXPORTER-Pi-Mobile-Pairing-v1` with SHA-256 context over invitation UUID + CSR hash. Successful WebAuthn is followed by short code, local Mac confirmation, atomic invitation consumption, route-key registration, and certificate issuance. Provisional closes; mTLS starts on a new connection.

DAL publishes both `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`.

## Consequences

Cross-connection/CSR/invitation replay, registration-versus-assertion confusion, premature mTLS/application data, and missing local confirmation fail closed. Physical release-signed Bitwarden and live DAL remain external gates.
