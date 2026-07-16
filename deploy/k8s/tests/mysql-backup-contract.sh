#!/usr/bin/env bash
# Literal checks intentionally match unexpanded shell and unit source.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
readonly BACKUP="$SCRIPT_DIR/mysql-backup.sh"
readonly RESTORE="$SCRIPT_DIR/mysql-backup-restore-verify.sh"
readonly INSTALLER="$SCRIPT_DIR/install-mysql-backup.sh"
readonly BUILD_BUNDLE="$SCRIPT_DIR/build-bundle.sh"
readonly DEPLOY_ON_NODE="$SCRIPT_DIR/deploy-on-node.sh"
readonly UNITS="$SCRIPT_DIR/systemd"
readonly MONITOR_WORKFLOW="$SCRIPT_DIR/../../.github/workflows/monitor-mysql-backup.yml"

for required in "$BACKUP" "$RESTORE" "$INSTALLER" "$BUILD_BUNDLE" "$DEPLOY_ON_NODE" \
  "$UNITS/talk-with-neighbors-mysql-backup.service" \
  "$UNITS/talk-with-neighbors-mysql-backup.timer" \
  "$UNITS/talk-with-neighbors-mysql-restore-verify.service" \
  "$UNITS/talk-with-neighbors-mysql-restore-verify.timer" \
  "$MONITOR_WORKFLOW"; do
  [[ -s "$required" ]] || { echo "Missing MySQL backup asset: $required" >&2; exit 1; }
done

grep -Fq 'exec 8>"$DEPLOY_LOCK"' "$BACKUP"
grep -Fq 'flock -n 9' "$BACKUP"
grep -Fq -- '--single-transaction' "$BACKUP"
grep -Fq -- '--set-gtid-purged=OFF' "$BACKUP"
grep -Fq -- '--no-tablespaces' "$BACKUP"
if grep -Eq -- '--databases|--add-drop-database' < <(grep -v '^#' "$BACKUP"); then
  echo "The backup must remain portable across isolated restore schemas" >&2
  exit 1
fi
grep -Fq 'gzip -t "$dump_path"' "$BACKUP"
grep -Fq '.ServerSideEncryption == "AES256"' "$BACKUP"
dump_upload_line="$(grep -nF -m1 'aws s3 cp "$dump_path"' "$BACKUP" | cut -d: -f1)"
manifest_upload_line="$(grep -nF -m1 'aws s3 cp "$manifest_path"' "$BACKUP" | cut -d: -f1)"
[[ "$dump_upload_line" =~ ^[0-9]+$ && "$manifest_upload_line" =~ ^[0-9]+$ && "$dump_upload_line" -lt "$manifest_upload_line" ]] || {
  echo "The backup manifest must be uploaded after the verified dump" >&2
  exit 1
}

grep -Fq 'DROP DATABASE IF EXISTS' "$RESTORE"
grep -Fq 'app_schema_migrations' "$RESTORE"
grep -Fq 'CHECK TABLE' "$RESTORE"
grep -Fq 'exec mysql --user=root --batch --skip-column-names --raw' "$RESTORE"
grep -Fq 'checked != expected' "$RESTORE"
if grep -Fq 'mysqlcheck' < <(grep -v '^#' "$RESTORE"); then
  echo "Restore verification must work with the MySQL 8.4 image, which does not ship mysqlcheck" >&2
  exit 1
fi
grep -Fq 'mysql/daily/' "$RESTORE"
grep -Fq 'MYSQL_BACKUP_BUCKET' "$BUILD_BUNDLE"
grep -Fq 'install-mysql-backup.sh' "$BUILD_BUNDLE"
grep -Fq 'before-migration-' "$DEPLOY_ON_NODE"

grep -Fxq 'Persistent=true' "$UNITS/talk-with-neighbors-mysql-backup.timer"
grep -Fxq 'RandomizedDelaySec=20m' "$UNITS/talk-with-neighbors-mysql-backup.timer"
grep -Fxq 'Persistent=true' "$UNITS/talk-with-neighbors-mysql-restore-verify.timer"
grep -Fxq 'ProtectSystem=strict' "$UNITS/talk-with-neighbors-mysql-backup.service"
grep -Fxq 'NoNewPrivileges=true' "$UNITS/talk-with-neighbors-mysql-backup.service"
grep -Fq 'monitor-started-at' "$INSTALLER"
grep -Fq 'install_age="$((now - installed_at))"' "$MONITOR_WORKFLOW"
grep -Fq 'elif test "$install_age" -le 691200; then' "$MONITOR_WORKFLOW"
if grep -Fq 'elif test "$backup_age" -le 691200; then' "$MONITOR_WORKFLOW"; then
  echo "Daily backups must not renew the initial restore-verification grace period" >&2
  exit 1
fi

echo "MySQL backups are encrypted, committed manifest-last, serialized, and restore-verified"
