# ADR-0014: CSR-first provisional pairing

Status: Accepted; supersedes the pairing sequence in ADR-0005
Date: 2026-08-09

## Context

A new device has no client certificate, so describing initial pairing as mTLS is circular. First-owner enrollment and later-device authorization have different WebAuthn security semantics.

## Decision

Android generates its non-exportable P-256 TLS key/CSR first and a separate route-auth key. It opens outer WSS, then inner TLS 1.3 with server authentication pinned by the five-minute QR and enters `PAIRING_PROVISIONAL`; no application data or mTLS is allowed. First owner uses distinct WebAuthn registration messages; later devices use assertion messages for the existing owner. The Mac's `pair.begin` response carries a per-ceremony `pairingToken` of 32 random bytes (unpadded base64url) over the provisional pinned channel; both sides derive `sessionBinding = lowercase hex SHA-256(pairingToken)`. The Mac binds the challenge to ceremony kind, invitation, CSR hash, and this session binding.

An earlier revision bound via the 32-byte TLS exporter `EXPORTER-Pi-Mobile-Pairing-v1` with SHA-256 context over invitation UUID + CSR hash. It was replaced because the TLS exporter is unavailable on API 29 (it requires a hidden Conscrypt API). Binding strength is equivalent: the token never leaves the provisional channel, and that channel is pinned to the Mac's leaf certificate, so the token cannot cross TLS connections. Successful WebAuthn is followed by short code, local Mac confirmation, atomic invitation consumption, route-key registration, and certificate issuance. Provisional closes; mTLS starts on a new connection.

DAL publishes both `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`.

## Consequences

Cross-connection/CSR/invitation replay, registration-versus-assertion confusion, premature mTLS/application data, and missing local confirmation fail closed. Live DAL now verifies both relations; independent fingerprint review and physical release-signed Bitwarden remain gates.
