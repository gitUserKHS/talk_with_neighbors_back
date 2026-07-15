#!/usr/bin/env bash
set -euo pipefail

readonly INSTANCE_ID="${1:?instance id is required}"
readonly FRONTEND_IMAGE="${2:?frontend digest is required}"
readonly RELEASE_ID="${3:?release id is required}"
readonly OUTPUT_FILE="${4:?GitHub output file is required}"

[[ "$INSTANCE_ID" =~ ^i-[a-f0-9]{8,17}$ ]] || { echo "Invalid EC2 instance id" >&2; exit 1; }
[[ "$FRONTEND_IMAGE" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$ ]] || {
  echo "Invalid frontend digest" >&2
  exit 1
}
[[ "$RELEASE_ID" =~ ^[0-9]+-[0-9]+$ ]] || { echo "Invalid release id" >&2; exit 1; }
[[ -n "$OUTPUT_FILE" ]] || { echo "GitHub output file is required" >&2; exit 1; }
for command_name in aws jq sed; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 1; }
done

umask 077
parameters_file="$(mktemp "${RUNNER_TEMP:-/tmp}/twn-frontend-ssm.XXXXXX.json")"
command_id=""
command_finished=false
cleanup() {
  if [[ -n "$command_id" && "$command_finished" != true ]]; then
    aws ssm cancel-command --command-id "$command_id" >/dev/null 2>&1 || true
  fi
  shred -u -- "$parameters_file" 2>/dev/null || rm -f -- "$parameters_file"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

jq -n \
  --arg frontend "$FRONTEND_IMAGE" \
  '{commands:[
    "set -eu",
    "umask 077",
    "command -v flock >/dev/null 2>&1 || { echo \"flock is required for serialized deployment\" >&2; exit 1; }",
    "command -v k3s >/dev/null 2>&1 || { echo \"Frontend-only deployment requires an existing k3s installation\" >&2; exit 1; }",
    "test -f /var/lib/rancher/k3s/server/node-ready || { echo \"Frontend-only deployment requires an initialized k3s node\" >&2; exit 1; }",
    "exec 8>/run/lock/talk-with-neighbors-deploy.lock",
    "flock -n 8 || { echo \"Another on-node deployment is still running\" >&2; exit 1; }",
    "namespace=talk-with-neighbors",
    "k3s kubectl get namespace \"$namespace\" >/dev/null 2>&1 || { echo \"Frontend-only deployment requires the existing talk-with-neighbors namespace\" >&2; exit 1; }",
    "k3s kubectl -n \"$namespace\" get deployment/backend >/dev/null 2>&1 || { echo \"Frontend-only deployment requires the existing backend deployment\" >&2; exit 1; }",
    "k3s kubectl -n \"$namespace\" get deployment/frontend >/dev/null 2>&1 || { echo \"Frontend-only deployment requires the existing frontend deployment\" >&2; exit 1; }",
    "backend_container=\"$(k3s kubectl -n \"$namespace\" get deployment/backend -o jsonpath='\''{.spec.template.spec.containers[0].name}'\'')\"",
    "frontend_container=\"$(k3s kubectl -n \"$namespace\" get deployment/frontend -o jsonpath='\''{.spec.template.spec.containers[0].name}'\'')\"",
    "test \"$backend_container\" = backend || { echo \"Existing backend deployment has an unexpected container contract\" >&2; exit 1; }",
    "test \"$frontend_container\" = frontend || { echo \"Existing frontend deployment has an unexpected container contract\" >&2; exit 1; }",
    "backend_image=\"$(k3s kubectl -n \"$namespace\" get deployment/backend -o jsonpath='\''{.spec.template.spec.containers[0].image}'\'')\"",
    "current_frontend_image=\"$(k3s kubectl -n \"$namespace\" get deployment/frontend -o jsonpath='\''{.spec.template.spec.containers[0].image}'\'')\"",
    "printf '\''%s\\n'\'' \"$backend_image\" | grep -Eq '\''^ghcr[.]io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$'\'' || { echo \"Existing backend deployment is not pinned to the expected immutable digest\" >&2; exit 1; }",
    "printf '\''%s\\n'\'' \"$current_frontend_image\" | grep -Eq '\''^ghcr[.]io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$'\'' || { echo \"Existing frontend deployment is not pinned to the expected immutable digest\" >&2; exit 1; }",
    ("frontend_image=" + ($frontend | @sh)),
    "k3s kubectl -n \"$namespace\" set image deployment/frontend frontend=\"$frontend_image\"",
    "k3s kubectl -n \"$namespace\" rollout status deployment/frontend --timeout=5m",
    "observed_frontend_image=\"$(k3s kubectl -n \"$namespace\" get deployment/frontend -o jsonpath='\''{.spec.template.spec.containers[0].image}'\'')\"",
    "test \"$observed_frontend_image\" = \"$frontend_image\" || { echo \"Frontend rollout did not retain the requested immutable digest\" >&2; exit 1; }",
    "printf '\''FRONTEND_ONLY_BACKEND_IMAGE=%s\\nFRONTEND_ONLY_FRONTEND_IMAGE=%s\\n'\'' \"$backend_image\" \"$observed_frontend_image\""
  ],executionTimeout:["900"]}' > "$parameters_file"

