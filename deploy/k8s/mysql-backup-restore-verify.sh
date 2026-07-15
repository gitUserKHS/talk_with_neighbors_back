#!/usr/bin/env bash
set -euo pipefail

readonly NAMESPACE="talk-with-neighbors"
readonly STATUS_ROOT="/var/lib/talk-with-neighbors/backup-status"
readonly DEPLOY_LOCK="/run/lock/talk-with-neighbors-deploy.lock"
readonly BACKUP_LOCK="/run/lock/talk-with-neighbors-mysql-backup.lock"
readonly MYSQL_BACKUP_BUCKET="${MYSQL_BACKUP_BUCKET:?MYSQL_BACKUP_BUCKET is required}"
readonly MYSQL_BACKUP_PREFIX="${MYSQL_BACKUP_PREFIX:-mysql}"

[[ "$EUID" -eq 0 ]] || { echo "mysql-backup-restore-verify.sh must run as root" >&2; exit 1; }
[[ "$MYSQL_BACKUP_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid MySQL backup bucket" >&2; exit 1; }
[[ "$MYSQL_BACKUP_PREFIX" == "mysql" ]] || { echo "MySQL backup prefix must be mysql" >&2; exit 1; }
for command_name in aws flock gzip jq k3s sha256sum timeout; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 1; }
done

umask 077
install -o root -g root -m 0700 -d "$STATUS_ROOT"
exec 8>"$DEPLOY_LOCK"
flock -w 900 8 || { echo "Timed out waiting for the deployment lock" >&2; exit 1; }
exec 9>"$BACKUP_LOCK"
flock -n 9 || { echo "Another MySQL backup or restore verification is running" >&2; exit 1; }

work_dir="$(mktemp -d "$STATUS_ROOT/restore.XXXXXX")"
listing_path="$work_dir/listing.json"
manifest_path="$work_dir/manifest.json"
dump_path="$work_dir/talk_with_neighbors.sql.gz"
head_path="$work_dir/head.json"
status_tmp="$STATUS_ROOT/latest-restore-verification.json.tmp"
scratch_schema=""

# Passwords expand only inside the MySQL container.
# shellcheck disable=SC2016
mysql_root_query() {
  k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --raw --execute="$1"' \
    sh "$1"
}

