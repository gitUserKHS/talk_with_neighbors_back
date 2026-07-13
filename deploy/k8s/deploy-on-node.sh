#!/usr/bin/env bash
set -euo pipefail

readonly BACKEND_IMAGE="${1:-}"
readonly FRONTEND_IMAGE="${2:-}"
readonly RELEASE_ID="${3:-}"
RELEASE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly RELEASE_DIR
readonly NAMESPACE="talk-with-neighbors"
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

kubectl=(k3s kubectl)
"${kubectl[@]}" wait --for=condition=Ready node --all --timeout=30s
"${kubectl[@]}" apply -f "$RELEASE_DIR/base/namespace.yaml"
"${kubectl[@]}" apply -f "$RELEASE_DIR/runtime-config.json"
"${kubectl[@]}" apply -f "$RELEASE_DIR/app-secrets.json"
"${kubectl[@]}" apply -f "$RELEASE_DIR/ghcr-pull.json"
cleanup_plaintext_secrets
"${kubectl[@]}" apply -k "$RELEASE_DIR/base"

"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/mysql --timeout=8m
"${kubectl[@]}" -n "$NAMESPACE" rollout status statefulset/redis --timeout=5m
"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/backend --timeout=10m
"${kubectl[@]}" -n "$NAMESPACE" rollout status deployment/frontend --timeout=5m
"${kubectl[@]}" -n "$NAMESPACE" exec deployment/backend -- \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
  | jq -e '.status == "UP"' >/dev/null

echo "Release ${RELEASE_ID} is ready"
