#!/usr/bin/env bash
set -euo pipefail

module=$(cd "$(dirname "$0")/../terraform" && pwd)
relay_host=${RELAY_HOST:-relay.203-0-113-10.sslip.io}
push_host=${PUSH_HOST:-push.203-0-113-10.sslip.io}
relay_image=${RELAY_IMAGE:-cr.yandex/crp000000000000000000/relay@sha256:1111111111111111111111111111111111111111111111111111111111111111}
caddy_image=${CADDY_IMAGE:-docker.io/library/caddy@sha256:844f60b64e4724a5aa8245e019dace0d3f199f7433ce6c57676cb30a920dbad9}
ntfy_image=${NTFY_IMAGE:-docker.io/binwiederhier/ntfy@sha256:f2419f405127afa868f10985c1a41449e673477cee1eb19994339a5ae8b592e7}
vars=$(jq -cn \
  --arg relay_host "$relay_host" \
  --arg push_host "$push_host" \
  --arg relay_image "$relay_image" \
  --arg caddy_image "$caddy_image" \
  --arg ntfy_image "$ntfy_image" \
  '$ARGS.named')
template=$(jq -Rn --arg value "$module/cloud-init.yaml.tftpl" '$value')
expression="jsonencode(templatefile($template, $vars))"
rendered=$(printf '%s\n' "$expression" | TF_CLI_ARGS=-no-color terraform -chdir="$module" console)
printf '%s' "$rendered" | jq -r . | jq -r .
