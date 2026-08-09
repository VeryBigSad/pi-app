# ADR-0012: Patched final-policy hook and out-of-band broker

Status: Accepted; supersedes ADR-0008
Date: 2026-08-09

## Context

Ordinary Pi extension handlers can mutate tool/user-bash arguments, and nested in-process `AgentSession`s may run with `extensions:false`. An approval extension can therefore run too early or be absent. Pi `confirm` is UI, not an immutable security boundary.

## Decision

Integrity-pin Pi 0.84 and apply a minimal reviewed patch that invokes an immutable out-of-band policy hook after all extension handlers, on final arguments. A `NODE_OPTIONS` preload registers a frozen Unix-socket broker client before Pi loads; patched core invokes it for every root or nested in-process session, including pi-subagents and `extensions:false`. Host direct RPC bash and destructive bridge actions gate separately. The broker serializes all approval-requiring operations through one global FIFO: one visible offer, at most eight queued. The preload starts a 150-second monotonic cap at hook invocation. Queue admission/wait is bounded to 30 seconds; overflow or queue timeout blocks without execution. Promotion grants up to 120 seconds, clipped by the hook cap. Thus connect, queue, decision, and response all fit the 150-second cap, with earlier resolution on deny, disconnect, cancellation, or broker failure. Expiry returns a block result and resumes the turn. Mobile uses `approval.offer/decision/expired`, never Pi `confirm`.

## Consequences

Patch source locator/hash, final-mutation ordering, nested-session coverage, broker-unreachable behavior, and sentinels are release gates. This remains a guardrail: arbitrary extension Node/fs/process side effects outside Pi tool/user-bash paths are not sandboxed.
