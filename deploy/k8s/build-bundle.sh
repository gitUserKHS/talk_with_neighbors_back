#!/usr/bin/env bash
set -euo pipefail

readonly OUTPUT_ARCHIVE="${1:?output archive path is required}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly PUBLIC_ORIGIN="${PUBLIC_ORIGIN:?PUBLIC_ORIGIN is required}"
readonly ACME_EMAIL="${ACME_EMAIL:-}"
readonly AWS_REGION="${AWS_REGION:?AWS_REGION is required}"
readonly MEDIA_BUCKET="${MEDIA_BUCKET:?MEDIA_BUCKET is required}"
readonly MYSQL_PASSWORD="${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
readonly MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
readonly AUTH_EMAIL_REQUIRED="${AUTH_EMAIL_REQUIRED:-false}"
readonly EMAIL_VERIFICATION_HMAC_SECRET="${EMAIL_VERIFICATION_HMAC_SECRET:-}"
readonly EMAIL_VERIFICATION_FROM="${EMAIL_VERIFICATION_FROM:-}"

[[ "$PUBLIC_ORIGIN" =~ ^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || { echo "PUBLIC_ORIGIN must be a lowercase HTTPS DNS origin without a port or path" >&2; exit 1; }
[[ -z "$ACME_EMAIL" || "$ACME_EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]] || { echo "ACME_EMAIL must be empty or a valid ACME contact address" >&2; exit 1; }
[[ "$AWS_REGION" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]] || { echo "Invalid AWS region" >&2; exit 1; }
[[ "$MEDIA_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid S3 bucket name" >&2; exit 1; }
(( ${#MYSQL_PASSWORD} >= 16 )) || { echo "MYSQL_PASSWORD must contain at least 16 characters" >&2; exit 1; }
(( ${#MYSQL_ROOT_PASSWORD} >= 16 )) || { echo "MYSQL_ROOT_PASSWORD must contain at least 16 characters" >&2; exit 1; }
[[ "$AUTH_EMAIL_REQUIRED" == "true" || "$AUTH_EMAIL_REQUIRED" == "false" ]] || { echo "AUTH_EMAIL_REQUIRED must be true or false" >&2; exit 1; }
if [[ "$AUTH_EMAIL_REQUIRED" == "true" ]]; then
  (( ${#EMAIL_VERIFICATION_HMAC_SECRET} >= 32 )) || { echo "EMAIL_VERIFICATION_HMAC_SECRET must contain at least 32 characters" >&2; exit 1; }
  [[ "$EMAIL_VERIFICATION_FROM" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]] || { echo "EMAIL_VERIFICATION_FROM must be a valid verified SES address" >&2; exit 1; }
fi
[[ -z "${GOOGLE_OAUTH_CLIENT_ID:-}" && -z "${GOOGLE_OAUTH_CLIENT_SECRET:-}" || -n "${GOOGLE_OAUTH_CLIENT_ID:-}" && -n "${GOOGLE_OAUTH_CLIENT_SECRET:-}" ]] || { echo "Set both Google OAuth credentials or neither" >&2; exit 1; }
[[ -z "${KAKAO_OAUTH_CLIENT_ID:-}" && -z "${KAKAO_OAUTH_CLIENT_SECRET:-}" || -n "${KAKAO_OAUTH_CLIENT_ID:-}" && -n "${KAKAO_OAUTH_CLIENT_SECRET:-}" ]] || { echo "Set both Kakao OAuth credentials or neither" >&2; exit 1; }

umask 077
bundle="$(mktemp -d "${RUNNER_TEMP:-/tmp}/twn-bundle.XXXXXX")"
cleanup() {
  local path
  for path in "$bundle/app-secrets.json" "$bundle/ghcr-pull.json" "$bundle/docker-config.json"; do
    if [[ -f "$path" ]]; then
      shred -u -- "$path" 2>/dev/null || rm -f -- "$path"
    fi
  done
  rm -rf -- "$bundle"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cp -R "$SCRIPT_DIR/base" "$bundle/base"
cp \
  "$SCRIPT_DIR/deploy-on-node.sh" \
  "$SCRIPT_DIR/k3s-network-common.sh" \
  "$SCRIPT_DIR/k3s-server-config.yaml" \
  "$SCRIPT_DIR/reinitialize-k3s-network.sh" \
  "$SCRIPT_DIR/traefik-config.yaml" \
  "$bundle/"

readonly PUBLIC_HOST="${PUBLIC_ORIGIN#https://}"
grep -Fq "talk-with-neighbors.duckdns.org" "$bundle/base/ingress.yaml"
grep -Fq "REPLACE_ACME_EMAIL_ARGUMENT" "$bundle/traefik-config.yaml"
sed -i "s/talk-with-neighbors\.duckdns\.org/${PUBLIC_HOST}/g" "$bundle/base/ingress.yaml"
if [[ -n "$ACME_EMAIL" ]]; then
  sed -i "s#REPLACE_ACME_EMAIL_ARGUMENT#- \"--certificatesresolvers.letsencrypt.acme.email=${ACME_EMAIL}\"#" "$bundle/traefik-config.yaml"
  grep -Fq -- "--certificatesresolvers.letsencrypt.acme.email=${ACME_EMAIL}" "$bundle/traefik-config.yaml"
else
  sed -i "/REPLACE_ACME_EMAIL_ARGUMENT/d" "$bundle/traefik-config.yaml"
fi
if grep -Fq "REPLACE_ACME_EMAIL_ARGUMENT" "$bundle/traefik-config.yaml"; then
  echo "Traefik bundle still contains the ACME email placeholder" >&2
  exit 1
fi
grep -Fq -- "host: ${PUBLIC_HOST}" "$bundle/base/ingress.yaml"

jq -n \
  --arg origin "$PUBLIC_ORIGIN" \
  --arg region "$AWS_REGION" \
  --arg bucket "$MEDIA_BUCKET" \
  --arg emailRequired "$AUTH_EMAIL_REQUIRED" \
  '{apiVersion:"v1",kind:"ConfigMap",metadata:{name:"runtime-config",namespace:"talk-with-neighbors"},data:{"public-origin":$origin,"cookie-secure":"true","auth-email-enabled":$emailRequired,"auth-email-required":$emailRequired,"auth-email-sender":(if $emailRequired == "true" then "ses" else "disabled" end),"media-storage-type":"s3","media-s3-region":$region,"media-s3-bucket":$bucket,"media-s3-prefix":"media"}}' \
  > "$bundle/runtime-config.json"
jq -n \
  --arg mysql "$MYSQL_PASSWORD" \
  --arg root "$MYSQL_ROOT_PASSWORD" \
  --arg emailHmac "$EMAIL_VERIFICATION_HMAC_SECRET" \
  --arg emailFrom "$EMAIL_VERIFICATION_FROM" \
  --arg googleId "${GOOGLE_OAUTH_CLIENT_ID:-}" \
  --arg googleSecret "${GOOGLE_OAUTH_CLIENT_SECRET:-}" \
  --arg kakaoId "${KAKAO_OAUTH_CLIENT_ID:-}" \
  --arg kakaoSecret "${KAKAO_OAUTH_CLIENT_SECRET:-}" \
  '{apiVersion:"v1",kind:"Secret",metadata:{name:"app-secrets",namespace:"talk-with-neighbors"},type:"Opaque",stringData:{"mysql-password":$mysql,"mysql-root-password":$root,"email-verification-hmac-secret":$emailHmac,"email-verification-from":$emailFrom,"google-oauth-client-id":$googleId,"google-oauth-client-secret":$googleSecret,"kakao-oauth-client-id":$kakaoId,"kakao-oauth-client-secret":$kakaoSecret}}' \
  > "$bundle/app-secrets.json"

if [[ -n "${GHCR_TOKEN:-}" && -n "${GHCR_USERNAME:-}" ]]; then
  auth="$(printf '%s:%s' "$GHCR_USERNAME" "$GHCR_TOKEN" | base64 -w0)"
  jq -n --arg auth "$auth" '{auths:{"ghcr.io":{auth:$auth}}}' > "$bundle/docker-config.json"
else
  jq -n '{auths:{}}' > "$bundle/docker-config.json"
fi
docker_config="$(base64 -w0 < "$bundle/docker-config.json")"
jq -n \
  --arg config "$docker_config" \
  '{apiVersion:"v1",kind:"Secret",metadata:{name:"ghcr-pull",namespace:"talk-with-neighbors"},type:"kubernetes.io/dockerconfigjson",data:{".dockerconfigjson":$config}}' \
  > "$bundle/ghcr-pull.json"
shred -u -- "$bundle/docker-config.json" 2>/dev/null || rm -f -- "$bundle/docker-config.json"

tar -C "$bundle" -czf "$OUTPUT_ARCHIVE" .
echo "Deployment bundle created"
