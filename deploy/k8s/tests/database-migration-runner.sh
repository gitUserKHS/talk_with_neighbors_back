#!/usr/bin/env bash
# This test intentionally matches the production runner's literal EUID guard.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="$SCRIPT_DIR/.."

test_directory_name="$(cd -- "$SCRIPT_DIR" && mktemp -d ".database-migration-runner.XXXXXX")"
test_root="$SCRIPT_DIR/$test_directory_name"
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

fixture="$test_root/deploy"
fake_bin="$test_root/bin"
state="$test_root/state"
mkdir -p -- "$fixture" "$fake_bin" "$state"
cp -R -- "$DEPLOY_DIR/database-migrations" "$fixture/database-migrations"

# The production runner intentionally requires root. This test exercises its
# database protocol as an unprivileged CI user, so only the copied fixture's
# host privilege gate is removed; the checked-in runner remains unchanged.
grep -Fq '[[ "$EUID" -eq 0 ]]' "$DEPLOY_DIR/run-database-migrations.sh"
while IFS= read -r line; do
  [[ "$line" == '[[ "$EUID" -eq 0 ]]'* ]] || printf '%s\n' "$line"
done < "$DEPLOY_DIR/run-database-migrations.sh" > "$fixture/run-database-migrations.sh"

cat > "$fake_bin/k3s" <<'FAKE_K3S'
#!/usr/bin/env bash
set -euo pipefail

readonly state="${FAKE_K3S_STATE:?FAKE_K3S_STATE is required}"
readonly ledger="$state/ledger.tsv"
readonly apply_log="$state/apply.log"

[[ "${1:-}" == "kubectl" ]] || { echo "expected kubectl" >&2; exit 2; }
shift
[[ "${1:-}" == "-n" && "${2:-}" == "talk-with-neighbors" ]] || {
  echo "unexpected namespace" >&2
  exit 2
}
shift 2
[[ "${1:-}" == "exec" ]] || { echo "expected exec" >&2; exit 2; }
shift
interactive=false
if [[ "${1:-}" == "-i" ]]; then
  interactive=true
  shift
fi
[[ "${1:-}" == "statefulset/mysql" && "${2:-}" == "--" ]] || {
  echo "unexpected MySQL target" >&2
  exit 2
}
shift 2
[[ "${1:-}" == "sh" && "${2:-}" == "-ec" && "${4:-}" == "sh" ]] || {
  echo "unexpected container shell invocation" >&2
  exit 2
}
readonly database="${5:-}"
[[ "$database" == "talk_with_neighbors" ]] || { echo "unexpected database" >&2; exit 2; }

if [[ "$interactive" == "true" ]]; then
  migration_sql="$(cat)"
  if grep -Fq 'MODIFY COLUMN `type` VARCHAR(20) NOT NULL' <<<"$migration_sql"; then
    grep -Fq "table_name = 'messages'" <<<"$migration_sql"
    grep -Fq "@messages_table_exists = 0" <<<"$migration_sql"
    grep -Fq "'SELECT 1'" <<<"$migration_sql"
    if [[ "${FAKE_MESSAGES_EXISTS:?FAKE_MESSAGES_EXISTS is required}" == "0" ]]; then
      printf 'messages-absent-noop\n' >> "$apply_log"
    else
      printf 'legacy-alter\n' >> "$apply_log"
    fi
  elif grep -Fq 'tmp_legacy_schedule_backfill' <<<"$migration_sql"; then
    grep -Fq 'tmp_legacy_schedule_candidates' <<<"$migration_sql"
    grep -Fq 'START TRANSACTION;' <<<"$migration_sql"
    grep -Fq 'FOR UPDATE;' <<<"$migration_sql"
    grep -Fq 'COMMIT;' <<<"$migration_sql"
    grep -Fq 'INSERT INTO chat_schedules' <<<"$migration_sql"
    grep -Fq 'INSERT INTO chat_schedule_rsvps' <<<"$migration_sql"
    grep -Fq 'INSERT INTO messages' <<<"$migration_sql"
    grep -Fq "MD5(CONCAT('legacy-chat-schedule-message:', pending.schedule_id))" <<<"$migration_sql"
    grep -Fq 'message.schedule_id = pending.schedule_id' <<<"$migration_sql"
    printf 'calendar-backfill\n' >> "$apply_log"
  else
    echo 'unexpected migration SQL' >&2
    exit 2
  fi
  exit 0
fi

readonly statement="${6:-}"
case "$statement" in
  *"CREATE TABLE IF NOT EXISTS app_schema_migrations"*)
    :
    ;;
  *"SELECT CONCAT(checksum, ':', description) FROM app_schema_migrations WHERE version = '"*)
    version="$(sed -n "s/.*version = '\([^']*\)'.*/\1/p" <<<"$statement")"
    if [[ -f "$ledger" ]]; then
      awk -F '\t' -v version="$version" '$1 == version { print $3 ":" $2 }' "$ledger"
    fi
    ;;
  *"FROM INFORMATION_SCHEMA.COLUMNS"*)
    printf '%s\n' "${FAKE_CALENDAR_SCHEMA_FINGERPRINT:?FAKE_CALENDAR_SCHEMA_FINGERPRINT is required}"
    ;;
  *"INSERT INTO app_schema_migrations"*)
    row="$(sed -n "s/.*VALUES ('\([^']*\)', '\([^']*\)', '\([^']*\)').*/\1\t\2\t\3/p" <<<"$statement")"
    [[ "$row" =~ ^V[0-9]{10}$'\t'[a-z0-9_]+$'\t'[a-f0-9]{64}$ ]] || {
      echo "invalid ledger insert" >&2
      exit 2
    }
    version="${row%%$'\t'*}"
    if [[ -f "$ledger" ]] && awk -F '\t' -v version="$version" '$1 == version { found = 1 } END { exit !found }' "$ledger"; then
      echo "duplicate migration version" >&2
      exit 2
    fi
    printf '%s\n' "$row" >> "$ledger"
    ;;
  *)
    printf 'unexpected SQL statement: %s\n' "$statement" >&2
    exit 2
    ;;
