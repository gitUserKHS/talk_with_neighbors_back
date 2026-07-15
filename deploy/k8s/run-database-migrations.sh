#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly MIGRATIONS_DIR="$SCRIPT_DIR/database-migrations"
readonly NAMESPACE="talk-with-neighbors"
readonly DATABASE="talk_with_neighbors"
readonly LEDGER_TABLE="app_schema_migrations"

mysql_root_execute() {
  local statement="$1"
  # MYSQL_ROOT_PASSWORD and positional arguments expand inside the MySQL container.
  # shellcheck disable=SC2016
  k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database="$1" --batch --skip-column-names --raw --execute="$2"' \
    sh "$DATABASE" "$statement"
}

mysql_root_apply_file() {
  local migration_file="$1"
  # MYSQL_ROOT_PASSWORD and the database argument expand inside the MySQL container.
  # shellcheck disable=SC2016
  k3s kubectl -n "$NAMESPACE" exec -i statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database="$1" --binary-mode=1' \
    sh "$DATABASE" < "$migration_file"
}

[[ "$EUID" -eq 0 ]] || { echo "run-database-migrations.sh must run as root" >&2; exit 1; }
[[ -d "$MIGRATIONS_DIR" && ! -L "$MIGRATIONS_DIR" ]] || {
  echo "Database migration directory is missing or unsafe" >&2
  exit 1
}

mapfile -t migration_files < <(
  find "$MIGRATIONS_DIR" -mindepth 1 -maxdepth 1 -type f -name 'V*.sql' -print | sort
)
(( ${#migration_files[@]} > 0 )) || { echo "No database migrations were bundled" >&2; exit 1; }

mysql_root_execute "
  CREATE TABLE IF NOT EXISTS ${LEDGER_TABLE} (
    version VARCHAR(32) NOT NULL PRIMARY KEY,
    description VARCHAR(128) NOT NULL,
    checksum CHAR(64) NOT NULL,
    applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
  ) ENGINE=InnoDB
"

for migration_file in "${migration_files[@]}"; do
  [[ -f "$migration_file" && ! -L "$migration_file" ]] || {
    echo "Database migration is missing or unsafe: $migration_file" >&2
    exit 1
  }

  filename="$(basename -- "$migration_file")"
  if [[ ! "$filename" =~ ^(V[0-9]{10})__([a-z0-9_]+)\.sql$ ]]; then
    echo "Invalid database migration filename: $filename" >&2
    exit 1
  fi
  version="${BASH_REMATCH[1]}"
  description="${BASH_REMATCH[2]}"
  checksum="$(sha256sum -- "$migration_file" | awk '{print $1}')"
  [[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid migration checksum" >&2; exit 1; }

  applied_record="$(mysql_root_execute \
    "SELECT CONCAT(checksum, ':', description) FROM ${LEDGER_TABLE} WHERE version = '${version}'")"
  if [[ -n "$applied_record" ]]; then
    applied_checksum="${applied_record%%:*}"
    applied_description="${applied_record#*:}"
    [[ "$applied_checksum" == "$checksum" ]] || {
      echo "Database migration checksum drift: $filename" >&2
      exit 1
    }
    [[ "$applied_description" == "$description" ]] || {
      echo "Database migration description drift: $filename" >&2
      exit 1
    }
    echo "Database migration already applied: $filename"
    continue
  fi

  mysql_root_apply_file "$migration_file"
  mysql_root_execute \
    "INSERT INTO ${LEDGER_TABLE} (version, description, checksum) VALUES ('${version}', '${description}', '${checksum}')"
  echo "Database migration applied: $filename"
done
