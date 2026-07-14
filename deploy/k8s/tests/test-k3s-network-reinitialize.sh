#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
# shellcheck source=../k3s-network-common.sh
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
if grep -Eq 'rm[^\n]*/var/lib/rancher/k3s/storage' "$SCRIPT_DIR/reinitialize-k3s-network.sh"; then
  fail "The local persistent storage path must never be removed"
fi
grep -Fq 'flock -n 9' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "Migration lock is missing"
grep -Fq 'k3s-network-v1.done.json' "$SCRIPT_DIR/reinitialize-k3s-network.sh" || fail "One-time migration marker is missing"
grep -Fq 'mysql-restore-v1.pending.json' "$SCRIPT_DIR/deploy-on-node.sh" || fail "Pending MySQL restore support is missing"

restore_call_line="$(grep -n '^  restore_pending_mysql_dump$' "$SCRIPT_DIR/deploy-on-node.sh" | cut -d: -f1)"
full_apply_line="$(grep -n 'apply -k "$RELEASE_DIR/base"' "$SCRIPT_DIR/deploy-on-node.sh" | cut -d: -f1)"
[[ "$restore_call_line" =~ ^[0-9]+$ && "$full_apply_line" =~ ^[0-9]+$ && "$restore_call_line" -lt "$full_apply_line" ]] || \
  fail "MySQL restore must complete before the full application kustomization"

temporary="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary"
}
trap cleanup EXIT

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
