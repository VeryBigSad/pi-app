# Research index

Last updated: 2026-08-09

Four research tracks completed before architecture. Each document separates **verified facts** (with primary source URL or local file reference), **recommendations**, and **unresolved tradeoffs**. Nothing here decides architecture; these are inputs to it.

## Documents

| Document | Scope | Requirements informed |
|---|---|---|
| [pi-integration.md](pi-integration.md) | Pi 0.84.0 RPC protocol, event lifecycle, extension UI tiers, session tree, hosting options | R1, R2, R3, R8 |
| [mobile-ux.md](mobile-ux.md) | Prior art in Codex and Claude Code mobile, adopted patterns, Compose streaming performance | R4, R5, R9 |
| [android-security.md](android-security.md) | Credential Manager passkeys, Digital Asset Links, signing fingerprints, threat model | R6, R7 |
| [networking-infra-testing.md](networking-infra-testing.md) | Background execution and FCM, Groq transcription limits, transport options, infra, test implications | R8, R9, R10, R11 |

## Decisive constraints

These five findings constrain the design and should be treated as fixed inputs.

1. **Arbitrary Pi custom TUI requires PTY compatibility.** `ctx.ui.custom()` returns `undefined` outside a real terminal, and a family of TUI-only methods degrade to no-ops. Four of eight installed extension packages contain `ui.custom(` call sites, so a compatibility fallback is mandatory rather than theoretical. → [pi-integration.md](pi-integration.md)
2. **Groq has batch-only transcription.** `whisper-large-v3-turbo` exposes no documented streaming parameter; "realtime" voice must be assembled from short sequential chunks, with a 10 s minimum billed duration and 30 s optimal segment length. → [networking-infra-testing.md](networking-infra-testing.md)
3. **`agent_settled` triggers completion.** `agent_end` may be followed by automatic retry, compaction retry, or queued follow-ups; only `agent_settled` means Pi will not continue on its own. → [pi-integration.md](pi-integration.md)
4. **Passkeys require a public stable RP plus `assetlinks.json`.** HTTPS, HTTP 200, `application/json`, no redirect, exact `/.well-known/assetlinks.json` path, correct package name and SHA-256 signing fingerprints, API 28+. → [android-security.md](android-security.md)
5. **Background sockets are not reliable push.** Doze suspends background network access, Android 12+ restricts background FGS starts outside a genuine high-priority FCM message, and Android 15 caps `dataSync` FGS at 6 hours per 24 hours. FCM wake plus short fetch is the supported shape. → [networking-infra-testing.md](networking-infra-testing.md)

## Cross-cutting open questions

Carried into architecture; each is recorded in full in its source document.

- PTY mirroring versus an honest compatibility banner for `custom()` extensions.
- Subprocess `pi --mode rpc` versus embedded `AgentSession` SDK hosting.
- Where high-rate `tool_execution_update` / `bash_execution_update` coalescing happens: Mac bridge or phone.
- Notification payload specificity versus lockscreen and FCM privacy.
- VPN-first (Tailscale/Headscale) versus relay-first default transport.
- Whether the passkey RP host and the relay are one deployment or two.
- Voice chunk length, and Groq Free versus Dev plan.
- Concrete R5 performance budgets, to be set from Macrobenchmark measurements rather than chosen up front.

## Method notes

- Pi facts were read from the installed package at `/opt/homebrew/lib/node_modules/@earendil-works/pi-coding-agent` (version 0.84.0), citing `docs/*.md` with line references, plus the local extension set in `~/.pi/agent/settings.json` and `~/.pi/agent/npm/node_modules`.
- Platform, Groq, and transport facts cite vendor documentation URLs inline. Where a conclusion is an inference rather than documented behavior, it is labeled as a recommendation or as application-level inference.
- Items explicitly **not verified** in this round: per-extension fallback quality for the four packages using `ui.custom(`, current Bitwarden Android provider behavior against this RP configuration, and Android-specific validation of the surveyed Tailscale/Headscale comparison, which was framed around iPhone and macOS clients.
