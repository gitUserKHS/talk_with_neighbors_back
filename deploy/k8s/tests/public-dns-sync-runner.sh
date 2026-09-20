#!/usr/bin/env bash
# Exercises the runner-side DNS sync and the on-node DuckDNS updater with fake
# aws, curl, and getent binaries. No network access is needed.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DEPLOY_DIR="$SCRIPT_DIR/.."
readonly INSTANCE_ID="i-0123456789abcdef0"
readonly PUBLIC_IP="203.0.113.10"
readonly STALE_IP="198.51.100.7"
readonly TOKEN="0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"
readonly PARAMETER="/talk-with-neighbors/duckdns/token"

test_directory_name="$(cd -- "$SCRIPT_DIR" && mktemp -d ".public-dns-sync.XXXXXX")"
test_root="$SCRIPT_DIR/$test_directory_name"
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

fake_bin="$test_root/bin"
state="$test_root/state"
mkdir -p -- "$fake_bin" "$state"

cat > "$fake_bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -euo pipefail
readonly state="${FAKE_STATE:?FAKE_STATE is required}"
service="${1:-}"
operation="${2:-}"
shift 2 || true
case "$service:$operation" in
  ec2:describe-instances)
    cat "$state/public-ip"
    ;;
  ssm:get-parameter)
    printf '%s\n' "$*" > "$state/get-parameter.args"
    if [[ -f "$state/token" ]]; then
      cat "$state/token"
    else
      echo "An error occurred (ParameterNotFound) when calling the GetParameter operation" >&2
      exit 254
    fi
    ;;
  *)
    echo "unexpected fake AWS call: $service $operation" >&2
    exit 2
    ;;
esac
FAKE_AWS

cat > "$fake_bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
readonly state="${FAKE_STATE:?FAKE_STATE is required}"
url=""
query=()
while (($# > 0)); do
  case "$1" in
    --data-urlencode) query+=("${2:-}"); shift ;;
    -H|-X|--max-time) shift ;;
    http://*|https://*) url="$1" ;;
  esac
  shift
done
case "$url" in
  */latest/api/token) printf 'fake-imds-token\n' ;;
  */latest/meta-data/public-ipv4) cat "$state/public-ip" ;;
  */latest/meta-data/placement/region) printf 'ap-northeast-2\n' ;;
  https://www.duckdns.org/update)
    printf '%s\n' "${query[@]}" > "$state/duckdns.query"
    for item in "${query[@]}"; do
      if [[ "$item" == ip=* ]]; then
        printf '%s\n' "${item#ip=}" > "$state/resolved-ip"
      fi
    done
    printf 'OK'
    ;;
  *) echo "unexpected fake curl url: $url" >&2; exit 2 ;;
esac
FAKE_CURL

cat > "$fake_bin/getent" <<'FAKE_GETENT'
#!/usr/bin/env bash
set -euo pipefail
readonly state="${FAKE_STATE:?FAKE_STATE is required}"
[[ "${1:-}" == "ahostsv4" ]] || { echo "unexpected getent database: ${1:-}" >&2; exit 2; }
[[ -f "$state/resolved-ip" ]] || exit 2
ip="$(cat "$state/resolved-ip")"
printf '%s STREAM %s\n%s DGRAM \n%s RAW \n' "$ip" "${2:-}" "$ip" "$ip"
FAKE_GETENT
chmod +x "$fake_bin/aws" "$fake_bin/curl" "$fake_bin/getent"

export PATH="$fake_bin:/usr/bin:/bin:$PATH"
export FAKE_STATE="$state"
export PUBLIC_DNS_ATTEMPTS=2
export PUBLIC_DNS_INTERVAL_SECONDS=0
unset GITHUB_OUTPUT GITHUB_ACTIONS

