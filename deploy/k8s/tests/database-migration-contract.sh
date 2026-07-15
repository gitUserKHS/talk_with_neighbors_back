#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="$SCRIPT_DIR/.."
readonly RUNNER="$DEPLOY_DIR/run-database-migrations.sh"
readonly MIGRATION="$DEPLOY_DIR/database-migrations/V2026071501__migrate_message_type_to_varchar.sql"
readonly DEPLOY_ON_NODE="$DEPLOY_DIR/deploy-on-node.sh"
readonly BUILD_BUNDLE="$DEPLOY_DIR/build-bundle.sh"

for required in "$RUNNER" "$MIGRATION" "$DEPLOY_ON_NODE" "$BUILD_BUNDLE"; do
  [[ -s "$required" ]] || { echo "Missing database migration contract file: $required" >&2; exit 1; }
done

grep -Fq 'app_schema_migrations' "$RUNNER"
grep -Fq 'Database migration checksum drift' "$RUNNER"
grep -Fq 'sha256sum -- "$migration_file"' "$RUNNER"
grep -Fq 'mysql_root_apply_file "$migration_file"' "$RUNNER"
grep -Fq 'table_name = '\''messages'\''' "$MIGRATION"
grep -Fq '@messages_table_exists = 0' "$MIGRATION"
grep -Fq 'MODIFY COLUMN `type` VARCHAR(20) NOT NULL' "$MIGRATION"
grep -Fq 'bash "$RELEASE_DIR/run-database-migrations.sh"' "$DEPLOY_ON_NODE"
grep -Fq 'cp -R "$SCRIPT_DIR/database-migrations" "$bundle/database-migrations"' "$BUILD_BUNDLE"

echo "Database migration contract is versioned, checksum-guarded, and staged before rollout"
