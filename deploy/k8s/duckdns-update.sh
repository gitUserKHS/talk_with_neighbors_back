#!/usr/bin/env bash
# Keep the DuckDNS A record pointed at this node's current public IPv4.
#
# The node has no Elastic IP, so every stop/start hands it a new address. A
# systemd timer runs this at boot and every few minutes so the record follows
# the node even when it is started from the AWS console instead of a workflow.
# The DuckDNS token never lives on disk: it is read from SSM Parameter Store
# through the instance role on each run.
set -euo pipefail

readonly CONFIG_FILE="${DUCKDNS_CONFIG_FILE:-/etc/talk-with-neighbors/duckdns.conf}"
readonly IMDS_BASE="${IMDS_BASE:-http://169.254.169.254}"

[[ -f "$CONFIG_FILE" ]] || { echo "DuckDNS is not configured because ${CONFIG_FILE} is missing"; exit 0; }
# The file holds only a validated subdomain and an SSM parameter name.
# shellcheck disable=SC1090
source "$CONFIG_FILE"
readonly DUCKDNS_DOMAIN="${DUCKDNS_DOMAIN:-}"
readonly DUCKDNS_TOKEN_PARAMETER="${DUCKDNS_TOKEN_PARAMETER:-}"
[[ "$DUCKDNS_DOMAIN" =~ ^[a-z0-9-]+$ ]] || { echo "DUCKDNS_DOMAIN must be the bare DuckDNS subdomain" >&2; exit 1; }
[[ "$DUCKDNS_TOKEN_PARAMETER" =~ ^/[A-Za-z0-9_./-]+$ ]] || { echo "DUCKDNS_TOKEN_PARAMETER must be an SSM parameter name" >&2; exit 1; }
readonly PUBLIC_HOST="${DUCKDNS_DOMAIN}.duckdns.org"

imds() {
  curl --silent --show-error --fail --max-time 5 -H "X-aws-ec2-metadata-token: ${imds_token}" "${IMDS_BASE}/latest/meta-data/$1"
}

imds_token="$(curl --silent --show-error --fail --max-time 5 -X PUT "${IMDS_BASE}/latest/api/token" -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
public_ip="$(imds public-ipv4)"
[[ "$public_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "IMDS did not return a public IPv4 address" >&2; exit 1; }
region="$(imds placement/region)"
[[ "$region" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]] || { echo "IMDS did not return a valid region" >&2; exit 1; }

# DuckDNS asks clients not to send unchanged updates, so compare first.
current_ips="$(getent ahostsv4 "$PUBLIC_HOST" 2>/dev/null | awk '{print $1}' | sort -u || true)"
if [[ "$current_ips" == "$public_ip" ]]; then
  echo "${PUBLIC_HOST} already points at ${public_ip}"
  exit 0
fi

token="$(aws ssm get-parameter --region "$region" --name "$DUCKDNS_TOKEN_PARAMETER" --with-decryption --query Parameter.Value --output text)"
[[ "$token" =~ ^[A-Za-z0-9-]{20,80}$ ]] || { echo "The DuckDNS token parameter has an unexpected format" >&2; exit 1; }

response="$(curl --silent --show-error --fail --max-time 20 --get \
  --data-urlencode "domains=${DUCKDNS_DOMAIN}" \
  --data-urlencode "token=${token}" \
  --data-urlencode "ip=${public_ip}" \
  https://www.duckdns.org/update)"
[[ "$response" == "OK" ]] || { echo "DuckDNS rejected the update for ${DUCKDNS_DOMAIN}" >&2; exit 1; }
echo "${PUBLIC_HOST} was pointed at ${public_ip}"
