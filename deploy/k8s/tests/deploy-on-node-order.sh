#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TARGET_SCRIPT="${1:-$SCRIPT_DIR/../deploy-on-node.sh}"

line_number() {
  local needle="$1"
  local match
  match="$(grep -nF -m1 -- "$needle" "$TARGET_SCRIPT")" || {
    echo "Missing deployment command: $needle" >&2
    return 1
  }
  printf '%s\n' "${match%%:*}"
}

assert_before() {
  local earlier="$1"
  local later="$2"
  local description="$3"
  if ((earlier >= later)); then
    echo "Deployment order violation: $description" >&2
    return 1
  fi
}

mysql_apply="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/mysql.yaml"')"
redis_apply="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/redis.yaml"')"
mysql_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/mysql --timeout=8m')"
redis_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/redis --timeout=5m')"
full_apply="$(line_number '"${kubectl[@]}" apply -k "$RELEASE_DIR/base"')"
backend_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/backend --timeout=10m')"
frontend_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/frontend --timeout=5m')"

assert_before "$mysql_apply" "$mysql_rollout" "MySQL must be applied before its rollout wait"
assert_before "$redis_apply" "$redis_rollout" "Redis must be applied before its rollout wait"
assert_before "$mysql_apply" "$redis_rollout" "MySQL must be applied before datastore rollout waits complete"
assert_before "$redis_apply" "$mysql_rollout" "Redis must be applied before datastore rollout waits begin"
assert_before "$mysql_rollout" "$full_apply" "MySQL must be ready before the full kustomization"
assert_before "$redis_rollout" "$full_apply" "Redis must be ready before the full kustomization"
assert_before "$full_apply" "$backend_rollout" "The full kustomization must precede the backend rollout"
assert_before "$full_apply" "$frontend_rollout" "The full kustomization must precede the frontend rollout"

echo "Deployment ordering is staged correctly"
