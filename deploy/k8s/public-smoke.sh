#!/usr/bin/env bash
set -euo pipefail

readonly PUBLIC_ORIGIN="${1:?public HTTPS origin is required}"
readonly WORK_ROOT="${RUNNER_TEMP:-/tmp}"

[[ "$PUBLIC_ORIGIN" =~ ^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || {
  echo "Public smoke origin must be a lowercase HTTPS DNS origin without a port or path" >&2
  exit 1
}
[[ -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || { echo "Unsafe public smoke work directory" >&2; exit 1; }
for command_name in curl jq mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 1; }
done

readonly PUBLIC_HOST="${PUBLIC_ORIGIN#https://}"
work_dir="$(mktemp -d "$WORK_ROOT/twn-public-smoke.XXXXXX")"
cleanup() {
  local path
  for path in "$work_dir"/*; do
    [[ -f "$path" ]] || continue
    shred -u -- "$path" 2>/dev/null || rm -f -- "$path"
  done
  rmdir -- "$work_dir" 2>/dev/null || rm -rf -- "$work_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

redirect_result="$(curl --show-error --silent --output /dev/null --max-redirs 0 --write-out '%{http_code} %{redirect_url}' "http://${PUBLIC_HOST}/healthz")"
read -r redirect_status redirect_url <<<"$redirect_result"
[[ "$redirect_status" == "301" || "$redirect_status" == "308" ]] || {
  echo "HTTP did not return a permanent HTTPS redirect" >&2
  exit 1
}
[[ "$redirect_url" == "$PUBLIC_ORIGIN/healthz" ]] || {
  echo "HTTP redirect target was not the canonical HTTPS origin" >&2
  exit 1
}

health_status="$(curl --fail --show-error --silent --retry 12 --retry-all-errors --retry-delay 5 \
  --output "$work_dir/healthz.txt" --write-out '%{http_code}' "$PUBLIC_ORIGIN/healthz")"
[[ "$health_status" == "200" ]] || { echo "healthz returned HTTP $health_status" >&2; exit 1; }
[[ "$(tr -d '\r\n' < "$work_dir/healthz.txt")" == "ok" ]] || {
  echo "healthz returned an unexpected body" >&2
  exit 1
}

api_status="$(curl --fail --show-error --silent --retry 12 --retry-all-errors --retry-delay 5 \
  --output "$work_dir/api-smoke.json" --write-out '%{http_code}' \
  "$PUBLIC_ORIGIN/api/auth/check-duplicates?username=deploy-smoke")"
[[ "$api_status" == "200" ]] || { echo "API smoke test returned HTTP $api_status" >&2; exit 1; }
jq -e 'type == "object" and ((.emailExists | type) == "boolean") and ((.usernameExists | type) == "boolean")' \
  "$work_dir/api-smoke.json" >/dev/null

public_feed_status="$(curl --fail --show-error --silent --retry 12 --retry-all-errors --retry-delay 5 \
  --output "$work_dir/public-feed.json" --write-out '%{http_code}' \
  "$PUBLIC_ORIGIN/api/public/feed?size=50")"
[[ "$public_feed_status" == "200" ]] || { echo "Public feed returned HTTP $public_feed_status" >&2; exit 1; }
jq -e 'type == "object" and (.content | type == "array") and (.content | any(.[]; .official == true and (.id | type == "string" and length == 36) and (.media | type == "array" and length > 0) and (has("demo") | not)))' \
  "$work_dir/public-feed.json" >/dev/null

public_meetups_status="$(curl --fail --show-error --silent --retry 12 --retry-all-errors --retry-delay 5 \
  --output "$work_dir/public-meetups.json" --write-out '%{http_code}' \
  "$PUBLIC_ORIGIN/api/public/meetups?size=50")"
[[ "$public_meetups_status" == "200" ]] || { echo "Public meetups returned HTTP $public_meetups_status" >&2; exit 1; }
jq -e 'type == "object" and (.content | type == "array") and (.content | any(.[]; .official == true and (.id | type == "string" and length == 36) and (.location | type == "string" and length > 0) and (.latitude | type == "number") and (.longitude | type == "number") and (has("demo") | not)))' \
  "$work_dir/public-meetups.json" >/dev/null

protected_write_status="$(curl --show-error --silent --output /dev/null --write-out '%{http_code}' \
  --request POST --header 'Content-Type: application/json' --data '{}' "$PUBLIC_ORIGIN/api/feed")"
[[ "$protected_write_status" == "401" ]] || {
  echo "Anonymous feed write returned HTTP $protected_write_status instead of 401" >&2
  exit 1
}

echo "Strict public smoke tests passed for $PUBLIC_ORIGIN"
