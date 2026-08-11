#!/usr/bin/env bash
set -euo pipefail

image=${NTFY_IMAGE:-docker.io/binwiederhier/ntfy@sha256:f2419f405127afa868f10985c1a41449e673477cee1eb19994339a5ae8b592e7}
if [[ ! "$image" =~ @sha256:[0-9a-f]{64}$ ]]; then
  printf 'NTFY_IMAGE must be digest-pinned\n' >&2
  exit 1
fi
name=pimobile-ntfy-smoke-$$-$RANDOM
volume=$name-data
password=$(openssl rand -hex 32)
up_topic=up$(openssl rand -hex 8)
other_topic=zz$(openssl rand -hex 8)
cleanup() {
  docker rm -f "$name" "$name-chown" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  unset password
}
trap cleanup EXIT INT TERM
status() {
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' "$@"
}
port() {
  docker port "$name" 80/tcp | sed 's/.*://'
}

docker volume create "$volume" >/dev/null
docker run --rm --name "$name-chown" --user 0 --volume "$volume:/var/lib/ntfy" --entrypoint chown "$image" -R 65533:65533 /var/lib/ntfy
docker run --detach --name "$name" \
  --user 65533:65533 \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --read-only \
  --tmpfs /tmp:size=16m,mode=1777 \
  --publish 127.0.0.1::80 \
  --env HOME=/tmp \
  --env NTFY_AUTH_FILE=/var/lib/ntfy/auth.db \
  --env NTFY_AUTH_DEFAULT_ACCESS=deny-all \
  --env NTFY_CACHE_FILE=/var/lib/ntfy/cache.db \
  --env NTFY_ATTACHMENT_CACHE_DIR= \
  --env NTFY_ENABLE_LOGIN=false \
  --env NTFY_WEB_ROOT=disable \
  --env NTFY_MESSAGE_SIZE_LIMIT=4096 \
  --env NTFY_GLOBAL_TOPIC_LIMIT=500 \
  --env NTFY_VISITOR_REQUEST_LIMIT_BURST=60 \
  --env NTFY_VISITOR_REQUEST_LIMIT_REPLENISH=3s \
  --env NTFY_VISITOR_MESSAGE_DAILY_LIMIT=10000 \
  --env NTFY_LOG_LEVEL=warn \
  --volume "$volume:/var/lib/ntfy" \
  "$image" serve >/dev/null
ready=false
for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error "http://127.0.0.1:$(port)/v1/health" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  docker logs "$name" >&2
  exit 1
fi
NTFY_PASSWORD="$password" docker exec --user 65533:65533 --env NTFY_PASSWORD "$name" ntfy user add --role=user pimobile >/dev/null
docker exec --user 65533:65533 "$name" ntfy access --reset everyone >/dev/null
docker exec --user 65533:65533 "$name" ntfy access --reset pimobile >/dev/null
docker exec --user 65533:65533 "$name" ntfy access pimobile 'up*' rw >/dev/null

# Anonymous and wrong-password access is denied on both topic classes.
[ "$(status --data-binary wake "http://127.0.0.1:$(port)/$up_topic")" = 403 ]
[ "$(status "http://127.0.0.1:$(port)/$up_topic/json?poll=1")" = 403 ]
[ "$(status --user pimobile:wrong --data-binary wake "http://127.0.0.1:$(port)/$up_topic")" = 401 ]

# The app user can publish and subscribe only inside the up* namespace.
[ "$(status --user "pimobile:$password" --data-binary wake "http://127.0.0.1:$(port)/$up_topic")" = 200 ]
curl --fail --silent --show-error --user "pimobile:$password" "http://127.0.0.1:$(port)/$up_topic/json?poll=1" | jq -e 'select(.event == "message" and .message == "wake")' >/dev/null
[ "$(status --user "pimobile:$password" --data-binary wake "http://127.0.0.1:$(port)/$other_topic")" = 403 ]
[ "$(status --user "pimobile:$password" "http://127.0.0.1:$(port)/$other_topic/json?poll=1")" = 403 ]

docker restart "$name" >/dev/null
ready=false
for attempt in $(seq 1 30); do
  if [ "$(status --user "pimobile:$password" "http://127.0.0.1:$(port)/$up_topic/json?poll=1" 2>/dev/null || true)" = 200 ]; then
    ready=true
    break
  fi
  sleep 1
done
[ "$ready" = true ]
[ "$(status --data-binary wake "http://127.0.0.1:$(port)/$up_topic")" = 403 ]
[ "$(status --user "pimobile:$password" --data-binary wake "http://127.0.0.1:$(port)/$up_topic")" = 200 ]
[ "$(status --user "pimobile:$password" --data-binary wake "http://127.0.0.1:$(port)/$other_topic")" = 403 ]
printf 'ntfy scoped up*-namespace deny-all smoke passed\n'
