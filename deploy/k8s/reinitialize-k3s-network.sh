#!/usr/bin/env bash
set -Eeuo pipefail

readonly INSTANCE_ID="${1:-}"
readonly CONFIRMATION="${2:-}"
readonly RELEASE_ID="${3:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
# The helper is resolved from the private release directory at runtime.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/k3s-network-common.sh"

readonly NAMESPACE="talk-with-neighbors"
readonly K3S_CONFIG="/etc/rancher/k3s/config.yaml"
readonly K3S_SERVER_DIR="/var/lib/rancher/k3s/server"
readonly K3S_AGENT_DIR="/var/lib/rancher/k3s/agent"
readonly K3S_STORAGE_DIR="/var/lib/rancher/k3s/storage"
readonly K3S_SERVICE_ENV="/etc/systemd/system/k3s.service.env"
readonly K3S_NODE_PASSWORD="/etc/rancher/node/password"
readonly BACKUP_ROOT="/var/lib/talk-with-neighbors/backups"
readonly MIGRATION_ROOT="/var/lib/talk-with-neighbors/migrations"
readonly MIGRATION_MARKER="$MIGRATION_ROOT/k3s-network-v1.done.json"
readonly IN_PROGRESS_MARKER="$MIGRATION_ROOT/k3s-network-v1.in-progress.json"
readonly MYSQL_RESTORE_PENDING="$MIGRATION_ROOT/mysql-restore-v1.pending.json"
readonly MYSQL_RESTORE_DONE="$MIGRATION_ROOT/mysql-restore-v1.done.json"
readonly DESIRED_CONFIG="$SCRIPT_DIR/k3s-server-config.yaml"
readonly BACKUP_DIR="$BACKUP_ROOT/k3s-network-v1-$RELEASE_ID"
readonly SERVER_ARCHIVE="$BACKUP_DIR/server.tar.gz"
readonly OLD_CONFIG_BACKUP="$BACKUP_DIR/k3s-config.yaml"
readonly OLD_SERVICE_ENV_BACKUP="$BACKUP_DIR/k3s.service.env"
readonly OLD_NODE_PASSWORD_BACKUP="$BACKUP_DIR/node-password"
readonly MYSQL_DUMP="$BACKUP_DIR/mysql.sql.gz"
readonly LOCK_FILE="/run/lock/talk-with-neighbors-k3s-network.lock"

original_backend_replicas=""
original_backend_available=""
original_mysql_replicas=""
original_redis_replicas=""
backend_scaled=false
cluster_stopped=false
destructive_started=false
migration_started=false
rollback_running=false

die() {
  echo "$*" >&2
  return 1
}

write_phase() {
  local phase="${1:?phase is required}"
  local temporary
  temporary="$(mktemp "$MIGRATION_ROOT/.k3s-network-v1.XXXXXX")"
  jq -n \
    --arg phase "$phase" \
    --arg release "$RELEASE_ID" \
    --arg backup "$BACKUP_DIR" \
    --arg updatedAt "$(date --utc +%FT%TZ)" \
    '{phase:$phase,release_id:$release,backup_dir:$backup,updated_at:$updatedAt}' \
    > "$temporary"
  chmod 0600 "$temporary"
  mv -f -- "$temporary" "$IN_PROGRESS_MARKER"
}

wait_for_k3s_api() {
  local attempts="${1:-120}"
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if k3s kubectl get --raw=/readyz >/dev/null 2>&1; then
      return 0
    fi
    sleep 5
  done
  return 1
}

wait_for_deployment() {
  local namespace="${1:?namespace is required}"
  local deployment="${2:?deployment is required}"
  local attempts="${3:-120}"
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if k3s kubectl -n "$namespace" get "deployment/$deployment" >/dev/null 2>&1; then
      if k3s kubectl -n "$namespace" rollout status "deployment/$deployment" --timeout=5m; then
        return 0
      fi
      return 1
    fi
    sleep 5
  done
  return 1
}

