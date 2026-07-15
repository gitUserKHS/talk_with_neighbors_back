#!/usr/bin/env bash
# Literal checks intentionally match unexpanded workflow and shell source.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
readonly FRONTEND_DEPLOY="$SCRIPT_DIR/deploy-frontend-via-ssm.sh"
readonly PUBLIC_SMOKE="$SCRIPT_DIR/public-smoke.sh"
readonly WORKFLOW="$SCRIPT_DIR/../../.github/workflows/deploy-k3s.yml"

for required in "$FRONTEND_DEPLOY" "$PUBLIC_SMOKE" "$WORKFLOW"; do
  [[ -s "$required" ]] || { echo "Missing frontend-only deployment contract file: $required" >&2; exit 1; }
done
"$BASH" -n "$FRONTEND_DEPLOY"
"$BASH" -n "$PUBLIC_SMOKE"

grep -Fq 'Frontend-only deployment requires the existing backend deployment' "$FRONTEND_DEPLOY"
grep -Fq 'Frontend-only deployment requires the existing frontend deployment' "$FRONTEND_DEPLOY"
grep -Fq 'set image deployment/frontend frontend=' "$FRONTEND_DEPLOY"
grep -Fq 'rollout status deployment/frontend --timeout=5m' "$FRONTEND_DEPLOY"
grep -Fq 'FRONTEND_ONLY_BACKEND_IMAGE=' "$FRONTEND_DEPLOY"
grep -Fq 'Existing backend deployment is not pinned to the expected immutable digest' "$FRONTEND_DEPLOY"
! grep -Eq 'kubectl .* apply|rollout status deployment/backend|run-database-migrations|mysql-backup|app-secrets|deployment/redis|statefulset/(mysql|redis)' "$FRONTEND_DEPLOY"

frontend_job="$(mktemp "${TMPDIR:-/tmp}/twn-frontend-job.XXXXXX")"
normal_job="$(mktemp "${TMPDIR:-/tmp}/twn-normal-job.XXXXXX")"
cleanup() {
  rm -f -- "$frontend_job" "$normal_job"
}
trap cleanup EXIT
MSYS_NO_PATHCONV=1 awk '/^  deploy_frontend:/{capture=1} capture{if (/^  deploy:/) exit; print}' "$WORKFLOW" > "$frontend_job"
MSYS_NO_PATHCONV=1 awk '/^  deploy:/{capture=1} capture{print}' "$WORKFLOW" > "$normal_job"

grep -Fq "github.event_name == 'repository_dispatch'" "$frontend_job"
grep -Fq 'SOURCE_SHA: ${{ github.event.client_payload.source_sha }}' "$frontend_job"
grep -Fq 'id: frontend_target' "$frontend_job"
grep -Fq 'if [[ "$DISPATCH_FRONTEND_IMAGE" != "$frontend_image" ]]; then' "$frontend_job"
grep -Fq "printf 'stale=true\\n' >> \"\$GITHUB_OUTPUT\"" "$frontend_job"
grep -Fq 'Stale frontend dispatch ignored successfully' "$frontend_job"
grep -Fq "if: steps.frontend_target.outputs.stale == 'true'" "$frontend_job"
[[ "$(grep -Fc "if: steps.frontend_target.outputs.stale != 'true'" "$frontend_job")" == "7" ]] || {
  echo "Every AWS, cluster, smoke, and release step must be skipped for a stale frontend dispatch" >&2
  exit 1
}
stale_line="$(grep -nF "printf 'stale=true\\n'" "$frontend_job" | cut -d: -f1)"
aws_line="$(grep -nF 'uses: aws-actions/configure-aws-credentials@' "$frontend_job" | cut -d: -f1)"
[[ "$stale_line" -lt "$aws_line" ]] || { echo "Stale dispatches must stop before AWS credentials are configured" >&2; exit 1; }
grep -Fq 'bash deploy/k8s/deploy-frontend-via-ssm.sh' "$frontend_job"
grep -Fq '"frontend_only"' "$frontend_job"
grep -Fq 'BACKEND_IMAGE: ${{ steps.frontend.outputs.backend_image }}' "$frontend_job"
! grep -Eq 'build-bundle[.]sh|deploy-via-ssm[.]sh|MYSQL_(PASSWORD|ROOT_PASSWORD|BACKUP_BUCKET)|APP_AUTH_|RUN_DATABASE_MIGRATIONS' "$frontend_job"
! grep -Fq "github.event_name == 'repository_dispatch'" "$normal_job"

[[ "$(grep -Fc 'run: bash deploy/k8s/public-smoke.sh "$PUBLIC_ORIGIN"' "$WORKFLOW")" == "2" ]] || {
  echo "Normal and frontend-only deployments must call the same strict public smoke runner" >&2
  exit 1
}

echo "Frontend repository dispatch updates only the existing immutable frontend deployment and shares public smoke checks"
