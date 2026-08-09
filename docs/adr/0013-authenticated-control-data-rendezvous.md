# ADR-0013: Authenticated control/data rendezvous

Status: Accepted; supersedes the remote attachment part of ADR-0004
Date: 2026-08-09

## Context

Two independently attached data sockets need a reliable way to summon a NATed Mac. Stateless bearer tickets leave host liveness, cold reconnect, revocation, and key rotation underspecified.

## Decision

Mac maintains a P-256 challenge-authenticated control WSS with heartbeat and jittered backoff. Android authenticates a data request with its registered route key; relay notifies control; Mac opens one outbound data WSS bound to a one-use notice; relay pairs and byte-splices. A signed pairing invitation bootstraps the first unregistered device. Relay persists only route key IDs/public keys and revocation; pairing state and data notices are bounded/in-memory. Rotation overlaps old/new public keys, then revokes old. Inner TLS remains QR-pinned server-auth while pairing and mTLS afterward; direct LAN remains one mTLS connection.

## Consequences

Cold reconnect, challenge replay/audience/expiry, control loss, one-use pairing, rotation/revocation, database/log privacy, idle heartbeat cost, and restart/load behavior are release tests. Relay still observes metadata and can deny service, but holds no bearer route secret or session content.