wait_for_no_pods() {
  local namespace="${1:?namespace is required}"
  local selector="${2:?selector is required}"
  local attempts="${3:-60}"
  local pod_count attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    pod_count="$(k3s kubectl -n "$namespace" get pods -l "$selector" -o json | jq -er '.items | length')"
    if [[ "$pod_count" == "0" ]]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

safe_remove_cluster_state() {
  local path
  for path in \
    "$K3S_SERVER_DIR" \
    "$K3S_AGENT_DIR" \
    /var/lib/cni \
    /etc/cni/net.d \
    /var/lib/kubelet/pods \
    /var/lib/kubelet/plugins \
    /var/lib/kubelet/plugins_registry \
    /run/k3s \
    /run/flannel \
    "$K3S_NODE_PASSWORD"; do
    case "$path" in
      /var/lib/rancher/k3s/server|/var/lib/rancher/k3s/agent|/var/lib/cni|/etc/cni/net.d|/var/lib/kubelet/pods|/var/lib/kubelet/plugins|/var/lib/kubelet/plugins_registry|/run/k3s|/run/flannel|/etc/rancher/node/password)
        ;;
      *)
        echo "Refusing to remove an unexpected path: $path" >&2
        return 1
        ;;
    esac
    if [[ "$path" == "$K3S_STORAGE_DIR" || "$path" == "$K3S_STORAGE_DIR/"* ]]; then
      echo "Refusing to remove local persistent storage" >&2
      return 1
    fi
    if [[ -e "$path" || -L "$path" ]]; then
      rm -rf --one-file-system -- "$path" || return 1
    fi
  done
}

