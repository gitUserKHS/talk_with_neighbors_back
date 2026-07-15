#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_PREFIX="releases/successful"

validate_bucket() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
    echo "Invalid release-history bucket" >&2
    return 1
  }
}

validate_image() {
  local kind="$1"
  local image="$2"
  case "$kind" in
    backend)
      [[ "$image" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$ ]]
      ;;
    frontend)
      [[ "$image" =~ ^ghcr\.io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$ ]]
      ;;
  esac || { echo "Invalid $kind image digest" >&2; return 1; }
}

cleanup_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    shred -u -- "$path" 2>/dev/null || rm -f -- "$path"
  fi
}

record_release() {
  local bucket="$1"
  local release_id="$2"
  local backend_image="$3"
  local frontend_image="$4"
  local source_event="$5"
  local source_repository="$6"
  local source_ref="$7"
  local source_sha="$8"
  local source_run_id="$9"
  local public_origin="${10}"
  local migrations_ran="${11}"
  local deployment_mode="${12}"
  local manifest created_at timestamp object_key encryption

  validate_bucket "$bucket"
  [[ "$release_id" =~ ^[0-9]+-[0-9]+$ ]] || { echo "Invalid release id" >&2; return 1; }
  validate_image backend "$backend_image"
  validate_image frontend "$frontend_image"
  [[ "$source_event" =~ ^(workflow_run|workflow_dispatch|repository_dispatch)$ ]] || { echo "Invalid source event" >&2; return 1; }
  [[ "$source_repository" =~ ^gitUserKHS/talk_with_neighbors_(back|front)$ ]] || { echo "Invalid source repository" >&2; return 1; }
  [[ "$source_ref" == "refs/heads/main" ]] || { echo "Invalid source ref" >&2; return 1; }
  [[ "$source_sha" =~ ^[a-f0-9]{40}$ ]] || { echo "Invalid source commit SHA" >&2; return 1; }
  [[ "$source_run_id" =~ ^[0-9]+$ ]] || { echo "Invalid source run id" >&2; return 1; }
  [[ "$public_origin" =~ ^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || { echo "Invalid public origin" >&2; return 1; }
  [[ "$migrations_ran" == "true" || "$migrations_ran" == "false" ]] || { echo "Invalid migration record" >&2; return 1; }
  [[ "$deployment_mode" =~ ^(deploy|frontend_only|rollback_latest_successful|rollback_previous_successful)$ ]] || {
    echo "Invalid deployment mode record" >&2
    return 1
  }
  case "$source_event:$deployment_mode" in
    repository_dispatch:frontend_only)
      [[ "$source_repository" == "gitUserKHS/talk_with_neighbors_front" && "$migrations_ran" == "false" ]] || {
        echo "Frontend-only provenance is inconsistent" >&2
        return 1
      }
      ;;
    workflow_run:deploy)
      [[ "$source_repository" == "gitUserKHS/talk_with_neighbors_back" && "$migrations_ran" == "true" ]] || {
        echo "Backend deployment provenance is inconsistent" >&2
        return 1
      }
      ;;
    workflow_dispatch:deploy)
      [[ "$source_repository" == "gitUserKHS/talk_with_neighbors_back" && "$migrations_ran" == "true" ]] || {
        echo "Manual deployment provenance is inconsistent" >&2
        return 1
      }
      ;;
    workflow_dispatch:rollback_latest_successful|workflow_dispatch:rollback_previous_successful)
      [[ "$source_repository" == "gitUserKHS/talk_with_neighbors_back" && "$migrations_ran" == "false" ]] || {
        echo "Rollback provenance is inconsistent" >&2
        return 1
      }
      ;;
    *)
      echo "Source event and deployment mode are inconsistent" >&2
      return 1
      ;;
  esac

  umask 077
  manifest="$(mktemp "${RUNNER_TEMP:-/tmp}/twn-release.XXXXXX.json")"
  trap 'cleanup_file "$manifest"' RETURN
  created_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
  object_key="${RELEASE_PREFIX}/${timestamp}-${release_id}.json"
  jq -n \
    --arg createdAt "$created_at" \
    --arg releaseId "$release_id" \
    --arg backendImage "$backend_image" \
    --arg frontendImage "$frontend_image" \
    --arg sourceEvent "$source_event" \
    --arg sourceRepository "$source_repository" \
    --arg sourceRef "$source_ref" \
    --arg sourceSha "$source_sha" \
    --arg sourceRunId "$source_run_id" \
    --arg publicOrigin "$public_origin" \
    --argjson migrationsRan "$migrations_ran" \
    --arg deploymentMode "$deployment_mode" \
    '{schemaVersion:2,createdAt:$createdAt,releaseId:$releaseId,backendImage:$backendImage,frontendImage:$frontendImage,sourceEvent:$sourceEvent,sourceRepository:$sourceRepository,sourceRef:$sourceRef,sourceSha:$sourceSha,sourceRunId:$sourceRunId,publicOrigin:$publicOrigin,migrationsRan:$migrationsRan,deploymentMode:$deploymentMode,smokeVerified:true}' \
    > "$manifest"
  aws s3 cp "$manifest" "s3://${bucket}/${object_key}" \
    --sse AES256 \
    --content-type application/json \
    --only-show-errors
  encryption="$(aws s3api head-object --bucket "$bucket" --key "$object_key" --query ServerSideEncryption --output text)"
  [[ "$encryption" == "AES256" ]] || { echo "Release manifest is not encrypted with SSE-S3" >&2; return 1; }
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'release_manifest_key=%s\n' "$object_key" >> "$GITHUB_OUTPUT"
  fi
  echo "Successful release recorded: $object_key"
}

