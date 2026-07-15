#!/usr/bin/env bash
# Literal checks intentionally match unexpanded workflow and shell source.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
readonly HISTORY="$SCRIPT_DIR/release-history.sh"
readonly WORKFLOW="$SCRIPT_DIR/../../.github/workflows/deploy-k3s.yml"

[[ -s "$HISTORY" && -s "$WORKFLOW" ]] || { echo "Release-history contract files are missing" >&2; exit 1; }
"$BASH" -n "$HISTORY"
grep -Fq 'releases/successful' "$HISTORY"
grep -Fq -- '--sse AES256' "$HISTORY"
grep -Fq '.smokeVerified == true' "$HISTORY"
grep -Fq '{schemaVersion:2' "$HISTORY"
grep -Fq 'sourceRepository:$sourceRepository' "$HISTORY"
grep -Fq 'sourceRef:$sourceRef' "$HISTORY"
grep -Fq 'deploymentMode:$deploymentMode' "$HISTORY"
grep -Fq '(.schemaVersion == 1 or .schemaVersion == 2)' "$HISTORY"
grep -Fq 'RUN_DATABASE_MIGRATIONS=false' "$HISTORY"
grep -Fq 'rollback_latest_successful' "$WORKFLOW"
grep -Fq 'rollback_previous_successful' "$WORKFLOW"
grep -Fq 'ROLLBACK_WITHOUT_DATABASE_DOWNGRADE' "$WORKFLOW"
grep -Fq 'Rollback is manual-only' "$WORKFLOW"
grep -Fq 'Record successful immutable release' "$WORKFLOW"
grep -Fq 'SOURCE_SHA: ${{ github.event.client_payload.source_sha }}' "$WORKFLOW"
grep -Fq 'SOURCE_RUN_ID: ${{ github.event.client_payload.source_run_id }}' "$WORKFLOW"
grep -Fq 'BACKEND_IMAGE: ${{ steps.frontend.outputs.backend_image }}' "$WORKFLOW"
grep -Fq '"frontend_only"' "$WORKFLOW"

echo "Successful release history records schema-v2 provenance while guarded rollback resolves schema-v1 and schema-v2 manifests"
