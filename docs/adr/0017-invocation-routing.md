# ADR-0017: Invocation-level terminal routing

Status: Accepted; complements ADR-0007
Date: 2026-08-09

## Context

In RPC mode `ctx.ui.custom()` returns `undefined` and emits no detectable event. Routing only after an extension attempts custom UI can therefore hang or silently degrade. Package-wide routing would unnecessarily demote headless tools that work natively.

## Decision

Stage 0 generates an integrity-bound manifest for every command path with `requiresTerminal`, side-effect class, expected RPC activity, and watchdog deadline. Pre-route `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and all discovered custom paths. Gate destructive/unknown side-effect extension commands at invocation or route terminal. A bounded unexpected-command watchdog kills the RPC process, restarts, and performs canonical resync; copy reports a generic compatibility timeout, never a detected custom event.

## Consequences

Source call-site audit starts at subagents 6, ask-user-question 4, MCP 3, usage 2, and btw 1; bundled `/llama` is a separate inline capability class. Every upgrade regenerates paths/counts and reruns semantic plus PTY scenarios. Direct extension Node/fs/process side effects remain unsandboxed.
