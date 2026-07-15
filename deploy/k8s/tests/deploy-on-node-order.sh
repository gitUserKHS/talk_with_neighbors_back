#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
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

# These are intentional literal source-code patterns, not local expansions.
# shellcheck disable=SC2016
mysql_apply="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/mysql.yaml"')"
# shellcheck disable=SC2016
redis_apply="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/redis.yaml"')"
# shellcheck disable=SC2016
mysql_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/mysql --timeout=8m')"
# shellcheck disable=SC2016
redis_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/redis --timeout=5m')"
# shellcheck disable=SC2016
database_migrations="$(line_number 'bash "$RELEASE_DIR/run-database-migrations.sh"')"
# shellcheck disable=SC2016
backup_install="$(line_number 'bash "$RELEASE_DIR/install-mysql-backup.sh" "$RELEASE_DIR"')"
# shellcheck disable=SC2016
pre_migration_backup="$(line_number 'talk-with-neighbors-mysql-backup')"
# shellcheck disable=SC2016
traefik_apply="$(line_number '"${kubectl[@]}" apply -f "$RELEASE_DIR/traefik-config.yaml"')"
# shellcheck disable=SC2016
traefik_ready="$(line_number 'wait_for_traefik_https_config # Gate TLS ingress creation on the rendered Traefik rollout and bound ACME PVC.')"
# shellcheck disable=SC2016
full_apply="$(line_number '"${kubectl[@]}" apply -k "$RELEASE_DIR/base"')"
# shellcheck disable=SC2016
backend_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/backend --timeout=10m')"
# shellcheck disable=SC2016
frontend_rollout="$(line_number '"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/frontend --timeout=5m')"

assert_before "$mysql_apply" "$mysql_rollout" "MySQL must be applied before its rollout wait"
assert_before "$redis_apply" "$redis_rollout" "Redis must be applied before its rollout wait"
assert_before "$mysql_apply" "$redis_rollout" "MySQL must be applied before datastore rollout waits complete"
assert_before "$redis_apply" "$mysql_rollout" "Redis must be applied before datastore rollout waits begin"
assert_before "$traefik_apply" "$traefik_ready" "Traefik ACME configuration must be applied before its readiness gate"
assert_before "$traefik_ready" "$full_apply" "Traefik HTTPS must be ready before the TLS ingress is applied"
assert_before "$mysql_rollout" "$full_apply" "MySQL must be ready before the full kustomization"
assert_before "$redis_rollout" "$full_apply" "Redis must be ready before the full kustomization"
assert_before "$mysql_rollout" "$database_migrations" "MySQL must be ready before database migrations"
assert_before "$redis_rollout" "$database_migrations" "Redis readiness must complete before database migrations"
assert_before "$mysql_rollout" "$backup_install" "MySQL must be ready before backup automation is installed"
assert_before "$backup_install" "$pre_migration_backup" "Backup automation must be installed before the pre-migration backup"
assert_before "$pre_migration_backup" "$database_migrations" "A verified S3 backup must complete before database migrations"
assert_before "$database_migrations" "$full_apply" "Database migrations must complete before the application rollout"
assert_before "$full_apply" "$backend_rollout" "The full kustomization must precede the backend rollout"
assert_before "$full_apply" "$frontend_rollout" "The full kustomization must precede the frontend rollout"

echo "Deployment ordering is staged correctly"
