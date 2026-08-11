# Relay infrastructure

This module owns one dedicated, always-on Yandex Cloud deployment: one non-preemptible `standard-v4a` VM (AMD EPYC Genoa platform, available in `ru-central1-d`), one 13 GiB auto-deleted network HDD boot disk, one reserved public IPv4 address, one VPC/subnet, one security group, one private container registry, and one VM service account with registry-level pull rights only. The VM uses 2 vCPU at 20% core fraction and 2 GiB RAM. Only TCP 443 is public; TCP 22 is limited to `admin_cidr`. There is no public TCP 80 listener.

Caddy terminates TLS 1.3 for `relay.<IP>.sslip.io` and `push.<IP>.sslip.io`. ACME uses the TLS-ALPN challenge on 443, so no paid DNS zone or HTTP challenge port is required. `sslip.io` is an external DNS dependency; use owned DNS before relying on this beyond a personal deployment.

## Supply-chain inputs

Terraform is constrained to provider `yandex-cloud/yandex` 0.220.0. `terraformrc` permits installation only through the Yandex Cloud provider mirror, and `.terraform.lock.hcl` locks Darwin arm64 and Linux amd64 checksums. The VM boot image is an explicit immutable `boot_image_id`; do not replace it with an image family data source. All three Compose images must be digest references.

## Relay image trust model

Runtime: the VM pulls the relay image by pinned digest from a **private Yandex Container Registry** (`yandex_container_registry.pi_mobile`). The VM's dedicated service account `pimobile-vm` holds only `container-registry.images.puller` on that registry (registry-level IAM, not folder-wide) and no other role; cloud-init fetches an IAM token from the instance metadata endpoint and runs `docker login cr.yandex` before the first compose pull. There is no GitHub dependency at runtime and no registry credential in Terraform variables, state, or cloud-init.

`relay_image_digest` is a `sha256:<64 hex>` variable; the full reference `cr.yandex/<registry-id>/relay@<digest>` is constructed from the registry resource, because the registry id exists only after apply. The digest must stay pinned; mutable tags are rejected by validation.

Publishing is an operator step, before the first plan:

```sh
infra/local/push-relay-image.sh --registry-id <cr-id>   # prints relay_image_digest for tfvars
```

The registry id is known only after its first apply; for a fresh deployment run a targeted bootstrap (`terraform apply -target=yandex_container_registry.pi_mobile`), push the image, then run the full plan with the printed digest.

Public provenance: the GHCR workflow still publishes `sha-<commit>` tags signed with GitHub Actions OIDC via cosign plus BuildKit provenance/SBOM attestations. That path is optional provenance only — `infra/local/verify-relay-image.sh` exercises it. The VM's update path cosign-verifies only `ghcr.io/...` references; the default update source is the YC CR pinned digest. Never put a GitHub token in Terraform variables, cloud-init, instance metadata, or state.

## Inputs and state

Use a dedicated state for this module and retain it until destroy completes. State and plan files are ignored, but must never be committed. Provider authentication belongs in the process environment. The SSH key in state is public. Relay and ntfy credentials are generated from `openssl rand` on the VM during first boot; their values never pass through Terraform, cloud-init metadata, or Terraform state.

Resolve and review an Ubuntu 24.04 image once, then pin its returned ID in the var file:

```sh
yc compute image get-latest-from-family ubuntu-2404-lts \
  --folder-id standard-images \
  --format json | jq -r '.id, .name, .created_at'
```

Copy `terraform.tfvars.example` outside the repository and replace every placeholder. `admin_cidr` must be a restricted IPv4 range and the module accepts exactly one Ed25519 public key.

## Local validation

```sh
cd infra/terraform
terraform fmt -check -recursive
TF_CLI_CONFIG_FILE="$PWD/terraformrc" terraform init -backend=false -lockfile=readonly
TF_CLI_CONFIG_FILE="$PWD/terraformrc" terraform validate

cd ../..
infra/local/render-cloud-init.sh > /tmp/pi-mobile-cloud-init.yaml
yq eval '.' /tmp/pi-mobile-cloud-init.yaml >/dev/null
infra/local/smoke-ntfy-auth.sh
docker build --platform linux/amd64 -f relay/Dockerfile -t pi-mobile-relay:local .
test "$(docker image inspect --format '{{.Config.User}}' pi-mobile-relay:local)" = '65532:65532'
docker run --rm pi-mobile-relay:local -h
```

On a host with `cloud-init`, also run:

```sh
cloud-init schema --config-file /tmp/pi-mobile-cloud-init.yaml
```

## Authenticated plan

Authenticate without writing a token into a var file:

```sh
export YC_TOKEN="$(yc iam create-token)"
TF_CLI_CONFIG_FILE="$PWD/terraformrc" terraform plan \
  -var-file=/absolute/path/to/pi-mobile.tfvars \
  -out=/absolute/path/to/pi-mobile.tfplan
terraform show /absolute/path/to/pi-mobile.tfplan
unset YC_TOKEN
```

Before any apply, the plan must show exactly one `yandex_compute_instance`, one `yandex_vpc_address`, one `yandex_container_registry` with its `images.puller` binding to only the `pimobile-vm` service account, no preemptible scheduling, only the intended 443/22 ingress, the pinned boot image ID, and no replacement or deletion of unrelated resources. Required external prerequisites are a Yandex Cloud principal with sufficient Compute/VPC permissions and quota, the target folder and zone, a current restricted operator CIDR, an Ed25519 key, the relay image digest already pushed to the pi-mobile registry by `push-relay-image.sh`, outbound access for apt/image pulls/ACME, working `sslip.io` resolution, and an approved current calculator estimate. This repository does not establish those external facts.

