# ADR-0016: Idle canonical snapshot and dormant command recovery

Status: Accepted; refines ADR-0003 and the sync contract
Date: 2026-08-09

## Context

Independent live RPC queries can describe different leaves, and Pi deltas after a gap are incomplete. Separately, auto-dispatching a recovered `RECEIVED` row would use stale auth, lease, leaf, blob, or approval state.

## Decision

On reset, discard provisional state, block bridge mutations, and wait for Pi idle/settled. Freeze event fence `F`; capture exactly one canonical `get_entries` response, its final append-order `lastAppendId`, and its independent eight-hex/null leaf; tag runtime queries as adjuncts at `F`; validate with `get_entries {since: lastAppendId}` (or repeated full query when empty), never with the leaf, and retry on any entry/leaf change; publish then replay after `F`. During an active gap, UI marks canonical data unavailable.

Recovered `RECEIVED` stays dormant. `command.query` observes only. Same-id/hash `command.submit` on the current READY, user-authenticated connection is the sole wake path and revalidates auth, lease, leaf, blobs, classification, and approval. Recovered `ARMED` remains indeterminate.

## Consequences

Snapshot attempts can wait while Pi works, but never expose a fabricated partial transcript. Recovery tests cover active gaps, mutation fencing, leaf races, adjunct cursors, post-fence replay, dormant queries/resubmission, and stale approval rejection.
