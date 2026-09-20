#!/usr/bin/env bash
# Point the public DNS name at the node's current public IPv4 address.
#
# The node deliberately has no Elastic IP: an auto-assigned address costs
# nothing while the instance is stopped, but it changes on every stop/start.
# When the public host is a DuckDNS name and the DuckDNS token is readable
# from SSM Parameter Store, this script updates the record itself; otherwise
# it only waits for someone else to update it. Either way it fails unless the
# record resolves to the running node before the deadline, because HTTPS and
# ACME depend on it.
set -euo pipefail

readonly INSTANCE_ID="${1:?instance id is required}"
readonly PUBLIC_HOST="${2:?public host is required}"
readonly TOKEN_PARAMETER="${3:-}"
readonly OUTPUT_FILE="${4:-${GITHUB_OUTPUT:-}}"
readonly DNS_ATTEMPTS="${PUBLIC_DNS_ATTEMPTS:-30}"
readonly DNS_INTERVAL_SECONDS="${PUBLIC_DNS_INTERVAL_SECONDS:-10}"

[[ "$INSTANCE_ID" =~ ^i-[a-f0-9]{8,17}$ ]] || { echo "Invalid EC2 instance id" >&2; exit 1; }
[[ "$PUBLIC_HOST" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || { echo "Invalid public host" >&2; exit 1; }
[[ -z "$TOKEN_PARAMETER" || "$TOKEN_PARAMETER" =~ ^/[A-Za-z0-9_./-]+$ ]] || { echo "Invalid DuckDNS token parameter name" >&2; exit 1; }
[[ "$DNS_ATTEMPTS" =~ ^[1-9][0-9]*$ && "$DNS_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || { echo "Invalid DNS wait settings" >&2; exit 1; }

public_ip="$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
[[ "$public_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "EC2 has no public IPv4 address; the instance must be running" >&2; exit 1; }

resolve_public_host() {
  getent ahostsv4 "$PUBLIC_HOST" 2>/dev/null | awk '{print $1}' | sort -u || true
}

update_duckdns() {
  local subdomain token response
  if [[ ! "$PUBLIC_HOST" =~ ^([a-z0-9-]+)\.duckdns\.org$ ]]; then
    echo "${PUBLIC_HOST} is not a DuckDNS name; waiting for its A record to be updated externally"
    return 0
  fi
  subdomain="${BASH_REMATCH[1]}"
  if [[ -z "$TOKEN_PARAMETER" ]]; then
    echo "No DuckDNS token parameter is configured; waiting for the A record to be updated externally"
    return 0
  fi
  if ! token="$(aws ssm get-parameter --name "$TOKEN_PARAMETER" --with-decryption --query Parameter.Value --output text 2>&1)"; then
    echo "The DuckDNS token parameter ${TOKEN_PARAMETER} is not readable (${token}); waiting for the A record to be updated externally"
    return 0
  fi
  [[ "$token" =~ ^[A-Za-z0-9-]{20,80}$ ]] || { echo "The DuckDNS token parameter ${TOKEN_PARAMETER} has an unexpected format" >&2; return 1; }
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${token}"
  fi
  # DuckDNS answers a bare OK or KO. The token travels only inside the TLS
  # query string; curl is never verbose here.
  response="$(curl --silent --show-error --fail --max-time 20 --get \
    --data-urlencode "domains=${subdomain}" \
    --data-urlencode "token=${token}" \
    --data-urlencode "ip=${public_ip}" \
    https://www.duckdns.org/update)" || { echo "The DuckDNS update request failed" >&2; return 1; }
  [[ "$response" == "OK" ]] || { echo "DuckDNS rejected the update for ${subdomain}" >&2; return 1; }
  echo "DuckDNS ${PUBLIC_HOST} was pointed at ${public_ip}"
}

update_duckdns

resolved_ips=""
dns_matches=false
for ((attempt = 1; attempt <= DNS_ATTEMPTS; attempt++)); do
  resolved_ips="$(resolve_public_host)"
  if [[ "$resolved_ips" == "$public_ip" ]]; then
    dns_matches=true
    break
  fi
  if ((attempt < DNS_ATTEMPTS)); then
    sleep "$DNS_INTERVAL_SECONDS"
  fi
done
if [[ "$dns_matches" != true ]]; then
  echo "${PUBLIC_HOST} resolves to '${resolved_ips:-nothing}' but the node's public IPv4 is ${public_ip}. Store the DuckDNS token in the SSM parameter so the record follows the node automatically, or update the A record by hand, then rerun." >&2
  exit 1
fi
echo "${PUBLIC_HOST} resolves to ${public_ip}"
if [[ -n "$OUTPUT_FILE" ]]; then
  printf 'public_ip=%s\n' "$public_ip" >> "$OUTPUT_FILE"
fi
