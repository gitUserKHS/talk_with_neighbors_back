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

[[ "$PUBLIC_ORIGIN" =~ ^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || { echo "PUBLIC_ORIGIN must be a lowercase HTTPS DNS origin without a port or path" >&2; exit 1; }
[[ -z "$ACME_EMAIL" || "$ACME_EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]] || { echo "ACME_EMAIL must be empty or a valid ACME contact address" >&2; exit 1; }
[[ "$AWS_REGION" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]] || { echo "Invalid AWS region" >&2; exit 1; }
[[ "$MEDIA_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid S3 bucket name" >&2; exit 1; }
(( ${#MYSQL_PASSWORD} >= 16 )) || { echo "MYSQL_PASSWORD must contain at least 16 characters" >&2; exit 1; }
(( ${#MYSQL_ROOT_PASSWORD} >= 16 )) || { echo "MYSQL_ROOT_PASSWORD must contain at least 16 characters" >&2; exit 1; }

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
! grep -Fq "REPLACE_ACME_EMAIL_ARGUMENT" "$bundle/traefik-config.yaml"
grep -Fq -- "host: ${PUBLIC_HOST}" "$bundle/base/ingress.yaml"

jq -n \
  --arg origin "$PUBLIC_ORIGIN" \
  --arg region "$AWS_REGION" \
  --arg bucket "$MEDIA_BUCKET" \
  '{apiVersion:"v1",kind:"ConfigMap",metadata:{name:"runtime-config",namespace:"talk-with-neighbors"},data:{"public-origin":$origin,"cookie-secure":"true","media-storage-type":"s3","media-s3-region":$region,"media-s3-bucket":$bucket,"media-s3-prefix":"media"}}' \
  > "$bundle/runtime-config.json"
jq -n \
  --arg mysql "$MYSQL_PASSWORD" \
  --arg root "$MYSQL_ROOT_PASSWORD" \
  '{apiVersion:"v1",kind:"Secret",metadata:{name:"app-secrets",namespace:"talk-with-neighbors"},type:"Opaque",stringData:{"mysql-password":$mysql,"mysql-root-password":$root}}' \
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
