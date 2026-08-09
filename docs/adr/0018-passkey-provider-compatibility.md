# ADR-0018: Passkey provider compatibility by Android version

Status: Accepted; clarifies ADR-0005 and ADR-0015
Date: 2026-08-09

## Context

Credential Manager passkeys do not imply a credential provider is present. On Android 13 and earlier, passkeys use the Google Play services provider. Android 14+ permits third-party providers such as Bitwarden. A no-Google AOSP image has no provider by default, while Pi Mobile still has no weaker production auth fallback.

## Decision

Keep `minSdk 29`, but scope runtime support explicitly:

- API 29–33 requires current Google Play services/Google Password Manager for production passkey registration and assertion.
- API 34+ may use a compatible third-party Credential Manager provider; Bitwarden is the designated no-Google provider for the still-blocked physical release test.
- If no compatible provider exists, the app remains locked and shows setup guidance. It does not fall back to password, biometric-only, debug credentials, or device certificate alone.
- The no-Google notification claim means the UnifiedPush/ntfy transport itself has no Google dependency. A fully usable no-Google production configuration additionally requires API 34+ and an installed third-party passkey provider.
- AOSP emulator lanes use a debug-only fake credential provider to test transport/push mechanics; they do not satisfy production passkey acceptance.

## Consequences

The Android build includes the Play-services Credential Manager adapter for API 29–33, but FCM remains optional and absent. Tests cover provider absent/setup-required states, API 29 Google-provider auth, API 34 third-party-provider integration boundaries, and AOSP transport with fake auth. Physical release acceptance still requires Bitwarden on Android 14+ with Google services disabled or absent.
