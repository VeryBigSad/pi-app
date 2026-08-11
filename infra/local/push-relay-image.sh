#!/usr/bin/env bash
# Builds the relay image for linux/amd64, pushes it to the private pi-mobile
# Yandex Container Registry, and prints the resolved sha256 digest to pin as
# relay_image_digest in the Terraform var file. Publishing to YC CR is an
# operator step; the VM pulls only the pinned digest with its service account.
set -euo pipefail

usage() {
  printf 'usage: %s --registry-id <cr-id> [--tag <git-sha>] [--dry-run]\n' "$0" >&2
  printf 'Builds relay/Dockerfile (linux/amd64), logs in via `yc container registry get-docker-token`,\n' >&2
  printf 'pushes cr.yandex/<registry-id>/relay:<tag>, prints the pinned digest line for tfvars.\n' >&2
  exit 1
}

registry_id=''
tag=''
dry_run=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --registry-id) registry_id=${2:?}; shift 2 ;;
    --tag) tag=${2:?}; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) usage ;;
  esac
done
[ -n "$registry_id" ] || usage
[[ "$registry_id" =~ ^[a-z0-9]{20}$ ]] || {
  printf 'registry id must be a 20-char YC resource id\n' >&2
  exit 1
}
if [ -z "$tag" ]; then
  tag=$(git -C "$(dirname "$0")/../.." rev-parse --short=12 HEAD 2>/dev/null || printf 'manual')
fi

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
image="cr.yandex/$registry_id/relay"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
export DOCKER_CONFIG=$scratch

printf 'building %s:%s (linux/amd64)\n' "$image" "$tag" >&2
docker build --platform linux/amd64 -f "$repo_root/relay/Dockerfile" \
  -t "$image:$tag" "$repo_root"

if [ "$dry_run" = 1 ]; then
  printf 'dry-run: skipping docker login and push\n' >&2
  exit 0
fi

yc container registry get-docker-token | docker login cr.yandex --username iam --password-stdin >/dev/null
docker push "$image:$tag" >/dev/null

digest=$(docker buildx imagetools inspect "$image:$tag" --format '{{.Manifest.Digest}}' 2>/dev/null \
  || docker inspect --format '{{index .RepoDigests 0}}' "$image:$tag" | sed 's/.*@//')
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  printf 'could not resolve pushed digest\n' >&2
  exit 1
}
printf '\nPinned reference: %s@%s\n' "$image" "$digest"
printf 'Add to tfvars:\n  relay_image_digest = "%s"\n' "$digest"
