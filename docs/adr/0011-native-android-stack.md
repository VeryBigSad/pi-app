# ADR-0011: Native Kotlin/Compose Android client

Status: Accepted; platform floor superseded by ADR-0015
Date: 2026-08-09

## Context

The app needs Credential Manager, Keystore, audio, WorkManager, accessibility, responsive phone/tablet UI, encrypted persistence, and release-grade performance. Terminal compatibility alone is touch-hostile.

## Decision

Use Kotlin and Jetpack Compose Material 3 with coroutines/Flow, Room+SQLCipher, Paging, Credential Manager, Android Keystore, OkHttp, kotlinx.serialization, Coil, WorkManager, Macrobenchmark, and Baseline Profiles. Original floor was API 28; ADR-0015 supersedes it with `minSdk 29`. Application ID is `io.github.verybigsad.pimobile`. WebView is restricted to bundled xterm.

## Rejected

- Web-only/PWA: insufficient credential/key/background/native terminal/audio integration.
- Flutter/React Native: adds bridge/runtime complexity without improving Pi protocol work.
- Terminal-first UI: poor triage/review/accessibility experience.

## Consequences

Native semantic UX is primary, terminal explicit compatibility. Catalog/locks, JDK 21, supported API 29/34/36 coverage, API 28 negative, physical evidence, accessibility, and release Macrobenchmarks are required.
