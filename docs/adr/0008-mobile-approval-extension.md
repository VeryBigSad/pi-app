# ADR-0008: Add a Pi Mobile-only approval extension

Status: Superseded by ADR-0012
Date: 2026-08-09

## Context

Pi has no built-in sandbox or generic tool approval. A mobile “Approve” control without a real pre-execution gate would create false assurance while remote commands run with Mac user privileges.

## Decision

Use one shared Mac destructive-operation classifier and approval broker. The host calls it before direct RPC bash and destructive bridge-owned operations. A local extension, explicitly added only to bridge-owned Pi processes, calls the same broker from blocking agent-tool and relevant Pi lifecycle hooks. Each request binds exact normalized operation, cwd/resource, reasons, operation ID (`toolCallId` or host `commandId`), and argument hash. Only Allow once or Deny; timeout, disconnect, classifier error, changed args, or absent channel denies.

## Rejected

- UI-only approval: no enforcement.
- Gate every operation: unusable and obscures risk.
- Persist “always allow”: excessive v1 risk.
- Claim sandboxing: approved arbitrary code still has user permissions.

## Consequences

The classifier/broker lives outside the extension so host-submitted direct RPC operations cannot bypass it. Policy corpus and sentinel tests become security-critical. Normal desktop Pi is unchanged. Steering/review buttons use accurate labels and never masquerade as security approval.
