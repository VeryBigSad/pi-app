#!/usr/bin/env bash
# Inventory and orphan checks for the pi-mobile Yandex Cloud folder.
#
# Modes:
#   snapshot <folder-id> <file>        Record IDs of every instance, disk,
#                                      address, security group, and network in
#                                      the folder before apply or destroy.
#   verify-destroy <folder-id>         Fail if any pi-mobile resource remains.
#   verify-apply <folder-id> <file>    Fail if a resource recorded by snapshot
#                                      disappeared or changed name, or if the
#                                      pi-mobile resources are not present
#                                      exactly once.
set -euo pipefail

mode=${1:-}
folder=${2:-}
file=${3:-}

usage() {
  printf 'usage: %s snapshot <folder-id> <file> | verify-destroy <folder-id> | verify-apply <folder-id> <file>\n' "$0" >&2
  exit 1
}

[ -n "$mode" ] && [ -n "$folder" ] || usage
command -v yc >/dev/null || { printf 'yc CLI required\n' >&2; exit 1; }
command -v jq >/dev/null || { printf 'jq required\n' >&2; exit 1; }

collect() {
  jq -n \
    --argjson instances "$(yc compute instance list --folder-id "$folder" --format json)" \
    --argjson disks "$(yc compute disk list --folder-id "$folder" --format json)" \
    --argjson addresses "$(yc vpc address list --folder-id "$folder" --format json)" \
    --argjson security_groups "$(yc vpc security-group list --folder-id "$folder" --format json)" \
    --argjson networks "$(yc vpc network list --folder-id "$folder" --format json)" \
    '{instances: $instances, disks: $disks, addresses: $addresses, security_groups: $security_groups, networks: $networks}'
}

case "$mode" in
  snapshot)
    [ -n "$file" ] || usage
    collect | jq '{instances, disks, addresses, security_groups, networks} | map_values(map({id, name}))' > "$file"
    printf 'recorded inventory in %s\n' "$file"
    ;;
  verify-destroy)
    leftovers=$(collect | jq -r '
      [.instances, .disks, .addresses, .security_groups, .networks]
      | flatten
      | map(select(.name == "pi-mobile" or (.name | startswith("pi-mobile-"))))
      | .[] | "\(.name) \(.id)"')
    if [ -n "$leftovers" ]; then
      printf 'orphaned pi-mobile resources remain:\n%s\n' "$leftovers" >&2
      exit 1
    fi
    printf 'no pi-mobile resources remain in folder %s\n' "$folder"
    ;;
  verify-apply)
    [ -n "$file" ] || usage
    [ -f "$file" ] || { printf 'snapshot file %s missing\n' "$file" >&2; exit 1; }
    before=$(cat "$file")
    after=$(collect | jq '{instances, disks, addresses, security_groups, networks} | map_values(map({id, name}))')
    missing=$(jq -n --argjson before "$before" --argjson after "$after" '
      [$before | to_entries[] | .key as $k | .value[] | select([$after[$k][] | select(.id == .id and .name == .name)] | length == 0) | "\($k) \(.name) \(.id)"]
      | .[]')
    if [ -n "$missing" ]; then
      printf 'pre-existing resources changed or disappeared:\n%s\n' "$missing" >&2
      exit 1
    fi
    for kind in instances addresses security_groups networks; do
      count=$(jq -n --argjson after "$after" --arg kind "$kind" '
        [$after[$kind][] | select(.name == "pi-mobile") | .id] | unique | length')
      if [ "$count" -ne 1 ]; then
        printf 'expected exactly one pi-mobile %s entry, found %s\n' "$kind" "$count" >&2
        exit 1
      fi
    done
    new_disks=$(jq -n --argjson before "$before" --argjson after "$after" '
      [$after.disks[] | select([$before.disks[].id] | index(.id) | not)] | length')
    if [ "$new_disks" -gt 1 ]; then
      printf 'expected at most one new disk (the auto-created boot disk), found %s\n' "$new_disks" >&2
      exit 1
    fi
    printf 'pre-existing resources unchanged; exactly one pi-mobile instance, address, security group, and network present\n'
    ;;
  *)
    usage
    ;;
esac
