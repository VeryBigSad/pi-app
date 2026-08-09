# ADR-0010: One dedicated YC VM

Status: Accepted
Date: 2026-08-09

## Context

Remote rendezvous and no-Google push need an always-on public host. Existing YC resources are unrelated; serverless WebSockets are expensive/limited for terminal and PCM traffic.

## Decision

After all local gates, Terraform creates one new non-preemptible `standard-v3` VM: 2 vCPU at 20%, 2 GiB RAM, 10–13 GiB HDD, reserved IPv4, dedicated security group. Pinned Caddy, Go relay, and ntfy run as isolated systemd-managed containers. Planning envelope is ₽900–1,500/month; current calculator over ₽1,500 before egress stops apply for approval.

## Rejected

- Reuse/import unrelated VM: unsafe ownership/destroy/cost.
- API Gateway/serverless hot path: cost, frame, binary, and duration limits.
- Two VMs: unnecessary personal-v1 cost.
- Preemptible VM: forced stop harms primary remote path.

## Consequences

Terraform state, names, inventory, budgets, digests, and destroy/orphan proof are mandatory. Relay persists only route public keys/revocation; control heartbeat cost/privacy are measured. Restart drops tunnels but loses no session content.