## First boot and restart

Wait for `cloud-init status --wait` and `pi-mobile.service` to succeed. Retrieve bootstrap material once:

```sh
terraform output -raw bootstrap_command
ssh pimobile@<public-ip> sudo /usr/local/bin/pimobile-bootstrap-read
```

The command serializes concurrent reads and removes the root-only export copies after a successful print. `shred` is best-effort logical cleanup and is not a guarantee that a cloud disk retained no historical block. The relay keeps a separate mode-0600 token in a UID/GID 65532 directory; the relay itself consumes and removes it only after the first successful route registration. ntfy stores a password hash in its auth database, resets anonymous ACLs to deny-all, and grants the authenticated non-admin `pimobile` user read/write access needed for UnifiedPush topics. Store the returned values securely and complete relay registration promptly.

Relay state and its token directory are owned by UID/GID 65532 and the container is explicitly non-root. ntfy, relay, and Caddy have read-only root filesystems, dropped capabilities, bounded local Docker logs, and no remote log sink. Caddy access logging is not enabled and ntfy logs at warning level, but system, SSH, ACME, error, source-IP, timing, and container metadata can still exist in the VM journal or bounded Docker logs. Notification payloads may remain in the local ntfy cache for up to 12 hours; only opaque bounded wake payloads belong there.

Compose uses immutable digests, `pull_policy: missing`, and `restart: unless-stopped`. Systemd validates Compose before an idempotent `up --detach --remove-orphans`; subsequent restart succeeds after plaintext bootstrap exports are removed because ntfy initialization checks the persisted user before reading the deleted password file.

```sh
sudo systemctl restart pi-mobile.service
sudo systemctl reload pi-mobile.service
sudo systemctl status pi-mobile.service
```

## In-place image updates

Cloud-init runs once per instance, so changing a digest in `cloud-init.yaml.tftpl` or the var file never rolls out to a running VM. Use the signed in-place path, which preserves relay, ntfy, and Caddy state on the boot-disk volumes:

```sh
ssh pimobile@<public-ip>
sudo /usr/local/sbin/pimobile-update stage RELAY_IMAGE=cr.yandex/<registry-id>/relay@sha256:<digest>
sudo systemctl start pi-mobile-update.service
```

`stage` accepts `RELAY_IMAGE`, `CADDY_IMAGE`, and `NTFY_IMAGE` digest references and writes `/etc/pimobile/images.env.next`; the systemd unit only runs when that file exists. Before any change, the update verifies the new relay digest's cosign signature against the pinned GitHub Actions workflow identity in `/etc/pimobile/cosign-policy` (this is the automatic deployment-time identity check; YC CR references authenticate via the VM service account instead; Caddy and ntfy digests are upstream images and are not signed by this repository). It then records the previous image set, pulls, recreates containers, and requires relay, ntfy, and Caddy healthchecks plus valid ACME certificates through `pimobile-await-healthy`. An unhealthy update rolls back to the previous image set automatically; `sudo /usr/local/sbin/pimobile-update rollback` repeats that manually. The service fails closed and unsigned or unverifiable relay digests are never applied.

## Cost and destroy

The recurring cost envelope consists of the non-preemptible VM CPU/RAM, 13 GiB network HDD, reserved public IPv4 address, Container Registry storage (the private pi-mobile registry holds relay images; a single small Go image is tens of MiB, keep only needed digests), and billable outbound traffic. Current Yandex Cloud pricing or quotas may add constraints. No fixed currency estimate is claimed here because rates and discounts change; recalculate in the official calculator for the selected folder, zone, expected traffic, and month length immediately before apply. Stopping the VM does not release its disk or reserved address and is not a complete cost stop.

Destroy is destructive: relay registrations/revocations, ntfy auth/cache, and Caddy state live only on the auto-deleted boot disk. Preserve only the intended non-content recovery material before proceeding. Destroy applies a reviewed saved plan artifact, never an unreviewed inline destroy:

```sh
infra/local/check-orphans.sh snapshot <folder-id> /absolute/path/to/pi-mobile-inventory.json
TF_CLI_CONFIG_FILE="$PWD/terraformrc" terraform plan \
  -destroy -var-file=/absolute/path/to/pi-mobile.tfvars \
  -out=/absolute/path/to/pi-mobile-destroy.tfplan
terraform show /absolute/path/to/pi-mobile-destroy.tfplan
TF_CLI_CONFIG_FILE="$PWD/terraformrc" terraform apply /absolute/path/to/pi-mobile-destroy.tfplan
infra/local/check-orphans.sh verify-destroy <folder-id>
```

The reviewed plan must delete exactly the pi-mobile VM, address, security group, subnet, and network and nothing else. `verify-destroy` fails if any pi-mobile resource remains in the folder; a lost or wrong state can otherwise leave billable resources orphaned. After a normal apply, `infra/local/check-orphans.sh verify-apply <folder-id> /absolute/path/to/pi-mobile-inventory.json` confirms pre-existing resources are untouched and exactly one pi-mobile instance, address, security group, and network exist.

The module enforces the approved budget only at plan time through the `monthly_cost_estimate_rub <= max_monthly_cost_rub` (default RUB 1500) precondition. Costs can drift after apply, so also configure a Yandex Cloud billing budget with a threshold notification on the folder as the out-of-band alert. No cloud apply or destroy is part of local validation.
