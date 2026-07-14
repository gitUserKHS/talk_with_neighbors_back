#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
# The helper path is derived dynamically from this test's location.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/k3s-network-common.sh"

fail() {
  echo "$*" >&2
  exit 1
}

readonly INSTANCE_ID="i-0123456789abcdef0"
readonly EXPECTED_CONFIRMATION="REINITIALIZE_K3S_NETWORK:${INSTANCE_ID}:pods=10.244.0.0/16:services=10.96.0.0/16:dns=10.96.0.10"
[[ "$(k3s_expected_reinitialize_confirmation "$INSTANCE_ID")" == "$EXPECTED_CONFIRMATION" ]] || fail "Confirmation contract drifted"
k3s_is_legacy_pod_cidr "10.42.0.0/24" || fail "Legacy Pod CIDR was rejected"
! k3s_is_legacy_pod_cidr "10.244.0.0/24" || fail "Desired Pod CIDR was accepted as legacy"
k3s_is_desired_pod_cidr "10.244.7.0/24" || fail "Desired Pod CIDR was rejected"
! k3s_is_desired_pod_cidr "10.42.7.0/24" || fail "Legacy Pod CIDR was accepted as desired"
k3s_config_has_desired_network "$SCRIPT_DIR/k3s-server-config.yaml" || fail "Canonical k3s config is invalid"

for script in \
  "$SCRIPT_DIR/build-bundle.sh" \
  "$SCRIPT_DIR/deploy-on-node.sh" \
  "$SCRIPT_DIR/deploy-via-ssm.sh" \
  "$SCRIPT_DIR/k3s-network-common.sh" \
  "$SCRIPT_DIR/reinitialize-k3s-network.sh"; do
  bash -n "$script"
done

if grep -Fq 'k3s-uninstall.sh' "$SCRIPT_DIR/reinitialize-k3s-network.sh"; then
  fail "The destructive k3s uninstall helper must never be called"
fi
if grep -Eq 'rm.*/var/lib/rancher/k3s/storage' "$SCRIPT_DIR/reinitialize-k3s-network.sh"; then
  fail "The local persistent storage path must never be removed"
fi
grep -Fq 'flock -n 9' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "Migration lock is missing"
grep -Fq 'k3s-network-v1.done.json' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "One-time migration marker is missing"
grep -Fq 'mysql-restore-v1.pending.json' "$SCRIPT_DIR/deploy-on-node.sh" || fail "Pending MySQL restore support is missing"
grep -Fq 'executionTimeout:["9000"]' "$SCRIPT_DIR/deploy-via-ssm.sh" || fail "The remote rollback window is too short"
grep -Fq 'select(.conditions.ready != false)' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "Traefik readiness is not checked"
grep -Fq 'recover_stale_pre_destructive_attempt' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "A safe pre-destructive retry path is missing"
grep -Fq 'aborted-before-destructive.json' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "Failed pre-destructive journals are not retained"
grep -Fq 'The previous migration advanced beyond the safe pre-destructive retry phase' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "Advanced migration phases are not blocked from automatic retry"
if grep -Fq 'rollout status deployment/backend --timeout=3m' "$SCRIPT_DIR/reinitialize-k3s-network.sh"; then
  fail "A scaled-to-zero backend must not use rollout status because stale ProgressDeadlineExceeded conditions fail immediately"
fi
grep -Fq 'k3s_wait_for_single_node_registration' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || \
  fail "The reset must wait for a node object after the k3s API becomes ready"
grep -Fq 'if (.items | length) == 1 then .items[0].metadata.name else empty end' "$SCRIPT_DIR/k3s-network-common.sh" || \
  fail "The node-registration wait must require exactly one node"
if grep -Fq 'kubectl wait --for=condition=Ready node --all' "$SCRIPT_DIR/reinitialize-k3s-network.sh"; then
  fail "kubectl wait --all fails when the API is ready before the first node object registers"
fi
# These are intentional literal source-code patterns.
# shellcheck disable=SC2016
node_registration_line="$(grep -nF -m1 'new_node_name="$(k3s_wait_for_single_node_registration 120)"' "$SCRIPT_DIR/reinitialize-k3s-network.sh" | cut -d: -f1)"
# shellcheck disable=SC2016
node_ready_line="$(grep -nF -m1 'kubectl wait --for=condition=Ready "node/$new_node_name"' "$SCRIPT_DIR/reinitialize-k3s-network.sh" | cut -d: -f1)"
[[ "$node_registration_line" =~ ^[0-9]+$ && "$node_ready_line" =~ ^[0-9]+$ && "$node_registration_line" -lt "$node_ready_line" ]] || \
  fail "The reset must observe node registration before waiting for node readiness"

temporary="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary"
}
trap cleanup EXIT

