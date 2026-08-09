# Infrastructure and cost

Last updated: 2026-08-09
Status: plan only; no project cloud resources created

## Decision

Create exactly one new dedicated non-preemptible YC VM after all local gates pass. It runs only Caddy, the opaque WSS rendezvous relay, and self-hosted ntfy. Do not reuse, import, or mutate unrelated existing VMs.

GitHub Pages separately serves `verybigsad.github.io/.well-known/assetlinks.json`; it is static and has no direct service fee. The Mac remains the WebAuthn verifier and Pi runtime.

## Terraform shape

`infra/terraform` owns a dedicated state and must create:

- `standard-v3`, 2 vCPU, 20% core fraction, 2 GiB RAM;
- 10–13 GiB network HDD;
- reserved public IPv4;
- dedicated service account with only required read/metrics permissions;
- dedicated security group;
- Ubuntu LTS image pinned by family plus resolved image ID recorded at apply;
- budget/monitoring resources where supported;
- DNS hostname using the reserved IP through `sslip.io` unless a user-owned domain is supplied before apply.

Public ingress:

- TCP 443: Caddy WSS/HTTPS.
- TCP 80: ACME HTTP challenge/redirect only; no application endpoint.
- TCP 22: only an explicit operator CIDR, disabled entirely after bootstrap when serial/YC access is sufficient.

No UDP, public databases, object stores, load balancers, serverless gateways, or second staging VM.

Pin Terraform/YC provider versions and commit `.terraform.lock.hcl`. `.gitignore` must contain explicit `!**/.terraform.lock.hcl` negation in addition to ignoring state/cache/plans/vars. Remote/local state is `0600`, encrypted at rest, and backed up securely. CI may run `fmt`, `validate`, policy, and `plan`; only a protected manual environment may apply/destroy.

## Services

### Caddy

- TLS termination for outer WSS and ntfy HTTPS.
- Automatic certificate renewal.
- Strict request/body/header/time limits.
- No application payload logging.
- Separate paths/hosts for relay and ntfy where possible.

### Rendezvous relay

- Go binary/container pinned by digest; binary WSS only, compression off.
- Mac holds a standing P-256 challenge-authenticated control WSS with heartbeat/liveness timeout and jittered exponential backoff.
- Android data WSS authenticates by registered P-256 route key; first device uses the signed pairing invitation. Relay notifies control, then accepts one outbound Mac data WSS bound to a one-use notice.
- Pair sockets in memory and byte-splice only after both attach. One MiB/direction and bounded global memory; handshake/wait/idle/rate limits; no arbitrary target or offline data queue.
- Durable database contains only route IDs, key IDs/public keys, and revocation/rotation timestamps. No bearer/HMAC secret, session/device display name, invitation, endpoint content, or inner bytes.
- Cold reconnect challenge, nonce/audience/expiry/replay cache, one-use notice, old/new key overlap then revoke, control loss, slow consumer, and restart are tested.
- Metrics use counts/bytes/durations and opaque labels only.

### ntfy

- Pinned container, separate Unix user and storage.
- `auth-default-access: deny-all`.
- Required high-entropy UnifiedPush `up*` namespace write access only.
- Persistent bounded cache so VM restart does not immediately lose queued wakes.
- Per-topic/IP publish rate limits and retention limits.
- No session names or plaintext results; UnifiedPush payload is opaque/encrypted wake data.

systemd manages all pinned containers with restart limits, health checks, resource limits, read-only root filesystems where feasible, and dedicated data directories. Logs rotate and redact tickets/topics/query strings.

## Secret bootstrap

Cloud-init contains no long-lived application secret. On first boot an authenticated operator flow registers the Mac's route P-256 public key; private keys remain in Mac Keychain. Relay service/admin bootstrap material stays local with restrictive permissions and retrieval disables itself. Device route public keys register only after completed pairing.

Never put these in Terraform variables/state, GitHub Actions logs, images, or repository files:

- relay admin/bootstrap secrets or route private keys;
- ntfy admin tokens or UnifiedPush endpoints;
- Mac pairing CA/server keys;
- Android signing key;
- Groq/provider credentials.

Routine service tokens rotate without rotating device certificates.

## Isolation and pre-apply safeguards

Before `apply`:

