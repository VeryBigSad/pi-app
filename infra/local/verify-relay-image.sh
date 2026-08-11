#!/usr/bin/env bash
# Verifies a relay image digest before it is trusted by Terraform or by the
# in-place VM update path: anonymous pull from GHCR plus a cosign signature
# check against the pinned GitHub Actions workflow identity. Uses a scratch
# DOCKER_CONFIG so no local registry credential can mask anonymity.
set -euo pipefail

image=${1:-${RELAY_IMAGE:-}}
if [[ ! "$image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]]; then
  printf 'usage: %s ghcr.io/<owner>/<repo>/relay@sha256:<digest>\n' "$0" >&2
  exit 1
fi
identity=${CERTIFICATE_IDENTITY:-https://github.com/VeryBigSad/pi-app/.github/workflows/relay-image.yml@refs/heads/main}
issuer=${OIDC_ISSUER:-https://token.actions.githubusercontent.com}
cosign_image=${COSIGN_IMAGE:-gcr.io/projectsigstore/cosign@sha256:de9c65609e6bde17e6b48de485ee788407c9502fa08b8f4459f595b21f56cd00}

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
export DOCKER_CONFIG=$scratch

docker pull --platform linux/amd64 "$image" >/dev/null
if command -v cosign >/dev/null 2>&1; then
  cosign verify --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$image" >/dev/null
else
  docker run --rm "$cosign_image" verify \
    --certificate-identity "$identity" \
    --certificate-oidc-issuer "$issuer" \
    "$image" >/dev/null
fi
printf 'verified anonymous pull and signature\n  image:    %s\n  identity: %s\n  issuer:   %s\n' "$image" "$identity" "$issuer"
