#!/usr/bin/env bash
# Optional GHCR-provenance check for the relay image: pull and cosign
# signature verification against the pinned GitHub Actions workflow identity.
# It uses the caller's Docker credentials, so it works while the package is
# private. This is NOT the runtime path — the VM pulls the pinned digest from
# the private Yandex Container Registry with its service account.
set -euo pipefail

image=${1:-${RELAY_IMAGE:-}}
if [[ ! "$image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]]; then
  printf 'usage: %s ghcr.io/<owner>/<repo>/relay@sha256:<digest>\n' "$0" >&2
  exit 1
fi
identity=${CERTIFICATE_IDENTITY:-https://github.com/VeryBigSad/pi-app/.github/workflows/relay-image.yml@refs/heads/main}
issuer=${OIDC_ISSUER:-https://token.actions.githubusercontent.com}
if ! command -v cosign >/dev/null 2>&1; then
  printf 'cosign is required to verify a private GHCR image\n' >&2
  exit 1
fi

docker pull --platform linux/amd64 "$image" >/dev/null
cosign verify --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$image" >/dev/null
printf 'verified pull and signature\n  image:    %s\n  identity: %s\n  issuer:   %s\n' "$image" "$identity" "$issuer"
