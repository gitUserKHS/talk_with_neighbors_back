#!/usr/bin/env bash
set -euo pipefail

readonly INSTANCE_ID="${1:?instance id is required}"
readonly DEPLOY_BUCKET="${2:?deployment bucket is required}"
readonly OBJECT_KEY="${3:?object key is required}"
readonly BUNDLE_ARCHIVE="${4:?bundle archive is required}"
readonly BACKEND_IMAGE="${5:?backend digest is required}"
readonly FRONTEND_IMAGE="${6:?frontend digest is required}"
readonly RELEASE_ID="${7:?release id is required}"
readonly REINITIALIZE_K3S_NETWORK="${8:-false}"
readonly REINITIALIZE_CONFIRMATION="${9:-}"
readonly RUN_DATABASE_MIGRATIONS="${10:-true}"
readonly OBJECT_URI="s3://${DEPLOY_BUCKET}/${OBJECT_KEY}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
# The helper is resolved from the checked-out release directory at runtime.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/k3s-network-common.sh"

[[ "$INSTANCE_ID" =~ ^i-[a-f0-9]{8,17}$ ]] || { echo "Invalid EC2 instance id" >&2; exit 1; }
[[ "$DEPLOY_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid deployment bucket" >&2; exit 1; }
[[ "$OBJECT_KEY" =~ ^deployments/[0-9]+-[0-9]+/bundle\.tgz$ ]] || { echo "Invalid deployment object key" >&2; exit 1; }
[[ "$BACKEND_IMAGE" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$ ]] || { echo "Invalid backend digest" >&2; exit 1; }
[[ "$FRONTEND_IMAGE" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$ ]] || { echo "Invalid frontend digest" >&2; exit 1; }
[[ "$RELEASE_ID" =~ ^[0-9]+-[0-9]+$ ]] || { echo "Invalid release id" >&2; exit 1; }
[[ -s "$BUNDLE_ARCHIVE" ]] || { echo "Deployment bundle is missing" >&2; exit 1; }
[[ "$REINITIALIZE_K3S_NETWORK" == "true" || "$REINITIALIZE_K3S_NETWORK" == "false" ]] || { echo "Invalid k3s reinitialize flag" >&2; exit 1; }
[[ "$RUN_DATABASE_MIGRATIONS" == "true" || "$RUN_DATABASE_MIGRATIONS" == "false" ]] || { echo "Invalid database migration mode" >&2; exit 1; }
expected_confirmation="$(k3s_expected_reinitialize_confirmation "$INSTANCE_ID")"
if [[ "$REINITIALIZE_K3S_NETWORK" == "true" ]]; then
  [[ "$REINITIALIZE_CONFIRMATION" == "$expected_confirmation" ]] || { echo "The k3s network confirmation is invalid" >&2; exit 1; }
else
  [[ -z "$REINITIALIZE_CONFIRMATION" ]] || { echo "A k3s network confirmation is only allowed when reinitialization is enabled" >&2; exit 1; }
fi

umask 077
parameters_file="$(mktemp "${RUNNER_TEMP:-/tmp}/twn-ssm-parameters.XXXXXX.json")"
command_id=""
command_finished=false
uploaded=false
# Invoked through EXIT and signal traps.
# shellcheck disable=SC2329
cleanup() {
  if [[ -n "$command_id" && "$command_finished" != true ]]; then
    if [[ "$REINITIALIZE_K3S_NETWORK" == "true" ]]; then
      echo "Leaving destructive SSM command $command_id running so its rollback guard cannot be interrupted" >&2
    else
      aws ssm cancel-command --command-id "$command_id" >/dev/null 2>&1 || true
    fi
  fi
  if [[ "$uploaded" == true ]]; then
    aws s3 rm "$OBJECT_URI" --only-show-errors >/dev/null 2>&1 || true
  fi
  for path in "$BUNDLE_ARCHIVE" "$parameters_file"; do
    if [[ -f "$path" ]]; then
      shred -u -- "$path" 2>/dev/null || rm -f -- "$path"
    fi
  done
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

aws s3 cp "$BUNDLE_ARCHIVE" "$OBJECT_URI" --sse AES256 --only-show-errors
uploaded=true

jq -n \
  --arg objectUri "$OBJECT_URI" \
  --arg backend "$BACKEND_IMAGE" \
  --arg frontend "$FRONTEND_IMAGE" \
  --arg release "$RELEASE_ID" \
  --arg instance "$INSTANCE_ID" \
  --arg reset "$REINITIALIZE_K3S_NETWORK" \
  --arg confirmation "$REINITIALIZE_CONFIRMATION" \
  --arg migrations "$RUN_DATABASE_MIGRATIONS" \
  '{commands:[
    "set -eu",
    "umask 077",
    "command -v flock >/dev/null 2>&1 || { echo \"flock is required for serialized deployment\" >&2; exit 1; }",
    "exec 8>/run/lock/talk-with-neighbors-deploy.lock",
    "flock -n 8 || { echo \"Another on-node deployment is still running\" >&2; exit 1; }",
    "release_dir=/var/lib/talk-with-neighbors/release",
    "cleanup_plaintext() { for path in /tmp/twn-deploy.tgz \"$release_dir/app-secrets.json\" \"$release_dir/ghcr-pull.json\"; do if [ -f \"$path\" ]; then shred -u -- \"$path\" 2>/dev/null || rm -f -- \"$path\"; fi; done; }",
    "trap cleanup_plaintext EXIT",
    "trap \"exit 129\" HUP",
    "trap \"exit 130\" INT",
    "trap \"exit 143\" TERM",
    "install -d -m 0700 \"$release_dir\"",
    "cleanup_plaintext",
    ("aws s3 cp \"" + $objectUri + "\" /tmp/twn-deploy.tgz --only-show-errors"),
    "find \"$release_dir\" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +",
    "tar -xzf /tmp/twn-deploy.tgz -C \"$release_dir\"",
    "shred -u -- /tmp/twn-deploy.tgz 2>/dev/null || rm -f -- /tmp/twn-deploy.tgz",
    "chmod 0700 \"$release_dir/deploy-on-node.sh\" \"$release_dir/reinitialize-k3s-network.sh\"",
    ("if [ " + ($reset | @sh) + " = true ]; then /bin/bash \"$release_dir/reinitialize-k3s-network.sh\" " + ($instance | @sh) + " " + ($confirmation | @sh) + " " + ($release | @sh) + "; fi"),
    ("/bin/bash \"$release_dir/deploy-on-node.sh\" \"" + $backend + "\" \"" + $frontend + "\" \"" + $release + "\" \"" + $migrations + "\"")
  ],executionTimeout:["9000"]}' > "$parameters_file"

