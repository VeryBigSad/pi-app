# ADR-0001: Run the real Pi RPC subprocess

Status: Accepted; approval-loading detail superseded by ADR-0012
Date: 2026-08-09

## Context

The mobile client must preserve the user’s current Pi settings, sessions, packages, skills, prompts, tools, and extensions. Pi 0.84 exposes interactive LF-delimited JSON RPC. Embedding the SDK would require reproducing CLI loading/trust/lifecycle behavior.

## Decision

Run one integrity-pinned, minimally patched `pi --mode rpc` subprocess per semantic session, inheriting normal auto-discovery. ADR-0012 replaces the former explicit approval extension with a `NODE_OPTIONS` preload and patched final-policy hook. Parse stdout with strict LF bytes; assemble deltas by `contentIndex`; `message_end.message` is authoritative and only `agent_settled` completes.

## Rejected

- Embed `AgentSession`: typed but risks startup/resource drift.
- Run Pi/Termux on Android: moves credentials and execution off the Mac.
- Treat RPC updates as cumulative: factually wrong and corrupts streams.

## Consequences

The host owns process supervision, framing, delta assembly, stderr bounds, leases, and resync. Compatibility is pinned to exact Pi/extension versions and must be re-tested after upgrades. A separately launched Pi writing the same session is unsupported.