reset_state() {
  rm -f -- "$state"/*
  printf '%s\n' "$PUBLIC_IP" > "$state/public-ip"
}

# 1. With a readable token, the runner updates DuckDNS and waits for the record.
reset_state
printf '%s\n' "$STALE_IP" > "$state/resolved-ip"
printf '%s\n' "$TOKEN" > "$state/token"
bash "$DEPLOY_DIR/sync-public-dns.sh" "$INSTANCE_ID" talk-with-neighbors.duckdns.org "$PARAMETER" "$state/output.txt" > "$state/stdout.txt"
grep -Fxq "domains=talk-with-neighbors" "$state/duckdns.query"
grep -Fxq "token=$TOKEN" "$state/duckdns.query"
grep -Fxq "ip=$PUBLIC_IP" "$state/duckdns.query"
grep -Fq -- "--name $PARAMETER --with-decryption" "$state/get-parameter.args"
grep -Fxq "public_ip=$PUBLIC_IP" "$state/output.txt"
if grep -Fq "$TOKEN" "$state/stdout.txt"; then
  echo "The DuckDNS token must never be printed" >&2
  exit 1
fi

# 2. Without a readable token the runner still succeeds when the record already matches.
reset_state
printf '%s\n' "$PUBLIC_IP" > "$state/resolved-ip"
bash "$DEPLOY_DIR/sync-public-dns.sh" "$INSTANCE_ID" talk-with-neighbors.duckdns.org "$PARAMETER" > "$state/stdout.txt"
[[ ! -f "$state/duckdns.query" ]] || { echo "DuckDNS must not be called without a token" >&2; exit 1; }
grep -Fq "is not readable" "$state/stdout.txt"

# 3. A stale record with no way to fix it fails with a clear message.
reset_state
printf '%s\n' "$STALE_IP" > "$state/resolved-ip"
if bash "$DEPLOY_DIR/sync-public-dns.sh" "$INSTANCE_ID" talk-with-neighbors.duckdns.org "$PARAMETER" > "$state/stdout.txt" 2> "$state/stderr.txt"; then
  echo "A stale DNS record must fail the sync" >&2
  exit 1
fi
grep -Fq "resolves to '$STALE_IP' but the node's public IPv4 is $PUBLIC_IP" "$state/stderr.txt"

# 4. A non-DuckDNS host never touches DuckDNS even when a token exists.
reset_state
printf '%s\n' "$PUBLIC_IP" > "$state/resolved-ip"
printf '%s\n' "$TOKEN" > "$state/token"
bash "$DEPLOY_DIR/sync-public-dns.sh" "$INSTANCE_ID" app.example.com "$PARAMETER" > "$state/stdout.txt"
[[ ! -f "$state/duckdns.query" ]] || { echo "Only DuckDNS hosts may be updated through DuckDNS" >&2; exit 1; }
[[ ! -f "$state/get-parameter.args" ]] || { echo "The token must not be read for non-DuckDNS hosts" >&2; exit 1; }

# 5. An empty parameter name skips the update and only waits.
reset_state
printf '%s\n' "$PUBLIC_IP" > "$state/resolved-ip"
printf '%s\n' "$TOKEN" > "$state/token"
bash "$DEPLOY_DIR/sync-public-dns.sh" "$INSTANCE_ID" talk-with-neighbors.duckdns.org "" > "$state/stdout.txt"
[[ ! -f "$state/get-parameter.args" ]] || { echo "An empty parameter name must not query SSM" >&2; exit 1; }

# 6. The on-node updater refreshes a stale record from IMDS and SSM.
reset_state
printf '%s\n' "$STALE_IP" > "$state/resolved-ip"
printf '%s\n' "$TOKEN" > "$state/token"
printf 'DUCKDNS_DOMAIN=talk-with-neighbors\nDUCKDNS_TOKEN_PARAMETER=%s\n' "$PARAMETER" > "$state/duckdns.conf"
DUCKDNS_CONFIG_FILE="$state/duckdns.conf" IMDS_BASE="http://fake-imds" bash "$DEPLOY_DIR/duckdns-update.sh" > "$state/stdout.txt"
grep -Fxq "ip=$PUBLIC_IP" "$state/duckdns.query"
grep -Fq -- "--region ap-northeast-2 --name $PARAMETER --with-decryption" "$state/get-parameter.args"
grep -Fq "was pointed at $PUBLIC_IP" "$state/stdout.txt"

# 7. The on-node updater does not call DuckDNS when the record already matches.
reset_state
printf '%s\n' "$PUBLIC_IP" > "$state/resolved-ip"
printf '%s\n' "$TOKEN" > "$state/token"
printf 'DUCKDNS_DOMAIN=talk-with-neighbors\nDUCKDNS_TOKEN_PARAMETER=%s\n' "$PARAMETER" > "$state/duckdns.conf"
DUCKDNS_CONFIG_FILE="$state/duckdns.conf" IMDS_BASE="http://fake-imds" bash "$DEPLOY_DIR/duckdns-update.sh" > "$state/stdout.txt"
[[ ! -f "$state/duckdns.query" ]] || { echo "Unchanged records must not be re-sent to DuckDNS" >&2; exit 1; }
[[ ! -f "$state/get-parameter.args" ]] || { echo "The token must not be read when nothing changes" >&2; exit 1; }
grep -Fq "already points at $PUBLIC_IP" "$state/stdout.txt"

# 8. Without a configuration file the on-node updater is a no-op.
reset_state
DUCKDNS_CONFIG_FILE="$state/missing.conf" IMDS_BASE="http://fake-imds" bash "$DEPLOY_DIR/duckdns-update.sh" > "$state/stdout.txt"
grep -Fq "is not configured" "$state/stdout.txt"

echo "Public DNS sync and the DuckDNS updater follow the auto-assigned public IP"
