#!/usr/bin/env bash
set -euo pipefail

readonly NAMESPACE="talk-with-neighbors"
readonly DATABASE="talk_with_neighbors"
readonly STATUS_ROOT="/var/lib/talk-with-neighbors/backup-status"
readonly DEPLOY_LOCK="/run/lock/talk-with-neighbors-deploy.lock"
readonly BACKUP_LOCK="/run/lock/talk-with-neighbors-mysql-backup.lock"
readonly MYSQL_BACKUP_BUCKET="${MYSQL_BACKUP_BUCKET:?MYSQL_BACKUP_BUCKET is required}"
readonly MYSQL_BACKUP_PREFIX="${MYSQL_BACKUP_PREFIX:-mysql}"

lock_held=false
reason="scheduled"
while (($# > 0)); do
  case "$1" in
    --lock-held)
      lock_held=true
      ;;
    --reason)
      shift
      reason="${1:-}"
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

[[ "$EUID" -eq 0 ]] || { echo "mysql-backup.sh must run as root" >&2; exit 1; }
[[ "$MYSQL_BACKUP_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid MySQL backup bucket" >&2; exit 1; }
[[ "$MYSQL_BACKUP_PREFIX" == "mysql" ]] || { echo "MySQL backup prefix must be mysql" >&2; exit 1; }
[[ "$reason" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || { echo "Invalid backup reason" >&2; exit 1; }

for command_name in aws flock gzip jq k3s sha256sum timeout; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 1; }
done

umask 077
install -o root -g root -m 0700 -d "$STATUS_ROOT"

if [[ "$lock_held" != true ]]; then
  exec 8>"$DEPLOY_LOCK"
  flock -w 900 8 || { echo "Timed out waiting for the deployment lock" >&2; exit 1; }
fi
exec 9>"$BACKUP_LOCK"
flock -n 9 || { echo "Another MySQL backup or restore verification is running" >&2; exit 1; }

work_dir="$(mktemp -d "$STATUS_ROOT/backup.XXXXXX")"
dump_path="$work_dir/talk_with_neighbors.sql.gz"
manifest_path="$work_dir/manifest.json"
head_path="$work_dir/head.json"
status_tmp="$STATUS_ROOT/latest-backup.json.tmp"
cleanup() {
  rm -rf -- "$work_dir"
  rm -f -- "$status_tmp"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

k3s kubectl -n "$NAMESPACE" wait --for=condition=Ready pod \
  -l app.kubernetes.io/name=mysql --timeout=5m >/dev/null

# Passwords expand only inside the MySQL container and never appear in argv or logs.
# shellcheck disable=SC2016
mysql_root_query() {
  k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database="$1" --batch --skip-column-names --raw --execute="$2"' \
    sh "$DATABASE" "$1"
}

table_count="$(mysql_root_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")"
database_bytes="$(mysql_root_query "SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = DATABASE()")"
mysql_version="$(mysql_root_query "SELECT VERSION()")"
[[ "$table_count" =~ ^[0-9]+$ ]] || { echo "Could not determine MySQL table count" >&2; exit 1; }
[[ "$database_bytes" =~ ^[0-9]+$ ]] || { echo "Could not determine MySQL database size" >&2; exit 1; }
if (( table_count == 0 )); then
  echo "MySQL backup skipped because the application schema has no tables yet"
  exit 0
fi

available_bytes="$(( $(df -Pk "$STATUS_ROOT" | awk 'NR == 2 { print $4 }') * 1024 ))"
required_bytes=$(( database_bytes + 134217728 ))
(( available_bytes > required_bytes )) || { echo "Insufficient disk space for a safe logical backup" >&2; exit 1; }

# --databases and --add-drop-database are deliberately omitted so the dump can
# be restored into an isolated verification schema without touching production.
# shellcheck disable=SC2016
timeout 1800 k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump \
    --user=root \
    --single-transaction \
    --quick \
    --skip-lock-tables \
    --routines \
    --events \
    --triggers \
    --hex-blob \
    --set-gtid-purged=OFF \
    --no-tablespaces \
    --default-character-set=utf8mb4 \
    "$MYSQL_DATABASE"
' | gzip -9 > "$dump_path"

[[ -s "$dump_path" ]] || { echo "MySQL dump is empty" >&2; exit 1; }
gzip -t "$dump_path"
dump_sha256="$(sha256sum "$dump_path" | awk '{print $1}')"
dump_bytes="$(stat -c '%s' "$dump_path")"
[[ "$dump_sha256" =~ ^[a-f0-9]{64}$ && "$dump_bytes" =~ ^[0-9]+$ ]] || { echo "Invalid dump metadata" >&2; exit 1; }

created_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
date_path="$(date -u +'%Y/%m/%d')"
object_base="${MYSQL_BACKUP_PREFIX}/daily/${date_path}/talk_with_neighbors-${timestamp}-${reason}-$$"
dump_key="${object_base}.sql.gz"
manifest_key="${object_base}.manifest.json"

aws s3 cp "$dump_path" "s3://${MYSQL_BACKUP_BUCKET}/${dump_key}" \
  --sse AES256 \
  --metadata "sha256=${dump_sha256},database=${DATABASE}" \
  --only-show-errors
aws s3api head-object --bucket "$MYSQL_BACKUP_BUCKET" --key "$dump_key" > "$head_path"
jq -e \
  --arg sha "$dump_sha256" \
  --argjson bytes "$dump_bytes" \
  '.ServerSideEncryption == "AES256" and .ContentLength == $bytes and .Metadata.sha256 == $sha' \
  "$head_path" >/dev/null

jq -n \
  --arg createdAt "$created_at" \
  --arg database "$DATABASE" \
  --arg dumpKey "$dump_key" \
  --arg mysqlVersion "$mysql_version" \
  --arg reason "$reason" \
  --arg sha256 "$dump_sha256" \
  --argjson compressedBytes "$dump_bytes" \
  --argjson databaseBytes "$database_bytes" \
  --argjson tableCount "$table_count" \
  '{schemaVersion:1,createdAt:$createdAt,database:$database,dumpKey:$dumpKey,sha256:$sha256,compressedBytes:$compressedBytes,databaseBytes:$databaseBytes,tableCount:$tableCount,mysqlVersion:$mysqlVersion,reason:$reason,restoreVerified:false}' \
  > "$manifest_path"

# The manifest is the commit marker and is intentionally uploaded last.
aws s3 cp "$manifest_path" "s3://${MYSQL_BACKUP_BUCKET}/${manifest_key}" \
  --sse AES256 \
  --content-type application/json \
  --only-show-errors
[[ "$(aws s3api head-object --bucket "$MYSQL_BACKUP_BUCKET" --key "$manifest_key" --query ServerSideEncryption --output text)" == "AES256" ]] || {
  echo "Backup manifest is not encrypted with SSE-S3" >&2
  exit 1
}

jq -n \
  --arg createdAt "$created_at" \
  --arg manifestKey "$manifest_key" \
  --arg sha256 "$dump_sha256" \
  --argjson compressedBytes "$dump_bytes" \
  '{schemaVersion:1,status:"succeeded",createdAt:$createdAt,manifestKey:$manifestKey,sha256:$sha256,compressedBytes:$compressedBytes}' \
  > "$status_tmp"
chmod 0600 "$status_tmp"
mv -f -- "$status_tmp" "$STATUS_ROOT/latest-backup.json"
echo "Verified encrypted MySQL backup committed: ${manifest_key}"
