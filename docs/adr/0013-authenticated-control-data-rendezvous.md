# ADR-0013: Authenticated control/data rendezvous

Status: Accepted; supersedes the remote attachment part of ADR-0004
Date: 2026-08-09

## Context

Two independently attached data sockets need a reliable way to summon a NATed Mac. Stateless bearer tickets leave host liveness, cold reconnect, revocation, and key rotation underspecified.

## Decision

VM first boot creates a root-only one-use registration token outside Terraform state; Mac retrieves it over SSH, registers its P-256 route key under TLS, and both erase it. Mac then maintains a challenge-authenticated control WSS: JCS/DER ECDSA-SHA256, SPKI keys, 32-byte nonce, 30-second audience-bound challenge, two-minute replay cache, 30-second ping, 90-second liveness, and jittered backoff. Android authenticates a data request with its registered route key; relay notifies control; Mac opens one outbound data WSS bound to a one-use notice; relay pairs and byte-splices. A max-2-KiB JCS QR signed by the Mac route key bootstraps the first unregistered device. Data notices expire in 20 seconds. Relay persists only route key IDs/public keys and revocation; invitation/notices/replay are bounded/in-memory. Rotation overlaps old/new public keys 24 hours, then revokes old. Inner TLS remains QR-pinned server-auth while pairing and mTLS afterward; direct LAN remains one mTLS connection.

## Consequences

Cold reconnect, challenge replay/audience/expiry, control loss, one-use pairing, rotation/revocation, database/log privacy, idle heartbeat cost, and restart/load behavior are release tests. Relay still observes metadata and can deny service, but holds no bearer route secret or session content.