command_id="$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --parameters "file://${parameters_file}" \
  --timeout-seconds 120 \
  --comment "talk-with-neighbors frontend-only ${RELEASE_ID}" \
  --query 'Command.CommandId' \
  --output text)"
[[ "$command_id" =~ ^[a-f0-9-]{36}$ ]] || { echo "SSM did not return a valid command id" >&2; exit 1; }
echo "Frontend-only SSM command: $command_id"

command_output=""
for _ in {1..120}; do
  status="$(aws ssm get-command-invocation \
    --command-id "$command_id" \
    --instance-id "$INSTANCE_ID" \
    --query Status \
    --output text 2>/dev/null || true)"
  case "$status" in
    Success)
      command_finished=true
      command_output="$(aws ssm get-command-invocation \
        --command-id "$command_id" \
        --instance-id "$INSTANCE_ID" \
        --query StandardOutputContent \
        --output text)"
      break
      ;;
    Failed|Cancelled|TimedOut|Cancelling)
      command_finished=true
      aws ssm get-command-invocation \
        --command-id "$command_id" \
        --instance-id "$INSTANCE_ID" \
        --query '[StandardOutputContent,StandardErrorContent]' \
        --output text || true
      echo "Frontend-only deployment failed with SSM status $status" >&2
      exit 1
      ;;
    Pending|InProgress|Delayed|"") sleep 5 ;;
    *) echo "Unexpected SSM status: $status" >&2; exit 1 ;;
  esac
done
[[ "$command_finished" == true ]] || { echo "Frontend-only deployment exceeded ten minutes" >&2; exit 1; }

backend_image="$(printf '%s\n' "$command_output" | sed -n 's/^FRONTEND_ONLY_BACKEND_IMAGE=//p' | tail -n 1)"
observed_frontend_image="$(printf '%s\n' "$command_output" | sed -n 's/^FRONTEND_ONLY_FRONTEND_IMAGE=//p' | tail -n 1)"
[[ "$backend_image" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$ ]] || {
  echo "Frontend-only deployment did not return a valid deployed backend digest" >&2
  exit 1
}
[[ "$observed_frontend_image" == "$FRONTEND_IMAGE" ]] || {
  echo "Frontend-only deployment did not return the requested frontend digest" >&2
  exit 1
}
{
  printf 'backend_image=%s\n' "$backend_image"
  printf 'frontend_image=%s\n' "$observed_frontend_image"
} >> "$OUTPUT_FILE"
echo "Frontend-only rollout completed without applying backend, database, Redis, secrets, backups, or migrations"