cleanup() {
  if [[ -n "$scratch_schema" ]]; then
    mysql_root_query "DROP DATABASE IF EXISTS \`${scratch_schema}\`" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir"
  rm -f -- "$status_tmp"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

k3s kubectl -n "$NAMESPACE" wait --for=condition=Ready pod \
  -l app.kubernetes.io/name=mysql --timeout=5m >/dev/null

aws s3api list-objects-v2 \
  --bucket "$MYSQL_BACKUP_BUCKET" \
  --prefix "${MYSQL_BACKUP_PREFIX}/daily/" \
  --output json > "$listing_path"
manifest_key="$(jq -er '
  [.Contents[]? | select(.Key | test("^mysql/daily/[0-9]{4}/[0-9]{2}/[0-9]{2}/talk_with_neighbors-[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9-]{0,63}-[0-9]+[.]manifest[.]json$"))]
  | sort_by(.LastModified) | reverse | .[0].Key // empty
' "$listing_path")"
[[ -n "$manifest_key" ]] || { echo "No committed MySQL backup manifest exists" >&2; exit 1; }

aws s3 cp "s3://${MYSQL_BACKUP_BUCKET}/${manifest_key}" "$manifest_path" --only-show-errors
jq -e '
  .schemaVersion == 1 and
  .database == "talk_with_neighbors" and
  (.dumpKey | type == "string" and test("^mysql/daily/[0-9]{4}/[0-9]{2}/[0-9]{2}/talk_with_neighbors-[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9-]{0,63}-[0-9]+[.]sql[.]gz$")) and
  (.sha256 | type == "string" and test("^[a-f0-9]{64}$")) and
  (.compressedBytes | type == "number" and . > 0) and
  (.databaseBytes | type == "number" and . >= 0) and
  (.tableCount | type == "number" and . > 0)
' "$manifest_path" >/dev/null
dump_key="$(jq -r '.dumpKey' "$manifest_path")"
expected_sha256="$(jq -r '.sha256' "$manifest_path")"
expected_bytes="$(jq -r '.compressedBytes' "$manifest_path")"
expected_database_bytes="$(jq -r '.databaseBytes' "$manifest_path")"
expected_table_count="$(jq -r '.tableCount' "$manifest_path")"

aws s3 cp "s3://${MYSQL_BACKUP_BUCKET}/${dump_key}" "$dump_path" --only-show-errors
aws s3api head-object --bucket "$MYSQL_BACKUP_BUCKET" --key "$dump_key" > "$head_path"
jq -e \
  --arg sha "$expected_sha256" \
  --argjson bytes "$expected_bytes" \
  '.ServerSideEncryption == "AES256" and .ContentLength == $bytes and .Metadata.sha256 == $sha' \
  "$head_path" >/dev/null
[[ "$(stat -c '%s' "$dump_path")" == "$expected_bytes" ]] || { echo "Downloaded backup size does not match its manifest" >&2; exit 1; }
[[ "$(sha256sum "$dump_path" | awk '{print $1}')" == "$expected_sha256" ]] || { echo "Downloaded backup checksum does not match its manifest" >&2; exit 1; }
gzip -t "$dump_path"

available_bytes="$(( $(df -Pk "$STATUS_ROOT" | awk 'NR == 2 { print $4 }') * 1024 ))"
required_bytes=$(( expected_database_bytes * 2 + 268435456 ))
(( available_bytes > required_bytes )) || { echo "Insufficient disk space for an isolated restore verification" >&2; exit 1; }

scratch_schema="twn_restore_verify_$(date -u +'%Y%m%d%H%M%S')_$$"
[[ "$scratch_schema" =~ ^twn_restore_verify_[0-9]{14}_[0-9]+$ ]] || { echo "Unsafe scratch schema name" >&2; exit 1; }
mysql_root_query "CREATE DATABASE \`${scratch_schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >/dev/null

# The dump contains no CREATE/USE/DROP DATABASE statements, so the generated
# scratch schema is the only target of this restore drill.
# shellcheck disable=SC2016
gzip -cd -- "$dump_path" | timeout 1800 k3s kubectl -n "$NAMESPACE" exec -i statefulset/mysql -- sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database="$1" --binary-mode=1' \
  sh "$scratch_schema"

restored_table_count="$(mysql_root_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${scratch_schema}'")"
[[ "$restored_table_count" == "$expected_table_count" ]] || { echo "Restore verification table count does not match the manifest" >&2; exit 1; }
migration_table_count="$(mysql_root_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${scratch_schema}' AND table_name = 'app_schema_migrations'")"
[[ "$migration_table_count" == "1" ]] || { echo "Restore verification is missing app_schema_migrations" >&2; exit 1; }
# shellcheck disable=SC2016
k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqlcheck --user=root --check "$1"' \
  sh "$scratch_schema" >/dev/null
mysql_root_query "DROP DATABASE \`${scratch_schema}\`" >/dev/null
scratch_schema=""

verified_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg verifiedAt "$verified_at" \
  --arg manifestKey "$manifest_key" \
  --arg sha256 "$expected_sha256" \
  --argjson tableCount "$restored_table_count" \
  '{schemaVersion:1,status:"succeeded",verifiedAt:$verifiedAt,manifestKey:$manifestKey,sha256:$sha256,tableCount:$tableCount}' \
  > "$status_tmp"
chmod 0600 "$status_tmp"
mv -f -- "$status_tmp" "$STATUS_ROOT/latest-restore-verification.json"
echo "Latest S3 MySQL backup restored and checked in an isolated schema"
