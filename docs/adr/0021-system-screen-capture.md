# ADR-0021: Allow system screen capture

Status: accepted 2026-08-14

## Context

Pi Mobile previously set `FLAG_SECURE` whenever the device was paired. That blocked user screenshots, screen recording, and Android Recents thumbnails. The product owner explicitly requires screenshots and screen recording for normal use.

## Decision

Pi Mobile clears `FLAG_SECURE` for every trust state. User-initiated screenshots and screen recordings are allowed. Android may also create Recents previews automatically; those previews can contain the currently displayed session content.

The app does not initiate, persist, inspect, or upload screenshots, recordings, or Recents previews. Existing content-redaction rules for logs, crash reports, notifications, and app-owned storage are unchanged.

## Consequences

Displayed prompts, tool output, terminal content, and session metadata can leave the app through OS capture surfaces. Users control sharing and must use device-level restrictions when capture is inappropriate. Instrumentation verifies the trusted-state policy explicitly.
