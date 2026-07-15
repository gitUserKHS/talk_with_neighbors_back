#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_DIR="${1:?release directory is required}"
readonly SYSTEMD_SOURCE="$SOURCE_DIR/systemd"
readonly CONFIG_SOURCE="$SOURCE_DIR/mysql-backup.conf"
readonly STATUS_ROOT="/var/lib/talk-with-neighbors/backup-status"
readonly MONITOR_STARTED_AT="$STATUS_ROOT/monitor-started-at"

[[ "$EUID" -eq 0 ]] || { echo "install-mysql-backup.sh must run as root" >&2; exit 1; }
[[ -d "$SOURCE_DIR" && ! -L "$SOURCE_DIR" ]] || { echo "Unsafe release directory" >&2; exit 1; }
for path in \
  "$SOURCE_DIR/mysql-backup.sh" \
  "$SOURCE_DIR/mysql-backup-restore-verify.sh" \
  "$CONFIG_SOURCE" \
  "$SYSTEMD_SOURCE/talk-with-neighbors-mysql-backup.service" \
  "$SYSTEMD_SOURCE/talk-with-neighbors-mysql-backup.timer" \
  "$SYSTEMD_SOURCE/talk-with-neighbors-mysql-restore-verify.service" \
  "$SYSTEMD_SOURCE/talk-with-neighbors-mysql-restore-verify.timer"; do
  [[ -s "$path" && ! -L "$path" ]] || { echo "Missing or unsafe backup asset: $path" >&2; exit 1; }
done

grep -Eq '^MYSQL_BACKUP_BUCKET=[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$' "$CONFIG_SOURCE"
grep -Fxq 'MYSQL_BACKUP_PREFIX=mysql' "$CONFIG_SOURCE"

install -o root -g root -m 0750 "$SOURCE_DIR/mysql-backup.sh" /usr/local/sbin/talk-with-neighbors-mysql-backup
install -o root -g root -m 0750 "$SOURCE_DIR/mysql-backup-restore-verify.sh" /usr/local/sbin/talk-with-neighbors-mysql-restore-verify
install -o root -g root -m 0700 -d /etc/talk-with-neighbors "$STATUS_ROOT"
if [[ ! -e "$MONITOR_STARTED_AT" ]]; then
  marker_tmp="$(mktemp "$STATUS_ROOT/.monitor-started-at.XXXXXX")"
  trap 'rm -f -- "${marker_tmp:-}"' EXIT
  date -u +%s > "$marker_tmp"
  chown root:root "$marker_tmp"
  chmod 0600 "$marker_tmp"
  mv -- "$marker_tmp" "$MONITOR_STARTED_AT"
  marker_tmp=""
  trap - EXIT
fi
[[ -f "$MONITOR_STARTED_AT" && ! -L "$MONITOR_STARTED_AT" ]] || { echo "Unsafe backup monitor installation marker" >&2; exit 1; }
[[ "$(stat -c '%u:%a' "$MONITOR_STARTED_AT")" == "0:600" ]] || { echo "Backup monitor installation marker permissions are invalid" >&2; exit 1; }
grep -Eq '^[0-9]{10}$' "$MONITOR_STARTED_AT" || { echo "Backup monitor installation marker is invalid" >&2; exit 1; }
install -o root -g root -m 0600 "$CONFIG_SOURCE" /etc/talk-with-neighbors/mysql-backup.conf
for unit in "$SYSTEMD_SOURCE"/*.service "$SYSTEMD_SOURCE"/*.timer; do
  install -o root -g root -m 0644 "$unit" "/etc/systemd/system/$(basename "$unit")"
done

systemctl daemon-reload
systemctl enable --now talk-with-neighbors-mysql-backup.timer talk-with-neighbors-mysql-restore-verify.timer >/dev/null
systemctl is-enabled --quiet talk-with-neighbors-mysql-backup.timer
systemctl is-enabled --quiet talk-with-neighbors-mysql-restore-verify.timer
echo "MySQL backup and restore-verification timers are installed"
