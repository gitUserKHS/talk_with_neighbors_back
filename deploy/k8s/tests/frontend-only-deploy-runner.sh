#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "Frontend-only runner test skipped because jq is unavailable"
  exit 0
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="$SCRIPT_DIR/.."
readonly FRONTEND_IMAGE="ghcr.io/gituserkhs/talk_with_neighbors_front@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
readonly BACKEND_IMAGE="ghcr.io/gituserkhs/talk_with_neighbors_back@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

test_directory_name="$(cd -- "$SCRIPT_DIR" && mktemp -d ".frontend-only-runner.XXXXXX")"
test_root="$SCRIPT_DIR/$test_directory_name"
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

fake_bin="$test_root/bin"
state="$test_root/state"
runner_temp="$test_root/temp"
output_file="$test_root/github-output.txt"
mkdir -p -- "$fake_bin" "$state" "$runner_temp"

cat > "$fake_bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -euo pipefail

readonly state="${FAKE_AWS_STATE:?FAKE_AWS_STATE is required}"
readonly backend_image="${FAKE_BACKEND_IMAGE:?FAKE_BACKEND_IMAGE is required}"
readonly frontend_image="${FAKE_FRONTEND_IMAGE:?FAKE_FRONTEND_IMAGE is required}"

service="${1:-}"
operation="${2:-}"
shift 2 || true
case "$service:$operation" in
  ssm:send-command)
    parameters=""
    while (($# > 0)); do
      if [[ "$1" == "--parameters" ]]; then
        parameters="${2:-}"
        break
      fi
      shift
    done
    [[ "$parameters" == file://* ]] || { echo "missing SSM parameter file" >&2; exit 2; }
    cp -- "${parameters#file://}" "$state/parameters.json"
    printf '00000000-0000-0000-0000-000000000000\n'
    ;;
  ssm:get-command-invocation)
    query=""
    while (($# > 0)); do
      if [[ "$1" == "--query" ]]; then
        query="${2:-}"
        break
      fi
      shift
    done
    case "$query" in
      Status) printf 'Success\n' ;;
      StandardOutputContent)
        printf 'deployment.apps/frontend image updated\n'
        printf 'FRONTEND_ONLY_BACKEND_IMAGE=%s\n' "$backend_image"
        printf 'FRONTEND_ONLY_FRONTEND_IMAGE=%s\n' "$frontend_image"
        ;;
      *) echo "unexpected invocation query: $query" >&2; exit 2 ;;
    esac
    ;;
  ssm:cancel-command)
    :
    ;;
  *)
    echo "unexpected fake AWS call: $service $operation" >&2
    exit 2
    ;;
esac
FAKE_AWS
chmod +x "$fake_bin/aws"

export PATH="$fake_bin:/usr/bin:/bin:$PATH"
export FAKE_AWS_STATE="$state"
export FAKE_BACKEND_IMAGE="$BACKEND_IMAGE"
export FAKE_FRONTEND_IMAGE="$FRONTEND_IMAGE"
export RUNNER_TEMP="$runner_temp"

bash "$DEPLOY_DIR/deploy-frontend-via-ssm.sh" \
  i-12345678 \
  "$FRONTEND_IMAGE" \
  123-1 \
  "$output_file"

jq -e '(.commands | type == "array" and length > 10) and .executionTimeout == ["900"]' "$state/parameters.json" >/dev/null
jq -r '.commands[]' "$state/parameters.json" > "$state/commands.sh"
grep -Fq 'Frontend-only deployment requires the existing backend deployment' "$state/commands.sh"
# The assertion intentionally matches the literal on-node shell variable.
# shellcheck disable=SC2016
grep -Fq 'set image deployment/frontend frontend="$frontend_image"' "$state/commands.sh"
grep -Fq 'rollout status deployment/frontend --timeout=5m' "$state/commands.sh"
grep -Fq "frontend_image='$FRONTEND_IMAGE'" "$state/commands.sh"
if grep -Eq 'kubectl .* apply|rollout status deployment/backend|run-database-migrations|mysql-backup|app-secrets|deployment/redis|statefulset/(mysql|redis)' "$state/commands.sh"; then
  echo "The rendered frontend-only command must not contain full-stack operations" >&2
  exit 1
fi
grep -Fxq "backend_image=$BACKEND_IMAGE" "$output_file"
grep -Fxq "frontend_image=$FRONTEND_IMAGE" "$output_file"

echo "Frontend-only SSM runner emits a validated image-only command and returns the deployed digest pair"
