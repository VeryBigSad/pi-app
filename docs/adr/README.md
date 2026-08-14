# Architecture decision records

Decision history for Pi Mobile 1.0 (supersession noted inline):

1. [ADR-0001: Pi RPC subprocess](0001-pi-rpc-subprocess.md)
2. [ADR-0002: Bounded JSON/binary protocol](0002-bounded-json-binary-protocol.md)
3. [ADR-0003: Fail-closed command journal](0003-fail-closed-command-journal.md)
4. [ADR-0004: WSS rendezvous and direct mTLS](0004-wss-rendezvous-inner-mtls.md) — remote attachment superseded by 0013
5. [ADR-0005: Mac WebAuthn verifier](0005-mac-webauthn-verifier.md)
6. [ADR-0006: UnifiedPush and ntfy](0006-unifiedpush-ntfy.md)
7. [ADR-0007: xterm, node-pty, and private tmux](0007-terminal-compatibility.md)
8. [ADR-0008: Mobile approval extension](0008-mobile-approval-extension.md) — superseded by 0012
9. [ADR-0009: Groq VAD chunking](0009-groq-vad-chunking.md)
10. [ADR-0010: One dedicated YC VM](0010-one-dedicated-yc-vm.md)
11. [ADR-0011: Native Android stack](0011-native-android-stack.md) — API floor superseded by 0015
12. [ADR-0012: Patched final-policy hook and broker](0012-final-policy-broker.md)
13. [ADR-0013: Authenticated control/data rendezvous](0013-authenticated-control-data-rendezvous.md)
14. [ADR-0014: CSR-first provisional pairing](0014-provisional-pairing.md)
15. [ADR-0015: Android API 29 floor](0015-api-29-floor.md)
16. [ADR-0016: Idle canonical snapshot and dormant recovery](0016-canonical-recovery.md)
17. [ADR-0017: Invocation-level terminal routing](0017-invocation-routing.md)
18. [ADR-0018: Passkey provider compatibility](0018-passkey-provider-compatibility.md)
19. [ADR-0019: Conservative API 36 build tuple](0019-build-toolchain.md)
20. [ADR-0020: Secure self-update](0020-secure-self-update.md)
21. [ADR-0021: Allow system screen capture](0021-system-screen-capture.md)

An accepted ADR changes only through a superseding ADR. Contract changes also update schemas, fixtures, both implementations, and both independent reviews.