command_id="$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --parameters "file://${parameters_file}" \
  --timeout-seconds 120 \
  --comment "talk-with-neighbors ${RELEASE_ID}" \
  --query 'Command.CommandId' \
  --output text)"
[[ -n "$command_id" && "$command_id" != "None" ]] || { echo "SSM did not return a command id" >&2; exit 1; }
echo "SSM command: $command_id"

for _ in {1..300}; do
  status="$(aws ssm get-command-invocation \
    --command-id "$command_id" \
    --instance-id "$INSTANCE_ID" \
    --query Status \
    --output text 2>/dev/null || true)"
  case "$status" in
    Success)
      command_finished=true
      aws ssm get-command-invocation --command-id "$command_id" --instance-id "$INSTANCE_ID" --query StandardOutputContent --output text
      exit 0
      ;;
    Failed|Cancelled|TimedOut|Cancelling)
      command_finished=true
      aws ssm get-command-invocation --command-id "$command_id" --instance-id "$INSTANCE_ID" --query '[StandardOutputContent,StandardErrorContent]' --output text || true
      echo "SSM deployment failed with status $status" >&2
      exit 1
      ;;
    Pending|InProgress|Delayed|"")
      sleep 10
      ;;
    *)
      echo "Unexpected SSM status: $status" >&2
      exit 1
      ;;
  esac
done

if [[ "$REINITIALIZE_K3S_NETWORK" == "true" ]]; then
  echo "SSM deployment exceeded the 50-minute runner observation window; the destructive command was deliberately left running for safe rollback" >&2
else
  echo "SSM deployment exceeded 50 minutes" >&2
fi
exit 1
