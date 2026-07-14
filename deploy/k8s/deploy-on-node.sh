#!/usr/bin/env bash
set -euo pipefail

readonly BACKEND_IMAGE="${1:-}"
readonly FRONTEND_IMAGE="${2:-}"
readonly RELEASE_ID="${3:-}"
RELEASE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly RELEASE_DIR
# The helper is resolved from the private release directory at runtime.
# shellcheck disable=SC1091
source "$RELEASE_DIR/k3s-network-common.sh"
readonly NAMESPACE="talk-with-neighbors"
readonly K3S_CONFIG="/etc/rancher/k3s/config.yaml"
readonly BACKUP_ROOT="/var/lib/talk-with-neighbors/backups"
readonly MIGRATION_ROOT="/var/lib/talk-with-neighbors/migrations"
readonly NETWORK_MIGRATION_DONE="$MIGRATION_ROOT/k3s-network-v1.done.json"
readonly MYSQL_RESTORE_PENDING="$MIGRATION_ROOT/mysql-restore-v1.pending.json"
readonly MYSQL_RESTORE_DONE="$MIGRATION_ROOT/mysql-restore-v1.done.json"
readonly BACKEND_PLACEHOLDER="ghcr.io/gituserkhs/talk_with_neighbors_back@sha256:0000000000000000000000000000000000000000000000000000000000000000"
readonly FRONTEND_PLACEHOLDER="ghcr.io/gituserkhs/talk_with_neighbors_front@sha256:0000000000000000000000000000000000000000000000000000000000000000"

cleanup_plaintext_secrets() {
  local path
  for path in "$RELEASE_DIR/app-secrets.json" "$RELEASE_DIR/ghcr-pull.json"; do
    if [[ -f "$path" ]]; then
      shred -u -- "$path" 2>/dev/null || rm -f -- "$path"
    fi
  done
}

verify_desired_cluster_network() {
  local node_count pod_cidr kubernetes_service_ip kube_dns_ip
  k3s_config_has_desired_network "$K3S_CONFIG" || {
    echo "The installed k3s config does not use the non-overlapping network contract" >&2
    return 1
  }
  node_count="$(k3s kubectl get nodes -o json | jq -er '.items | length')"
  [[ "$node_count" == "1" ]] || { echo "Expected exactly one k3s node" >&2; return 1; }
  pod_cidr="$(k3s kubectl get nodes -o json | jq -er '.items[0].spec.podCIDR')"
  k3s_is_desired_pod_cidr "$pod_cidr" || { echo "The node Pod CIDR is outside 10.244.0.0/16" >&2; return 1; }
  kubernetes_service_ip="$(k3s kubectl get service kubernetes -o jsonpath='{.spec.clusterIP}')"
  k3s_is_desired_service_ip "$kubernetes_service_ip" || { echo "The Kubernetes service IP is outside 10.96.0.0/16" >&2; return 1; }
  kube_dns_ip="$(k3s kubectl -n kube-system get service kube-dns -o jsonpath='{.spec.clusterIP}')"
  [[ "$kube_dns_ip" == "$K3S_DESIRED_CLUSTER_DNS" ]] || { echo "CoreDNS is not using 10.96.0.10" >&2; return 1; }
}

