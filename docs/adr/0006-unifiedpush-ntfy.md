# ADR-0006: UnifiedPush with self-hosted ntfy is primary

Status: Accepted
Date: 2026-08-09

## Context

Doze and OEM policy make background sockets unreliable. FCM requires external Google/Firebase credentials and is not universally available. Completion notification is a core requirement.

## Decision

Use the UnifiedPush connector with ntfy Android as distributor and a self-hosted ntfy server on the project VM. Mac sends only an opaque bounded wake after durable `agent_settled` or blocking input. Android performs authenticated catch-up and renders detail locally after unlock. Optional FCM implements the same interface but is not a release gate.

## Rejected

- Permanent Pi Mobile socket/FGS: unreliable and policy/battery hostile.
- `remoteMessaging` FGS: semantic/policy mismatch.
- FCM-only: human-gated and no-Google failure.
- Push as authorization: forged delivery must have no authority.

## Consequences

User configures ntfy distributor/battery behavior. Force-stop/OEM restrictions may delay wakes; app-open catch-up is authoritative. The VM must harden `up*` publish access, cache, rate limits, and payload privacy.