esac
FAKE_K3S
chmod +x "$fake_bin/k3s"

export PATH="$fake_bin:/usr/bin:/bin:$PATH"
export FAKE_K3S_STATE="$state"
export FAKE_MESSAGES_EXISTS=0
export FAKE_CALENDAR_SCHEMA_FINGERPRINT='14:18:4:9:2'

migration="$fixture/database-migrations/V2026071501__migrate_message_type_to_varchar.sql"
expected_checksum="$(sha256sum -- "$migration" | awk '{print $1}')"
calendar_migration="$fixture/database-migrations/V2026071601__backfill_chat_schedule_calendar.sql"
calendar_checksum="$(sha256sum -- "$calendar_migration" | awk '{print $1}')"

missing_schema_state="$test_root/missing-schema-state"
mkdir -p -- "$missing_schema_state"
printf 'V2026071501\tmigrate_message_type_to_varchar\t%s\n' \
  "$expected_checksum" > "$missing_schema_state/ledger.tsv"
: > "$missing_schema_state/apply.log"
export FAKE_K3S_STATE="$missing_schema_state"
export FAKE_CALENDAR_SCHEMA_FINGERPRINT='14:17:4:9:2'
set +e
missing_schema_output="$(bash "$fixture/run-database-migrations.sh" 2>&1)"
missing_schema_status=$?
set -e
[[ "$missing_schema_status" -ne 0 ]] || { echo "missing core schema unexpectedly succeeded" >&2; exit 1; }
grep -Fq 'restore a verified schema+data backup first' <<<"$missing_schema_output"
[[ "$(wc -l < "$missing_schema_state/ledger.tsv")" -eq 1 ]]
[[ "$(wc -l < "$missing_schema_state/apply.log")" -eq 0 ]]
if grep -Fq 'V2026071601' "$missing_schema_state/ledger.tsv"; then
  echo "schema preflight failure unexpectedly recorded V2026071601" >&2
  exit 1
fi

export FAKE_K3S_STATE="$state"
export FAKE_CALENDAR_SCHEMA_FINGERPRINT='14:18:4:9:2'

first_output="$(bash "$fixture/run-database-migrations.sh")"
grep -Fq 'Database migration applied: V2026071501__migrate_message_type_to_varchar.sql' <<<"$first_output"
grep -Fq 'Database migration applied: V2026071601__backfill_chat_schedule_calendar.sql' <<<"$first_output"
grep -Fxq "V2026071501"$'\t'"migrate_message_type_to_varchar"$'\t'"$expected_checksum" "$state/ledger.tsv"
grep -Fxq "V2026071601"$'\t'"backfill_chat_schedule_calendar"$'\t'"$calendar_checksum" "$state/ledger.tsv"
[[ "$(wc -l < "$state/ledger.tsv")" -eq 2 ]]
[[ "$(wc -l < "$state/apply.log")" -eq 2 ]]
grep -Fxq 'messages-absent-noop' "$state/apply.log"
grep -Fxq 'calendar-backfill' "$state/apply.log"

second_output="$(bash "$fixture/run-database-migrations.sh")"
grep -Fq 'Database migration already applied: V2026071501__migrate_message_type_to_varchar.sql' <<<"$second_output"
[[ "$(wc -l < "$state/ledger.tsv")" -eq 2 ]]
[[ "$(wc -l < "$state/apply.log")" -eq 2 ]]

renamed_migration="$fixture/database-migrations/V2026071501__renamed_description.sql"
mv -- "$migration" "$renamed_migration"
set +e
description_drift_output="$(bash "$fixture/run-database-migrations.sh" 2>&1)"
description_drift_status=$?
set -e
[[ "$description_drift_status" -ne 0 ]] || { echo "description drift unexpectedly succeeded" >&2; exit 1; }
grep -Fq 'Database migration description drift: V2026071501__renamed_description.sql' <<<"$description_drift_output"
[[ "$(wc -l < "$state/ledger.tsv")" -eq 2 ]]
[[ "$(wc -l < "$state/apply.log")" -eq 2 ]]
mv -- "$renamed_migration" "$migration"

printf '\n-- deliberate checksum drift for the test fixture\n' >> "$migration"
set +e
drift_output="$(bash "$fixture/run-database-migrations.sh" 2>&1)"
drift_status=$?
set -e
[[ "$drift_status" -ne 0 ]] || { echo "checksum drift unexpectedly succeeded" >&2; exit 1; }
grep -Fq 'Database migration checksum drift: V2026071501__migrate_message_type_to_varchar.sql' <<<"$drift_output"
[[ "$(wc -l < "$state/ledger.tsv")" -eq 2 ]]
[[ "$(wc -l < "$state/apply.log")" -eq 2 ]]

echo "Database migration runner handles first apply, messages-table-absent no-op, schema preflight, repeat, and metadata drift"