node_probe_count_file="$temporary/node-probe-count"
# Invoked indirectly by k3s_wait_for_single_node_registration.
# shellcheck disable=SC2329
k3s() {
  [[ "$*" == "kubectl get nodes -o json" ]] || return 2
  local probe_count
  probe_count="$(< "$node_probe_count_file")"
  probe_count=$((probe_count + 1))
  printf '%s\n' "$probe_count" > "$node_probe_count_file"
  case "$K3S_NODE_TEST_SCENARIO" in
    delayed)
      if (( probe_count < 3 )); then
        printf '{"items":[]}\n'
      else
        printf '{"items":[{"metadata":{"name":"node-a"}}]}\n'
      fi
      ;;
    empty)
      printf '{"items":[]}\n'
      ;;
    multiple)
      printf '{"items":[{"metadata":{"name":"node-a"}},{"metadata":{"name":"node-b"}}]}\n'
      ;;
    *)
      return 2
      ;;
  esac
}
# Invoked indirectly by k3s_wait_for_single_node_registration.
# shellcheck disable=SC2329
sleep() {
  :
}

printf '0\n' > "$node_probe_count_file"
K3S_NODE_TEST_SCENARIO=delayed
[[ "$(k3s_wait_for_single_node_registration 3)" == "node-a" ]] || \
  fail "The node-registration wait did not tolerate an API-ready registration delay"

printf '0\n' > "$node_probe_count_file"
K3S_NODE_TEST_SCENARIO=empty
if k3s_wait_for_single_node_registration 2 >/dev/null; then
  fail "The node-registration wait accepted an empty node list"
fi

printf '0\n' > "$node_probe_count_file"
K3S_NODE_TEST_SCENARIO=multiple
if k3s_wait_for_single_node_registration 2 >/dev/null; then
  fail "The single-node migration accepted multiple registered nodes"
fi
unset -f k3s sleep
unset K3S_NODE_TEST_SCENARIO node_probe_count_file

deploy_lock_line="$(grep -nF -m1 'exec 8>/run/lock/talk-with-neighbors-deploy.lock' "$SCRIPT_DIR/deploy-via-ssm.sh" | cut -d: -f1)"
release_dir_line="$(grep -nF -m1 'release_dir=/var/lib/talk-with-neighbors/release' "$SCRIPT_DIR/deploy-via-ssm.sh" | cut -d: -f1)"
[[ "$deploy_lock_line" =~ ^[0-9]+$ && "$release_dir_line" =~ ^[0-9]+$ && "$deploy_lock_line" -lt "$release_dir_line" ]] || \
  fail "The on-node deployment lock must be acquired before the shared release directory is touched"

restore_call_line="$(grep -n '^  restore_pending_mysql_dump$' "$SCRIPT_DIR/deploy-on-node.sh" | cut -d: -f1)"
# This is an intentional literal source-code pattern.
# shellcheck disable=SC2016
full_apply_line="$(grep -n 'apply -k "$RELEASE_DIR/base"' "$SCRIPT_DIR/deploy-on-node.sh" | cut -d: -f1)"
[[ "$restore_call_line" =~ ^[0-9]+$ && "$full_apply_line" =~ ^[0-9]+$ && "$restore_call_line" -lt "$full_apply_line" ]] || \
  fail "MySQL restore must complete before the full application kustomization"

PUBLIC_ORIGIN=http://127.0.0.1 \
AWS_REGION=ap-northeast-2 \
MEDIA_BUCKET=twn-ci-media \
MYSQL_PASSWORD=ci-mysql-password \
MYSQL_ROOT_PASSWORD=ci-root-password \
JWT_SECRET=ci-jwt-secret-that-is-at-least-32-characters \
RUNNER_TEMP="$temporary" \
  bash "$SCRIPT_DIR/build-bundle.sh" "$temporary/bundle.tgz" >/dev/null

tar -tzf "$temporary/bundle.tgz" > "$temporary/bundle-files.txt"
for required in \
  ./deploy-on-node.sh \
  ./k3s-network-common.sh \
  ./k3s-server-config.yaml \
  ./reinitialize-k3s-network.sh; do
  grep -Fxq "$required" "$temporary/bundle-files.txt" || fail "Bundle is missing $required"
done

readonly IMAGE_SUFFIX="sha256:0000000000000000000000000000000000000000000000000000000000000000"
if bash "$SCRIPT_DIR/deploy-via-ssm.sh" \
  "$INSTANCE_ID" twn-ci-deploy deployments/1-1/bundle.tgz "$temporary/bundle.tgz" \
  "ghcr.io/gituserkhs/talk_with_neighbors_back@$IMAGE_SUFFIX" \
  "ghcr.io/gituserkhs/talk_with_neighbors_front@$IMAGE_SUFFIX" \
  1-1 true WRONG >/dev/null 2>&1; then
  fail "An invalid destructive confirmation passed runner validation"
fi
if bash "$SCRIPT_DIR/deploy-via-ssm.sh" \
  "$INSTANCE_ID" twn-ci-deploy deployments/1-1/bundle.tgz "$temporary/bundle.tgz" \
  "ghcr.io/gituserkhs/talk_with_neighbors_back@$IMAGE_SUFFIX" \
  "ghcr.io/gituserkhs/talk_with_neighbors_front@$IMAGE_SUFFIX" \
  1-1 false "$EXPECTED_CONFIRMATION" >/dev/null 2>&1; then
  fail "A stale confirmation passed while destructive reset was disabled"
fi

echo "K3s network migration guard tests passed"
