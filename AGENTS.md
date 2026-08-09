# AGENTS.md

## Mission

Build a secure, native Android client for Pi running on a paired Mac. Preserve Pi and extension behavior on the Mac; make the phone experience fast, clear, and resilient.

## Non-negotiables

- Never commit API keys, Pi credentials, passkey material, pairing secrets, signing keys, cloud tokens, or generated Terraform state.
- Keep relay payloads end-to-end encrypted whenever a relay is used.
- Keep `README.md`, `AGENTS.md`, and `docs/` accurate in every behavior-changing commit.
- Infrastructure changes go through Terraform. Prefer zero/low idle cost and destroy test resources promptly.
- Every implementation change includes tests at the appropriate layer.
- Use deterministic protocol fixtures and preserve backward compatibility within a protocol major version.
- Do not silently discard unknown Pi RPC or extension events; retain a generic representation.
- Accessibility, offline/reconnect behavior, and secret redaction are release criteria.

## Workflow

1. Research and record decisions in `docs/`.
2. Plan stages with explicit acceptance criteria and parallel work boundaries.
3. Have both Codex and Claude review non-trivial plans and implementations.
4. Implement atomic changes with tests.
5. Run unit, integration, Android instrumentation/E2E, security, and manual emulator checks.
6. Update requirement traceability before release.

## Repository layout

The module layout is finalized in the architecture plan. Do not create competing protocol or security implementations.

## Local policy

- Kotlin: immutable UI state, structured concurrency, no blocking work on the main thread.
- TypeScript: strict mode, runtime validation at trust boundaries, bounded queues, explicit abort/timeout handling.
- Logs must redact prompts, tool data, credentials, audio, and ciphertext keys by default.
- Production auth bypasses are forbidden. Development bypasses must be compile-time/debug gated and loudly labeled.
- Avoid code comments except API/function documentation or non-obvious security invariants.
