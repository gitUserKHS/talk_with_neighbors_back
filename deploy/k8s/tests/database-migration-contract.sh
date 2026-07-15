#!/usr/bin/env bash
# The grep expressions below intentionally match unexpanded shell source code.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="$SCRIPT_DIR/.."
readonly RUNNER="$DEPLOY_DIR/run-database-migrations.sh"
readonly MIGRATION="$DEPLOY_DIR/database-migrations/V2026071501__migrate_message_type_to_varchar.sql"
readonly CALENDAR_MIGRATION="$DEPLOY_DIR/database-migrations/V2026071601__backfill_chat_schedule_calendar.sql"
readonly DEPLOY_ON_NODE="$DEPLOY_DIR/deploy-on-node.sh"
readonly BUILD_BUNDLE="$DEPLOY_DIR/build-bundle.sh"
readonly BACKEND_MANIFEST="$DEPLOY_DIR/base/backend.yaml"

for required in "$RUNNER" "$MIGRATION" "$CALENDAR_MIGRATION" "$DEPLOY_ON_NODE" "$BUILD_BUNDLE" "$BACKEND_MANIFEST"; do
  [[ -s "$required" ]] || { echo "Missing database migration contract file: $required" >&2; exit 1; }
done

grep -Fq 'app_schema_migrations' "$RUNNER"
grep -Fq 'Database migration checksum drift' "$RUNNER"
grep -Fq 'sha256sum -- "$migration_file"' "$RUNNER"
grep -Fq 'mysql_root_apply_file "$migration_file"' "$RUNNER"
grep -Fq 'require_calendar_backfill_schema' "$RUNNER"
grep -Fq '14:18:4:9:2' "$RUNNER"
grep -Fq 'restore a verified schema+data backup first' "$RUNNER"
preflight_line="$(grep -nF -m1 '    require_calendar_backfill_schema' "$RUNNER" | cut -d: -f1)"
apply_line="$(grep -nF -m1 '  mysql_root_apply_file "$migration_file"' "$RUNNER" | cut -d: -f1)"
[[ "$preflight_line" =~ ^[0-9]+$ && "$apply_line" =~ ^[0-9]+$ && "$preflight_line" -lt "$apply_line" ]] || {
  echo "Calendar schema preflight must run before the backfill file is applied" >&2
  exit 1
}
grep -Fq 'table_name = '\''messages'\''' "$MIGRATION"
grep -Fq '@messages_table_exists = 0' "$MIGRATION"
grep -Fq 'MODIFY COLUMN `type` VARCHAR(20) NOT NULL' "$MIGRATION"
grep -Fq 'tmp_legacy_schedule_backfill' "$CALENDAR_MIGRATION"
grep -Fq 'tmp_legacy_schedule_candidates' "$CALENDAR_MIGRATION"
grep -Fq 'START TRANSACTION;' "$CALENDAR_MIGRATION"
grep -Fq 'FOR UPDATE;' "$CALENDAR_MIGRATION"
grep -Fq 'COMMIT;' "$CALENDAR_MIGRATION"
grep -Fq "MD5(CONCAT('legacy-chat-schedule:', room.id))" "$CALENDAR_MIGRATION"
grep -Fq 'room.is_public = 1' "$CALENDAR_MIGRATION"
grep -Fq 'INSERT INTO chat_schedule_rsvps' "$CALENDAR_MIGRATION"
grep -Fq 'INSERT INTO messages' "$CALENDAR_MIGRATION"
grep -Fq 'schedule_by_time.starts_at = candidate.starts_at' "$CALENDAR_MIGRATION"
grep -Fq "schedule_by_time.status = 'SCHEDULED'" "$CALENDAR_MIGRATION"
grep -Fq 'schedule_by_id.id = candidate.deterministic_schedule_id' "$CALENDAR_MIGRATION"
grep -Fq "MD5(CONCAT('legacy-chat-schedule-message:', pending.schedule_id))" "$CALENDAR_MIGRATION"
grep -Fq 'message.schedule_id = pending.schedule_id' "$CALENDAR_MIGRATION"
grep -Fq 'existing_read.message_id = message.id' "$CALENDAR_MIGRATION"
if grep -Eq '^[[:space:]]*UPDATE[[:space:]]+`?chat_rooms' "$CALENDAR_MIGRATION"; then
  echo "Calendar expand migration must preserve rollback columns in chat_rooms" >&2
  exit 1
fi
transaction_line="$(grep -nF -m1 'START TRANSACTION;' "$CALENDAR_MIGRATION" | cut -d: -f1)"
lock_line="$(grep -nF -m1 'FOR UPDATE;' "$CALENDAR_MIGRATION" | cut -d: -f1)"
commit_line="$(grep -nF -m1 'COMMIT;' "$CALENDAR_MIGRATION" | cut -d: -f1)"
[[ "$transaction_line" -lt "$lock_line" && "$lock_line" -lt "$commit_line" ]] || {
  echo "Calendar backfill must lock its room snapshot inside one transaction" >&2
  exit 1
}
grep -Fq 'bash "$RELEASE_DIR/run-database-migrations.sh"' "$DEPLOY_ON_NODE"
grep -Fq 'RUN_DATABASE_MIGRATIONS' "$DEPLOY_ON_NODE"
grep -Fq 'Database migrations skipped for guarded application rollback; the database is never downgraded' "$DEPLOY_ON_NODE"
grep -Fq 'cp -R "$SCRIPT_DIR/database-migrations" "$bundle/database-migrations"' "$BUILD_BUNDLE"
ddl_env_line="$(grep -nF -m1 'name: SPRING_JPA_HIBERNATE_DDL_AUTO' "$BACKEND_MANIFEST" | cut -d: -f1)"
ddl_value_line="$(grep -nF -m1 'value: none' "$BACKEND_MANIFEST" | cut -d: -f1)"
[[ "$ddl_env_line" =~ ^[0-9]+$ && "$ddl_value_line" =~ ^[0-9]+$ && "$ddl_value_line" -eq $((ddl_env_line + 1)) ]] || {
  echo "The production backend must force Hibernate ddl-auto=none immediately after the environment name" >&2
  exit 1
}

echo "Database migration contract is versioned, checksum-guarded, staged before rollout, and is the only production schema writer"