wait_for_traefik_https_config() {
  local deployment_json acme_claim phase

  for _ in {1..120}; do
    deployment_json="$("${kubectl[@]}" -n kube-system get deployment traefik -o json 2>/dev/null || true)"
    if [[ -n "$deployment_json" ]] && jq -e '
      [.spec.template.spec.containers[] | select(.name == "traefik")][0] as $container |
      ($container.args // []) as $args |
      ($args | index("--certificatesresolvers.letsencrypt.acme.storage=/data/acme.json")) != null and
      ($args | index("--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web")) != null and
      ($args | index("--entrypoints.web.http.redirections.entrypoint.to=websecure")) != null and
      ($args | index("--entrypoints.web.http.redirections.entrypoint.scheme=https")) != null and
      (.spec.template.spec.securityContext.fsGroup == 65532)
    ' <<<"$deployment_json" >/dev/null; then
      break
    fi
    sleep 5
  done

  if [[ -z "${deployment_json:-}" ]]; then
    echo "Traefik did not render the HTTPS/ACME configuration within ten minutes" >&2
    return 1
  fi

  if ! jq -e '
    [.spec.template.spec.containers[] | select(.name == "traefik")][0].args as $args |
    ($args | index("--certificatesresolvers.letsencrypt.acme.storage=/data/acme.json")) != null and
    (.spec.template.spec.securityContext.fsGroup == 65532)
  ' <<<"$deployment_json" >/dev/null; then
    echo "Traefik did not render the HTTPS/ACME configuration within ten minutes" >&2
    return 1
  fi

  acme_claim="$(jq -er '.spec.template.spec.volumes[] | select(.name == "data") | .persistentVolumeClaim.claimName' <<<"$deployment_json")"
  for _ in {1..60}; do
    phase="$("${kubectl[@]}" -n kube-system get "pvc/${acme_claim}" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    [[ "$phase" == "Bound" ]] && break
    sleep 5
  done
  [[ "${phase:-}" == "Bound" ]] || { echo "Traefik ACME storage PVC did not bind" >&2; return 1; }
  "${kubectl[@]}" -n kube-system rollout status deployment/traefik --timeout=5m
}

restore_pending_mysql_dump() {
  local dump_path expected_sha256 actual_path file_mode table_count restore_error
  [[ -f "$MYSQL_RESTORE_PENDING" ]] || return 0
  [[ ! -e "$MYSQL_RESTORE_DONE" ]] || { echo "Both pending and completed MySQL restore markers exist" >&2; return 1; }

  dump_path="$(jq -er '.dump_path | select(type == "string")' "$MYSQL_RESTORE_PENDING")"
  expected_sha256="$(jq -er '.sha256 | select(type == "string" and test("^[a-f0-9]{64}$"))' "$MYSQL_RESTORE_PENDING")"
  case "$dump_path" in
    "$BACKUP_ROOT"/k3s-network-v1-*/mysql.sql.gz)
      ;;
    *)
      echo "The pending MySQL dump path is outside the root-only migration backups" >&2
      return 1
      ;;
  esac
  [[ -f "$dump_path" && ! -L "$dump_path" ]] || { echo "The pending MySQL dump is missing or unsafe" >&2; return 1; }
  actual_path="$(readlink -e "$dump_path")"
  [[ "$actual_path" == "$dump_path" ]] || { echo "The pending MySQL dump resolves to an unexpected path" >&2; return 1; }
  [[ "$(stat -c '%u' "$dump_path")" == "0" ]] || { echo "The pending MySQL dump is not owned by root" >&2; return 1; }
  file_mode="$(stat -c '%a' "$dump_path")"
  (( (8#$file_mode & 077) == 0 )) || { echo "The pending MySQL dump is readable outside root" >&2; return 1; }
  printf '%s  %s\n' "$expected_sha256" "$dump_path" | sha256sum --check --status
  gzip -t "$dump_path"

  restore_error="$MIGRATION_ROOT/mysql-restore-v1.error.log"
  install -o root -g root -m 0600 /dev/null "$restore_error"
  # MYSQL_ROOT_PASSWORD expands inside the container, not in this host shell.
  # shellcheck disable=SC2016
  if ! { gzip -cd -- "$dump_path" | k3s kubectl -n "$NAMESPACE" exec -i statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --binary-mode=1'; } 2>"$restore_error"; then
    echo "The MySQL restore failed; root-only details remain at $restore_error" >&2
    return 1
  fi
  rm -f -- "$restore_error"
  # MYSQL_ROOT_PASSWORD expands inside the container, not in this host shell.
  # shellcheck disable=SC2016
  table_count="$(k3s kubectl -n "$NAMESPACE" exec statefulset/mysql -- sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '\''talk_with_neighbors'\''"')"
  if [[ ! "$table_count" =~ ^[0-9]+$ ]] || (( table_count == 0 )); then
    echo "The restored application database contains no tables" >&2
    return 1
  fi

  mv -- "$MYSQL_RESTORE_PENDING" "$MYSQL_RESTORE_DONE"
  echo "The pending MySQL logical backup was restored exactly once"
}

trap cleanup_plaintext_secrets EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ "$EUID" -eq 0 ]] || { echo "deploy-on-node.sh must run as root" >&2; exit 1; }
[[ "$BACKEND_IMAGE" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$ ]] || { echo "Invalid backend digest" >&2; exit 1; }
[[ "$FRONTEND_IMAGE" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$ ]] || { echo "Invalid frontend digest" >&2; exit 1; }
[[ "$RELEASE_ID" =~ ^[0-9]+-[0-9]+$ ]] || { echo "Invalid release id" >&2; exit 1; }

for _ in {1..120}; do
  if command -v k3s >/dev/null 2>&1 && [[ -f /var/lib/rancher/k3s/server/node-ready ]]; then
    break
  fi
  sleep 5
done
command -v k3s >/dev/null 2>&1 || { echo "k3s was not installed within 10 minutes" >&2; exit 1; }
[[ -f /var/lib/rancher/k3s/server/node-ready ]] || { echo "k3s node did not become ready within 10 minutes" >&2; exit 1; }

for required in \
  "$RELEASE_DIR/base/kustomization.yaml" \
  "$RELEASE_DIR/traefik-config.yaml" \
  "$RELEASE_DIR/k3s-network-common.sh" \
  "$RELEASE_DIR/k3s-server-config.yaml" \
  "$RELEASE_DIR/runtime-config.json" \
  "$RELEASE_DIR/app-secrets.json" \
  "$RELEASE_DIR/ghcr-pull.json"; do
  [[ -s "$required" ]] || { echo "Missing deployment file: $required" >&2; exit 1; }
done

grep -Fq "$BACKEND_PLACEHOLDER" "$RELEASE_DIR/base/backend.yaml"
grep -Fq "$FRONTEND_PLACEHOLDER" "$RELEASE_DIR/base/frontend.yaml"
grep -Fq "REPLACE_RELEASE_ID" "$RELEASE_DIR/base/backend.yaml"
sed -i "s#${BACKEND_PLACEHOLDER}#${BACKEND_IMAGE}#" "$RELEASE_DIR/base/backend.yaml"
sed -i "s#${FRONTEND_PLACEHOLDER}#${FRONTEND_IMAGE}#" "$RELEASE_DIR/base/frontend.yaml"
sed -i "s#REPLACE_RELEASE_ID#${RELEASE_ID}#" "$RELEASE_DIR/base/backend.yaml"
grep -Fq "image: ${BACKEND_IMAGE}" "$RELEASE_DIR/base/backend.yaml"
grep -Fq "image: ${FRONTEND_IMAGE}" "$RELEASE_DIR/base/frontend.yaml"
grep -Fq "talkwithneighbors.io/release: ${RELEASE_ID}" "$RELEASE_DIR/base/backend.yaml"
if grep -Fq "REPLACE_ACME_EMAIL_ARGUMENT" "$RELEASE_DIR/traefik-config.yaml"; then
  echo "Rendered Traefik configuration still contains the ACME email placeholder" >&2
  exit 1
fi

kubectl=(k3s kubectl)
"${kubectl[@]}" wait --for=condition=Ready node --all --timeout=30s
verify_desired_cluster_network
"${kubectl[@]}" apply -f "$RELEASE_DIR/traefik-config.yaml"
wait_for_traefik_https_config # Gate TLS ingress creation on the rendered Traefik rollout and bound ACME PVC.
"${kubectl[@]}" apply -f "$RELEASE_DIR/base/namespace.yaml"
"${kubectl[@]}" apply -f "$RELEASE_DIR/runtime-config.json"
"${kubectl[@]}" apply -f "$RELEASE_DIR/app-secrets.json"
"${kubectl[@]}" apply -f "$RELEASE_DIR/ghcr-pull.json"
cleanup_plaintext_secrets

"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/mysql.yaml"
"${kubectl[@]}" -n "$NAMESPACE" apply -f "$RELEASE_DIR/base/redis.yaml"
"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/mysql --timeout=8m
"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/redis --timeout=5m

if [[ -f "$MYSQL_RESTORE_PENDING" ]]; then
  [[ -f "$NETWORK_MIGRATION_DONE" ]] || { echo "A MySQL restore is pending without a completed network migration" >&2; exit 1; }
  restore_pending_mysql_dump
elif [[ -f "$NETWORK_MIGRATION_DONE" && ! -f "$MYSQL_RESTORE_DONE" ]]; then
  echo "The network migration completed without a pending or completed MySQL restore marker" >&2
  exit 1
fi

"${kubectl[@]}" apply -k "$RELEASE_DIR/base"
"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/backend --timeout=10m
"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/frontend --timeout=5m
"${kubectl[@]}" -n "$NAMESPACE" exec deployment/backend -- \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
  | jq -e '.status == "UP"' >/dev/null

storage_ready=false
for _ in {1..12}; do
  if "${kubectl[@]}" -n "$NAMESPACE" exec deployment/backend -- \
    wget --timeout=8 --tries=1 -qO- http://127.0.0.1:8080/actuator/health/storage \
    | jq -e '.status == "UP"' >/dev/null; then
    storage_ready=true
    break
  fi
  sleep 5
done
[[ "$storage_ready" == true ]] || { echo "S3 media storage health did not become ready" >&2; exit 1; }

echo "Release ${RELEASE_ID} is ready"