1. Record existing YC VM/disk/address/security-group IDs and hashes of relevant metadata.
2. Confirm Terraform state contains only `pi-mobile-*` resources.
3. Review `terraform show -json plan.bin` and reject update/delete/import/data-source references to unrelated compute resources.
4. Confirm unique names/labels, region/zone, CIDR, image ID, container digests, and cost estimate.
5. Stop if estimated fixed monthly cost exceeds ₽1,500 before variable egress without explicit user approval.

After `apply`, prove every pre-existing recorded resource is unchanged. After `destroy`, prove only the dedicated state resources disappeared and no disk, address, snapshot, DNS record, service account, security group, or budget alert was orphaned.

## Commands

Run from `infra/terraform` once files exist:

```bash
terraform fmt -check -recursive
terraform init
terraform validate
terraform providers lock -platform=darwin_arm64 -platform=linux_amd64
terraform plan -out=plan.bin
terraform show -json plan.bin > plan.json
# protected/manual gate only
terraform apply plan.bin

# teardown
terraform plan -destroy -out=destroy.bin
terraform show -json destroy.bin > destroy.json
terraform apply destroy.bin
```

Also required before remote apply:

```bash
# local container/integration tests
make relay-test
make ntfy-smoke
make load-test
```

Exact Make targets may be introduced by implementation, but the underlying checks are release gates.

## Cost envelope

Planning target, including VAT/region variation:

| Item | Expected monthly |
|---|---:|
| 2 vCPU at 20% + 2 GiB VM | dominant fixed cost |
| 10–13 GiB network HDD | small fixed cost |
| Reserved active IPv4 | fixed cost |
| Caddy, relay, ntfy software | ₽0 license/service fee |
| GitHub Pages | ₽0 direct fee |
| **Fixed planning envelope** | **about ₽900–1,500/month** |

Recalculate using current YC calculator immediately before apply; historical USD estimates varied from roughly $12–16/month for the selected class. Egress, snapshots, VAT/exchange rate, GitHub private CI overage, and domain cost are variable and reported separately.

Groq `whisper-large-v3-turbo` is currently `$0.04` per billed audio hour, with a 10-second minimum per request. The Mac durably reserves every attempt before upload and displays `sum(max(encodedDuration,10s)) / 3600 × $0.04` as a conservative upper bound; overlap and retries count. Defaults are 18 RPM, 1,800 RPD, 6,480 encoded audio seconds/hour, 25,920/day, `$0.25` per UTC day, and `$2.00` per UTC month. Any rate or budget boundary stops before send and shows reset. Current organization limits can be entered explicitly. 429 honors `Retry-After` up to 120 seconds or uses bounded full jitter, with three retries maximum. Counters persist across restart; no audio/transcript is logged.

Set budget alerts at 80% and 100% of the approved amount. No automatic destroy on budget alarm.

## Why one VM

Rejected alternatives:

- Reusing the existing unrelated VM: violates ownership, cost attribution, rollback, and safe destroy.
- YC API Gateway WebSockets: per-message integration, binary/base64 and frame limits, connection limits, and continuous terminal/audio request volume make it expensive and unsuitable.
- Two VMs for relay and ntfy: cleaner blast radius but unnecessary cost for one-user v1; process/container isolation is accepted.
- Preemptible VM: forced stops and no SLA save too little for the primary remote path.
- FCM-only: requires external Firebase credentials and does not satisfy no-Google delivery.
- Permanent Android socket: unreliable under Doze/OEM policies and wrong ownership boundary.

## Operations and rollback

- Local load/fault tests precede cloud creation, including standing-control heartbeat bandwidth, reconnect storms, route-key cold auth/rotation, one-use matching, and relay-database/log privacy inspection.
- Deploy by immutable image digest; health check before routing clients.
- Keep previous relay image and host/app protocol minor available for rollback.
- Relay restart intentionally drops tunnels; Mac/Android reconnect and resync from Mac truth.
- ntfy outage degrades to foreground/app-open catch-up; it never blocks direct/relay session use.
- Certificate renewal failure alerts before expiry.
- Back up configuration, route public-key/revocation registry, and bounded ntfy queue; relay has no durable session/content data or private route keys.
- Monthly review covers cost, control-WSS idle egress, image/CVE/cert/disk/rate limits, route-key revocation, database privacy, and orphan scan.

## External prerequisites

- YC `default` profile is authenticated and has an active billing account, but no resource is created until Stage 5.
- A release signing key/fingerprint and public `VeryBigSad/verybigsad.github.io` repository are still required.
- A user-owned domain is optional; `sslip.io` is acceptable for relay TLS, not the passkey RP.
- Operator CIDR and final YC zone are apply-time inputs.
