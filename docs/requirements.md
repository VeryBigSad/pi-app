# Requirements and acceptance

Last updated: 2026-08-09

| ID | Requirement | Acceptance evidence |
|---|---|---|
| R1 | Native Android Pi client synchronized with a Mac | An emulator/phone can discover or pair with a Mac, list sessions, reconnect, and continue one session. |
| R2 | Current Pi feature coverage | Capability matrix maps Pi RPC, session, model, command, attachment, tool, lifecycle, and extension behavior to UI or an explicit compatibility fallback. Unknown events remain inspectable. |
| R3 | Current extension compatibility | Mac process loads the user's normal Pi settings/packages/extensions; representative installed extensions pass integration scenarios. |
| R4 | Beautiful, mobile-first UX | Reviewed screen/component specification, responsive phone/tablet layouts, dark/light themes, accessibility checks, and manual screenshots. |
| R5 | Performance | Streaming remains responsive under a long synthetic session; memory, recomposition, queue, and reconnect tests meet documented budgets. |
| R6 | Authentication | Production access requires a standards-based Android Credential Manager passkey compatible with third-party providers such as Bitwarden. |
| R7 | Transport security | TLS plus application-layer end-to-end encryption, replay resistance, revocation, key storage, and threat-model tests. |
| R8 | Completion notifications | Android posts a completion/failure notification while backgrounded and catches up after reconnect. Delivery limits are documented. |
| R9 | Voice input | Hold/tap voice control streams audio toward the Mac; Groq `whisper-large-v3-turbo` returns ordered partial text and a final editable transcript. Groq key stays on Mac. |
| R10 | Low-cost optional middleman | Direct/local operation works where feasible. Any cloud relay is minimal, Terraform-managed, observable, and content-blind. Cost and destroy steps are documented. |
| R11 | Tests | Unit, integration, protocol contract, security, Android UI/instrumentation, E2E, fault, and manual emulator suites are documented and run. |
| R12 | Delivery | Reproducible debug/release builds, CI, install/run docs, SBOM/dependency checks, and pushed GitHub history. |
| R13 | Maintainability | `README.md`, `AGENTS.md`, architecture decisions, protocol docs, runbooks, and traceability remain current. |

## Constraints

- Pi/provider auth files and `~/.groq_key` must never leave the Mac.
- External account steps that cannot be automated safely must have exact runbooks and tested local substitutes.
- Cloud resources may be created only when needed and should not be left running without documented purpose and cost.
