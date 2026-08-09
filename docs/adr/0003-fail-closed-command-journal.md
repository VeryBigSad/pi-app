# ADR-0003: Fail-closed at-most-once command journal

Status: Accepted; recovery refined by ADR-0016
Date: 2026-08-09

## Context

Pi RPC request IDs are correlation IDs, not idempotency keys; a same-ID bash probe executed twice. A crash after Pi accepts a mutation but before the host persists a result makes blind retry unsafe.

## Decision

Persist each state-changing semantic command in SQLite (`WAL`, `synchronous=FULL`, mode `0600`) as `RECEIVED`, then commit `ARMED` before any Pi stdin byte. Complete as `ACKED`/`REJECTED`; recover `ARMED` as `INDETERMINATE` and never redispatch it. Per ADR-0016, recovered `RECEIVED` stays dormant until same-id/hash resubmission on the current READY/user-authenticated connection revalidates every guard; `command.query` cannot dispatch. Different hash is a protocol violation. Journal failure disables mutations. Dormant `RECEIVED` expires rejected after 24 hours; full terminal-state payloads retain 30 days; ID/hash/state tombstones retain 365 days or 100,000 rows. Capacity pressure rejects new mutations rather than deleting live safeguards.

## Rejected

- Exactly-once claim: impossible across process/session side effects.
- In-memory/24-hour response cache: loses the crash boundary.
- Automatic retry of unknown mutations: may duplicate destructive work.

## Consequences

Dispatch is at most once but may execute zero times. Dormant RECEIVED can resume only with full current revalidation; indeterminate ARMED requires inspection and a new command. Prompt blobs use the protocol quotas and terminal rows release temporary bytes after one hour. Terminal input is not replayed. Crash injection around every state/write boundary is a release gate.
