# ADR-0015: Android API 29 product floor

Status: Accepted; supersedes the floor in ADR-0011
Date: 2026-08-09

## Context

The protocol requires platform TLS 1.3. API 28 supports Credential Manager prerequisites but lacks Android's platform TLS 1.3 implementation, so it cannot satisfy the transport contract without another crypto stack.

## Decision

Set `minSdk` to 29. `PiApp_API_29` is the supported floor AVD. Keep installed `PiApp_API_28` only for an explicit unsupported/negative check; never count it as product coverage. Google APIs images exist for API 29/34/36; `PiApp_API_34_AOSP_UI` provides a no-Google default-image UI lane, and `PiApp_API_34_AOSP` provides a headless AOSP ATD lane.

## Consequences

Stage 0 pins/builds/tests API 29 and rejects unsupported API use. API 28 users cannot install 1.0. Google APIs and AOSP ATD lanes remain distinct; disabling services on a Google image does not replace the AOSP result.
