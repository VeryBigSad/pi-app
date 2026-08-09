# ADR-0004: WSS rendezvous with inner mTLS; direct LAN mTLS

Status: Superseded in its remote attachment mechanism by ADR-0013; direct/inner TLS decision retained
Date: 2026-08-09

## Context

Both Mac and phone may be behind NAT. A forward CONNECT proxy cannot reach the Mac. The relay must remain content-blind, while LAN should avoid relay latency.

## Decision

For remote access both peers open outbound binary WSS to an opaque route on one YC relay. The relay pairs sides and copies bytes. Inside that duplex stream, the Mac serves TLS 1.3 with mutual X.509 authentication. WebSocket boundaries have no inner meaning. On LAN, use one direct TCP TLS 1.3 mTLS connection. Race paths and accept the first authenticated generation.

## Rejected

- HTTP CONNECT: missing NAT rendezvous and SSRF-prone if generic.
- Hand-rolled AES-GCM/Noise-like design: unacceptable cryptographic risk.
- Noise bindings: viable, but standard TLS was already proven across Android/Node and avoids a second audited native crypto core.
- Permanent VPN requirement: adds user setup and does not meet standalone app goal.

## Consequences

Relay sees metadata/ciphertext and can deny service but not read content. The host must adapt WSS bytes into TLS, enforce bounds/tickets, and resync after disconnect. Direct mode uses no duplicate outer TLS.