recover_stale_pre_destructive_attempt() {
  if [[ ! -e "$IN_PROGRESS_MARKER" && ! -L "$IN_PROGRESS_MARKER" ]]; then
    return 0
  fi

  local stale_phase stale_release stale_backup expected_backup aborted_marker file_mode artifact
  [[ -f "$IN_PROGRESS_MARKER" && ! -L "$IN_PROGRESS_MARKER" ]] || {
    echo "The previous migration journal is not a regular root-only file" >&2
    return 1
  }
  [[ "$(readlink -e "$IN_PROGRESS_MARKER")" == "$IN_PROGRESS_MARKER" ]] || {
    echo "The previous migration journal resolves outside its expected path" >&2
    return 1
  }
  [[ "$(stat -c '%u' "$IN_PROGRESS_MARKER")" == "0" ]] || {
    echo "The previous migration journal is not owned by root" >&2
    return 1
  }
  file_mode="$(stat -c '%a' "$IN_PROGRESS_MARKER")"
  if [[ ! "$file_mode" =~ ^[0-7]{3,4}$ ]] || (( (8#$file_mode & 077) != 0 )); then
    echo "The previous migration journal is readable outside root" >&2
    return 1
  fi

  stale_phase="$(jq -er '.phase | select(type == "string")' "$IN_PROGRESS_MARKER")" || {
    echo "The previous migration journal has no valid phase" >&2
    return 1
  }
  stale_release="$(jq -er '.release_id | select(type == "string" and test("^[0-9]+-[0-9]+$"))' "$IN_PROGRESS_MARKER")" || {
    echo "The previous migration journal has no valid release id" >&2
    return 1
  }
  stale_backup="$(jq -er '.backup_dir | select(type == "string")' "$IN_PROGRESS_MARKER")" || {
    echo "The previous migration journal has no valid backup path" >&2
    return 1
  }
  expected_backup="$BACKUP_ROOT/k3s-network-v1-$stale_release"

  [[ "$stale_phase" == "preparing-backup" ]] || {
    echo "The previous migration advanced beyond the safe pre-destructive retry phase: $stale_phase" >&2
    return 1
  }
  [[ "$stale_backup" == "$expected_backup" && -d "$stale_backup" && ! -L "$stale_backup" ]] || {
    echo "The previous migration backup path is unsafe" >&2
    return 1
  }
  [[ "$(readlink -e "$stale_backup")" == "$stale_backup" && "$(stat -c '%u' "$stale_backup")" == "0" ]] || {
    echo "The previous migration backup is not a root-owned canonical directory" >&2
    return 1
  }
  file_mode="$(stat -c '%a' "$stale_backup")"
  if [[ ! "$file_mode" =~ ^[0-7]{3,4}$ ]] || (( (8#$file_mode & 077) != 0 )); then
    echo "The previous migration backup directory is accessible outside root" >&2
    return 1
  fi
  [[ -s "$stale_backup/inventory.txt" && ! -L "$stale_backup/inventory.txt" ]] || {
    echo "The previous pre-destructive inventory is missing or unsafe" >&2
    return 1
  }
  file_mode="$(stat -c '%a' "$stale_backup/inventory.txt")"
  if [[ "$(stat -c '%u' "$stale_backup/inventory.txt")" != "0" || ! "$file_mode" =~ ^[0-7]{3,4}$ ]] ||
    (( (8#$file_mode & 077) != 0 )); then
    echo "The previous pre-destructive inventory is accessible outside root" >&2
    return 1
  fi
  if ! grep -Fxq "release_id=$stale_release" "$stale_backup/inventory.txt" ||
    ! grep -Fxq "legacy_pod_cidr=$legacy_pod_cidr" "$stale_backup/inventory.txt" ||
    ! grep -Fxq "backend_replicas=$original_backend_replicas" "$stale_backup/inventory.txt" ||
    ! grep -Fxq "mysql_replicas=$original_mysql_replicas" "$stale_backup/inventory.txt" ||
    ! grep -Fxq "redis_replicas=$original_redis_replicas" "$stale_backup/inventory.txt"; then
    echo "Current workload replicas do not match the previous pre-destructive inventory" >&2
    return 1
  fi
  if grep -q '^backend_available=' "$stale_backup/inventory.txt" &&
    ! grep -Fxq "backend_available=$original_backend_available" "$stale_backup/inventory.txt"; then
    echo "Current backend availability does not match the previous pre-destructive inventory" >&2
    return 1
  fi

  for artifact in server.tar.gz mysql.sql.gz k3s-config.yaml k3s.service.env node-password SHA256SUMS; do
    [[ ! -e "$stale_backup/$artifact" && ! -L "$stale_backup/$artifact" ]] || {
      echo "The previous attempt created destructive-phase artifact $artifact; refusing automatic retry" >&2
      return 1
    }
  done

  aborted_marker="$stale_backup/aborted-before-destructive.json"
  [[ ! -e "$aborted_marker" && ! -L "$aborted_marker" ]] || {
    echo "The previous pre-destructive journal was already archived" >&2
    return 1
  }
  chmod 0600 "$IN_PROGRESS_MARKER" || return 1
  mv -- "$IN_PROGRESS_MARKER" "$aborted_marker" || return 1
  echo "Archived a verified pre-destructive failed attempt at $aborted_marker"
}

restore_workload_replicas() {
  local kind name replicas
  while read -r kind name replicas; do
    [[ "$replicas" =~ ^[0-9]+$ ]] || continue
    if k3s kubectl -n "$NAMESPACE" get "$kind/$name" >/dev/null 2>&1; then
      k3s kubectl -n "$NAMESPACE" scale "$kind/$name" --replicas="$replicas" >/dev/null || return 1
    fi
  done <<EOF
deployment backend $original_backend_replicas
statefulset mysql $original_mysql_replicas
statefulset redis $original_redis_replicas
EOF
  while read -r kind name replicas; do
    [[ "$replicas" =~ ^[1-9][0-9]*$ ]] || continue
    k3s kubectl -n "$NAMESPACE" rollout status "$kind/$name" --timeout=5m || return 1
  done <<EOF
statefulset mysql $original_mysql_replicas
statefulset redis $original_redis_replicas
EOF
  if [[ "$original_backend_available" =~ ^[1-9][0-9]*$ && "$original_backend_replicas" =~ ^[1-9][0-9]*$ ]]; then
    k3s kubectl -n "$NAMESPACE" rollout status deployment/backend --timeout=5m || return 1
  fi
  backend_scaled=false
}

rollback_old_cluster() {
  local rollback_status=0
  [[ "$rollback_running" == false ]] || return 0
  rollback_running=true

  rm -f -- "$MYSQL_RESTORE_PENDING" || rollback_status=1

  if [[ "$destructive_started" == true ]]; then
    echo "Migration failed before commit; restoring the previous k3s server state" >&2
    [[ -s "$SERVER_ARCHIVE" && -s "$OLD_CONFIG_BACKUP" ]] || {
      echo "The root-only rollback archive is incomplete: $BACKUP_DIR" >&2
      return 1
    }
    systemctl stop k3s >/dev/null 2>&1 || true
    /usr/local/bin/k3s-killall.sh >/dev/null 2>&1 || true
    safe_remove_cluster_state || return 1
    install -d -m 0755 /var/lib/rancher/k3s /etc/rancher/k3s
    tar --numeric-owner --xattrs --acls -xzf "$SERVER_ARCHIVE" -C /var/lib/rancher/k3s || return 1
    install -o root -g root -m 0600 "$OLD_CONFIG_BACKUP" "$K3S_CONFIG" || return 1
    if [[ -s "$OLD_SERVICE_ENV_BACKUP" ]]; then
      install -o root -g root -m 0600 "$OLD_SERVICE_ENV_BACKUP" "$K3S_SERVICE_ENV" || return 1
    fi
    if [[ -s "$OLD_NODE_PASSWORD_BACKUP" ]]; then
      install -d -m 0700 /etc/rancher/node
      install -o root -g root -m 0600 "$OLD_NODE_PASSWORD_BACKUP" "$K3S_NODE_PASSWORD" || return 1
    fi
    systemctl start k3s || return 1
    cluster_stopped=false
    wait_for_k3s_api 120 || return 1
    restore_workload_replicas || rollback_status=1
  elif [[ "$cluster_stopped" == true ]]; then
    systemctl start k3s || return 1
    cluster_stopped=false
    wait_for_k3s_api 120 || return 1
    restore_workload_replicas || rollback_status=1
  elif [[ "$backend_scaled" == true ]]; then
    restore_workload_replicas || rollback_status=1
  fi

  if (( rollback_status == 0 )); then
    rm -f -- "$IN_PROGRESS_MARKER" || rollback_status=1
  fi
  if (( rollback_status == 0 )); then
    echo "Previous k3s state restored; backup retained at $BACKUP_DIR" >&2
  else
    echo "Rollback was incomplete; inspect $BACKUP_DIR before retrying" >&2
  fi
  return "$rollback_status"
}

on_exit() {
  local status=$?
  local rollback_status=0
  trap - EXIT
  if (( status != 0 )) && [[ "$migration_started" == true && ! -f "$MIGRATION_MARKER" ]]; then
    set +e
    rollback_old_cluster
    rollback_status=$?
    set -e
    if (( rollback_status != 0 )); then
      echo "CRITICAL: automatic rollback failed; the root-only backup remains at $BACKUP_DIR" >&2
    fi
  fi
  exit "$status"
}

trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ "$EUID" -eq 0 ]] || die "reinitialize-k3s-network.sh must run as root"
[[ "$INSTANCE_ID" =~ ^i-[a-f0-9]{8,17}$ ]] || die "Invalid EC2 instance id"
[[ "$RELEASE_ID" =~ ^[0-9]+-[0-9]+$ ]] || die "Invalid release id"
EXPECTED_CONFIRMATION="$(k3s_expected_reinitialize_confirmation "$INSTANCE_ID")"
readonly EXPECTED_CONFIRMATION
[[ "$CONFIRMATION" == "$EXPECTED_CONFIRMATION" ]] || die "The k3s network confirmation did not match the target instance and CIDRs"

for command in curl flock gzip jq k3s sha256sum systemctl tar; do
  command -v "$command" >/dev/null 2>&1 || die "Required command is unavailable: $command"
done
[[ -x /usr/local/bin/k3s-killall.sh ]] || die "The k3s killall helper is unavailable"
k3s_config_has_desired_network "$DESIRED_CONFIG" || die "The bundled k3s network config is invalid"

umask 077
install -d -m 0700 "$BACKUP_ROOT" "$MIGRATION_ROOT"
exec 9>"$LOCK_FILE"
flock -n 9 || die "Another k3s network migration is running"

[[ ! -e "$MIGRATION_MARKER" ]] || die "The one-time k3s network migration was already completed"
[[ ! -e "$MYSQL_RESTORE_PENDING" && ! -e "$MYSQL_RESTORE_DONE" ]] || die "A MySQL restore marker already exists"
[[ ! -e "$BACKUP_DIR" ]] || die "The release-specific backup directory already exists"
systemctl is-active --quiet k3s || die "k3s must be active before the migration"
[[ -d "$K3S_SERVER_DIR" ]] || die "The existing k3s server state is missing"
[[ -s "$K3S_CONFIG" ]] || die "The existing k3s config is missing"
[[ -d "$K3S_STORAGE_DIR" && ! -L "$K3S_STORAGE_DIR" ]] || die "The local-path storage directory is missing or unsafe"
[[ "$(readlink -e "$K3S_STORAGE_DIR")" == "$K3S_STORAGE_DIR" ]] || die "The local-path storage directory resolves outside its expected path"

imds_token="$(curl --fail --silent --show-error --max-time 5 --noproxy '*' \
  --request PUT \
  --header 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
  http://169.254.169.254/latest/api/token)"
[[ -n "$imds_token" ]] || die "IMDSv2 did not return a token"
actual_instance_id="$(curl --fail --silent --show-error --max-time 5 --noproxy '*' \
  --header "X-aws-ec2-metadata-token: $imds_token" \
  http://169.254.169.254/latest/meta-data/instance-id)"
[[ "$actual_instance_id" == "$INSTANCE_ID" ]] || die "IMDS instance id does not match the confirmed deployment target"
mapfile -t interface_macs < <(curl --fail --silent --show-error --max-time 5 --noproxy '*' \
  --header "X-aws-ec2-metadata-token: $imds_token" \
  http://169.254.169.254/latest/meta-data/network/interfaces/macs/ \
  | sed '/^[[:space:]]*$/d; s#/$##')
[[ "${#interface_macs[@]}" == "1" ]] || die "The migration expects exactly one EC2 network interface"
vpc_cidr="$(curl --fail --silent --show-error --max-time 5 --noproxy '*' \
  --header "X-aws-ec2-metadata-token: $imds_token" \
  "http://169.254.169.254/latest/meta-data/network/interfaces/macs/${interface_macs[0]}/vpc-ipv4-cidr-block")"
[[ "$vpc_cidr" == "10.42.0.0/16" ]] || die "The EC2 VPC CIDR is not the expected conflicting 10.42.0.0/16"

node_count="$(k3s kubectl get nodes -o json | jq -er '.items | length')"
[[ "$node_count" == "1" ]] || die "The migration is limited to exactly one k3s node"
legacy_pod_cidr="$(k3s kubectl get nodes -o json | jq -er '.items[0].spec.podCIDR')"
k3s_is_legacy_pod_cidr "$legacy_pod_cidr" || die "The current node Pod CIDR is not inside the expected legacy 10.42.0.0/16 range"

k3s kubectl -n "$NAMESPACE" get deployment/backend statefulset/mysql statefulset/redis >/dev/null
k3s kubectl -n "$NAMESPACE" rollout status statefulset/mysql --timeout=3m
original_backend_replicas="$(k3s kubectl -n "$NAMESPACE" get deployment/backend -o jsonpath='{.spec.replicas}')"
original_backend_available="$(k3s kubectl -n "$NAMESPACE" get deployment/backend -o json | jq -er '.status.availableReplicas // 0')"
original_mysql_replicas="$(k3s kubectl -n "$NAMESPACE" get statefulset/mysql -o jsonpath='{.spec.replicas}')"
original_redis_replicas="$(k3s kubectl -n "$NAMESPACE" get statefulset/redis -o jsonpath='{.spec.replicas}')"
for replicas in "$original_backend_replicas" "$original_backend_available" "$original_mysql_replicas" "$original_redis_replicas"; do
  [[ "$replicas" =~ ^[0-9]+$ ]] || die "A workload replica count is invalid"
done
(( original_backend_available <= original_backend_replicas )) || die "The backend availability count exceeds its replica count"
(( original_mysql_replicas == 1 )) || die "The migration expects one MySQL replica"
recover_stale_pre_destructive_attempt

server_size_kib="$(du -sk "$K3S_SERVER_DIR" | awk '{print $1}')"
# MYSQL_ROOT_PASSWORD expands inside the container, not in this host shell.
# shellcheck disable=SC2016
database_size_bytes="$(k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --execute="SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = '\''talk_with_neighbors'\''"')"
available_kib="$(df --output=avail "$BACKUP_ROOT" | tail -n 1 | tr -d ' ')"
[[ "$server_size_kib" =~ ^[0-9]+$ && "$database_size_bytes" =~ ^[0-9]+$ && "$available_kib" =~ ^[0-9]+$ ]] || die "Could not determine backup disk capacity"
database_size_kib=$(( (database_size_bytes + 1023) / 1024 ))
required_backup_kib=$(( server_size_kib + (database_size_kib * 2) + 1048576 ))
(( available_kib > required_backup_kib )) || die "At least the server-state size, twice the database size, and 1 GiB must be free for the root-only backup"

install -d -m 0700 "$BACKUP_DIR"
migration_started=true
write_phase "preparing-backup"
printf 'release_id=%s\nlegacy_pod_cidr=%s\nbackend_replicas=%s\nbackend_available=%s\nmysql_replicas=%s\nredis_replicas=%s\n' \
  "$RELEASE_ID" "$legacy_pod_cidr" "$original_backend_replicas" "$original_backend_available" "$original_mysql_replicas" "$original_redis_replicas" \
  > "$BACKUP_DIR/inventory.txt"
k3s kubectl get nodes,persistentvolumes -o yaml > "$BACKUP_DIR/cluster-inventory.yaml"
k3s kubectl -n "$NAMESPACE" get deployments,statefulsets,persistentvolumeclaims -o yaml >> "$BACKUP_DIR/cluster-inventory.yaml"
find "$K3S_STORAGE_DIR" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort > "$BACKUP_DIR/storage-directories.txt"
chmod 0600 "$BACKUP_DIR"/*

backend_scaled=true
k3s kubectl -n "$NAMESPACE" scale deployment/backend --replicas=0 >/dev/null
wait_for_no_pods "$NAMESPACE" 'app.kubernetes.io/name=backend' 60 || die "The backend pod did not stop before the database dump"
write_phase "dumping-mysql"
# MYSQL_ROOT_PASSWORD expands inside the container, not in this host shell.
# shellcheck disable=SC2016
k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --quick --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces --add-drop-database --databases talk_with_neighbors' \
  | gzip -9 > "$MYSQL_DUMP"
[[ -s "$MYSQL_DUMP" ]] || die "The MySQL logical dump is empty"
gzip -t "$MYSQL_DUMP"
chmod 0600 "$MYSQL_DUMP"

write_phase "quiescing-statefulsets"
k3s kubectl -n "$NAMESPACE" scale statefulset/mysql statefulset/redis --replicas=0 >/dev/null
wait_for_no_pods "$NAMESPACE" 'app.kubernetes.io/name=mysql' 60 || die "The MySQL pod did not stop cleanly"
wait_for_no_pods "$NAMESPACE" 'app.kubernetes.io/name=redis' 60 || die "The Redis pod did not stop cleanly"

write_phase "stopping-old-cluster"
cluster_stopped=true
systemctl stop k3s
if systemctl is-active --quiet k3s; then
  die "k3s did not stop cleanly"
fi
/usr/local/bin/k3s-killall.sh >/dev/null

write_phase "archiving-old-server"
install -o root -g root -m 0600 "$K3S_CONFIG" "$OLD_CONFIG_BACKUP"
if [[ -s "$K3S_SERVICE_ENV" ]]; then
  install -o root -g root -m 0600 "$K3S_SERVICE_ENV" "$OLD_SERVICE_ENV_BACKUP"
fi
if [[ -s "$K3S_NODE_PASSWORD" ]]; then
  install -o root -g root -m 0600 "$K3S_NODE_PASSWORD" "$OLD_NODE_PASSWORD_BACKUP"
fi
tar --numeric-owner --xattrs --acls -czf "$SERVER_ARCHIVE" -C /var/lib/rancher/k3s server
tar -tzf "$SERVER_ARCHIVE" >/dev/null
chmod 0600 "$SERVER_ARCHIVE"
(
  cd "$BACKUP_DIR"
  checksum_files=(k3s-config.yaml mysql.sql.gz server.tar.gz)
  [[ -s k3s.service.env ]] && checksum_files+=(k3s.service.env)
  [[ -s node-password ]] && checksum_files+=(node-password)
  sha256sum "${checksum_files[@]}" > SHA256SUMS
  chmod 0600 SHA256SUMS
  sha256sum --check --status SHA256SUMS
)

write_phase "resetting-cluster-state"
destructive_started=true
safe_remove_cluster_state
install -d -m 0755 /etc/rancher/k3s
install -o root -g root -m 0600 "$DESIRED_CONFIG" "$K3S_CONFIG"
systemctl start k3s
cluster_stopped=false

write_phase "verifying-new-cluster"
wait_for_k3s_api 180 || die "The reinitialized k3s API did not become ready"
k3s kubectl wait --for=condition=Ready node --all --timeout=5m
node_count="$(k3s kubectl get nodes -o json | jq -er '.items | length')"
[[ "$node_count" == "1" ]] || die "The reinitialized cluster does not contain exactly one node"
new_pod_cidr="$(k3s kubectl get nodes -o json | jq -er '.items[0].spec.podCIDR')"
k3s_is_desired_pod_cidr "$new_pod_cidr" || die "The node Pod CIDR is outside 10.244.0.0/16"
kubernetes_service_ip="$(k3s kubectl get service kubernetes -o jsonpath='{.spec.clusterIP}')"
k3s_is_desired_service_ip "$kubernetes_service_ip" || die "The Kubernetes service IP is outside 10.96.0.0/16"
kube_dns_ip="$(k3s kubectl -n kube-system get service kube-dns -o jsonpath='{.spec.clusterIP}')"
[[ "$kube_dns_ip" == "$K3S_DESIRED_CLUSTER_DNS" ]] || die "CoreDNS did not receive 10.96.0.10"
wait_for_deployment kube-system coredns 120 || die "CoreDNS did not become available"
wait_for_deployment kube-system local-path-provisioner 120 || die "The local-path provisioner did not become available"
wait_for_deployment kube-system traefik 180 || die "Traefik did not become available"

svclb_daemonset=""
for _ in {1..120}; do
  svclb_daemonset="$(k3s kubectl -n kube-system get daemonsets \
    -l svccontroller.k3s.cattle.io/svcname=traefik \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  [[ -n "$svclb_daemonset" ]] && break
  sleep 5
done
[[ -n "$svclb_daemonset" ]] || die "The Traefik service load balancer DaemonSet was not created"
k3s kubectl -n kube-system rollout status "daemonset/$svclb_daemonset" --timeout=5m

traefik_endpoints=0
for _ in {1..60}; do
  traefik_endpoints="$(k3s kubectl -n kube-system get endpointslices \
    -l kubernetes.io/service-name=traefik -o json 2>/dev/null \
    | jq '[.items[].endpoints[]? | select(.conditions.ready != false) | .addresses[]?] | length' 2>/dev/null || echo 0)"
  [[ "$traefik_endpoints" =~ ^[0-9]+$ ]] && (( traefik_endpoints > 0 )) && break
  sleep 5
done
(( traefik_endpoints > 0 )) || die "Traefik has no ready service endpoint"

install -o root -g root -m 0600 /dev/null "$K3S_SERVER_DIR/node-ready"
dump_sha256="$(sha256sum "$MYSQL_DUMP" | awk '{print $1}')"
pending_tmp="$(mktemp "$MIGRATION_ROOT/.mysql-restore-v1.XXXXXX")"
jq -n \
  --arg dumpPath "$MYSQL_DUMP" \
  --arg sha256 "$dump_sha256" \
  --arg release "$RELEASE_ID" \
  '{dump_path:$dumpPath,sha256:$sha256,release_id:$release}' \
  > "$pending_tmp"
chmod 0600 "$pending_tmp"
mv -f -- "$pending_tmp" "$MYSQL_RESTORE_PENDING"

marker_tmp="$(mktemp "$MIGRATION_ROOT/.k3s-network-v1-done.XXXXXX")"
jq -n \
  --arg release "$RELEASE_ID" \
  --arg backup "$BACKUP_DIR" \
  --arg oldPodCidr "$legacy_pod_cidr" \
  --arg newPodCidr "$new_pod_cidr" \
  --arg completedAt "$(date --utc +%FT%TZ)" \
  '{release_id:$release,backup_dir:$backup,old_pod_cidr:$oldPodCidr,new_pod_cidr:$newPodCidr,completed_at:$completedAt}' \
  > "$marker_tmp"
chmod 0600 "$marker_tmp"
mv -f -- "$marker_tmp" "$MIGRATION_MARKER"
rm -f -- "$IN_PROGRESS_MARKER"

echo "K3s network migration completed; the root-only backup is $BACKUP_DIR"
echo "MySQL restore is pending and will run once before the backend starts"