resolve_release() {
  local bucket="$1"
  local selector="$2"
  local env_file="$3"
  local index listing manifest object_key backend_image frontend_image release_id

  validate_bucket "$bucket"
  [[ "$selector" == "latest" || "$selector" == "previous" ]] || { echo "Rollback selector must be latest or previous" >&2; return 1; }
  [[ -n "$env_file" ]] || { echo "Environment output file is required" >&2; return 1; }
  index=0
  [[ "$selector" == "previous" ]] && index=1

  umask 077
  listing="$(mktemp "${RUNNER_TEMP:-/tmp}/twn-releases.XXXXXX.json")"
  manifest="$(mktemp "${RUNNER_TEMP:-/tmp}/twn-release-target.XXXXXX.json")"
  trap 'cleanup_file "$listing"; cleanup_file "$manifest"' RETURN
  aws s3api list-objects-v2 --bucket "$bucket" --prefix "${RELEASE_PREFIX}/" --output json > "$listing"
  object_key="$(jq -er --argjson index "$index" '
    [.Contents[]? | select(.Key | test("^releases/successful/[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+[.]json$"))]
    | sort_by(.LastModified) | reverse | .[$index].Key // empty
  ' "$listing")"
  [[ -n "$object_key" ]] || { echo "No $selector successful release is available" >&2; return 1; }
  aws s3 cp "s3://${bucket}/${object_key}" "$manifest" --only-show-errors
  jq -e '
    (.schemaVersion == 1 or .schemaVersion == 2) and .smokeVerified == true and
    (.releaseId | type == "string" and test("^[0-9]+-[0-9]+$")) and
    (.backendImage | type == "string" and test("^ghcr[.]io/gituserkhs/talk_with_neighbors_back@sha256:[a-f0-9]{64}$")) and
    (.frontendImage | type == "string" and test("^ghcr[.]io/gituserkhs/talk_with_neighbors_front@sha256:[a-f0-9]{64}$")) and
    (if .schemaVersion == 2 then
      (.sourceEvent | type == "string" and test("^(workflow_run|workflow_dispatch|repository_dispatch)$")) and
      (.sourceRepository | type == "string" and test("^gitUserKHS/talk_with_neighbors_(back|front)$")) and
      .sourceRef == "refs/heads/main" and
      (.sourceSha | type == "string" and test("^[a-f0-9]{40}$")) and
      (.sourceRunId | type == "string" and test("^[0-9]+$")) and
      (.deploymentMode | type == "string" and test("^(deploy|frontend_only|rollback_latest_successful|rollback_previous_successful)$")) and
      (.migrationsRan | type == "boolean")
    else true end)
  ' "$manifest" >/dev/null
  backend_image="$(jq -r '.backendImage' "$manifest")"
  frontend_image="$(jq -r '.frontendImage' "$manifest")"
  release_id="$(jq -r '.releaseId' "$manifest")"
  validate_image backend "$backend_image"
  validate_image frontend "$frontend_image"
  {
    printf 'BACKEND_IMAGE=%s\n' "$backend_image"
    printf 'FRONTEND_IMAGE=%s\n' "$frontend_image"
    printf 'ROLLBACK_TARGET_RELEASE_ID=%s\n' "$release_id"
    printf 'ROLLBACK_TARGET_MANIFEST=%s\n' "$object_key"
    printf 'RUN_DATABASE_MIGRATIONS=false\n'
  } >> "$env_file"
  echo "Guarded rollback target resolved: release $release_id ($selector successful)"
}

case "${1:-}" in
  record)
    (($# == 13)) || { echo "Usage: $0 record BUCKET RELEASE_ID BACKEND FRONTEND EVENT SOURCE_REPOSITORY SOURCE_REF SHA SOURCE_RUN_ID ORIGIN MIGRATIONS_RAN DEPLOYMENT_MODE" >&2; exit 2; }
    shift
    record_release "$@"
    ;;
  resolve)
    (($# == 4)) || { echo "Usage: $0 resolve BUCKET latest|previous ENV_FILE" >&2; exit 2; }
    shift
    resolve_release "$@"
    ;;
  *)
    echo "Usage: $0 record|resolve ..." >&2
    exit 2
    ;;
esac
