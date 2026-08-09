# Research index

Last updated: 2026-08-09

Four research tracks preceded architecture. They separate verified facts from recommendations. Final decisions live in ADRs; where an early tradeoff is now closed, each research page says so.

## Documents

| Document | Scope | Requirements informed |
|---|---|---|
| [pi-integration.md](pi-integration.md) | Pi 0.84.0 RPC protocol, event lifecycle, extension UI tiers, session tree, hosting options | R1, R2, R3, R8 |
| [mobile-ux.md](mobile-ux.md) | Prior art in Codex and Claude Code mobile, adopted patterns, Compose streaming performance | R4, R5, R9 |
| [android-security.md](android-security.md) | Credential Manager passkeys, Digital Asset Links, signing fingerprints, threat model | R6, R7 |
| [networking-infra-testing.md](networking-infra-testing.md) | Background execution, UnifiedPush/optional FCM, Groq limits, transport, infra/testing | R8, R9, R10, R11 |

## Decisive constraints

These five findings constrain the design and should be treated as fixed inputs.

1. **Arbitrary Pi custom TUI requires PTY compatibility.** `ctx.ui.custom()` returns `undefined` outside a real terminal, and a family of TUI-only methods degrade to no-ops. Four of eight installed extension packages contain `ui.custom(` call sites, so a compatibility fallback is mandatory rather than theoretical. → [pi-integration.md](pi-integration.md)
2. **Groq has batch-only transcription.** `whisper-large-v3-turbo` exposes no documented streaming parameter; "realtime" voice must be assembled from short sequential chunks, with a 10 s minimum billed duration, 30 s optimized segment length, complete org-window limits, and bounded retry/cost accounting. → [networking-infra-testing.md](networking-infra-testing.md)
3. **`agent_settled` triggers completion.** `agent_end` may be followed by automatic retry, compaction retry, or queued follow-ups; only `agent_settled` means Pi will not continue on its own. → [pi-integration.md](pi-integration.md)
4. **Passkeys need stable RP/DAL; product TLS sets a higher floor.** Credential Manager supports API 28+, but platform TLS 1.3 requires Pi Mobile `minSdk 29`. DAL needs both login-credential and App-Link relations. → [android-security.md](android-security.md)
5. **Background sockets are not push.** UnifiedPush/self-hosted ntfy is primary and Google-independent; optional FCM is never required. → [networking-infra-testing.md](networking-infra-testing.md)

## Closed by architecture

Terminal is mandatory compatibility mode; real CLI subprocess is primary; update publication is losslessly coalesced; push is opaque; direct LAN plus one-VM rendezvous needs no VPN; Pages hosts DAL while relay+ntfy share YC; voice uses 8–12 s VAD; performance budgets are frozen in testing docs. Runtime Groq plan tier remains operational, not architectural.

## Method notes

- Pi facts were read from the installed package at `/opt/homebrew/lib/node_modules/@earendil-works/pi-coding-agent` (version 0.84.0), citing `docs/*.md` with line references, plus the local extension set in `~/.pi/agent/settings.json` and `~/.pi/agent/npm/node_modules`.
- Platform, Groq, and transport facts cite vendor documentation URLs inline. Where a conclusion is an inference rather than documented behavior, it is labeled as a recommendation or as application-level inference.
- Items explicitly **not verified** in this round: per-extension fallback quality for the four packages using `ui.custom(`, current Bitwarden Android provider behavior against this RP configuration, and Android-specific validation of the surveyed Tailscale/Headscale comparison, which was framed around iPhone and macOS clients.
