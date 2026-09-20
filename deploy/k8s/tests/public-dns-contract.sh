#!/usr/bin/env bash
# Guards the "no Elastic IP" cost contract: the node uses an auto-assigned
# public IPv4 address that is free while stopped, and every start path points
# DNS at the new address instead of assuming a fixed one.
# Literal checks intentionally match unexpanded shell and unit source.
# shellcheck disable=SC2016
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SCRIPT_DIR
readonly REPO_ROOT="$SCRIPT_DIR/../.."
readonly SYNC="$SCRIPT_DIR/sync-public-dns.sh"
readonly UPDATER="$SCRIPT_DIR/duckdns-update.sh"
readonly INSTALLER="$SCRIPT_DIR/install-duckdns-update.sh"
readonly BUILD_BUNDLE="$SCRIPT_DIR/build-bundle.sh"
readonly DEPLOY_ON_NODE="$SCRIPT_DIR/deploy-on-node.sh"
readonly UNITS="$SCRIPT_DIR/systemd"
readonly DEPLOY_WORKFLOW="$REPO_ROOT/.github/workflows/deploy-k3s.yml"
readonly POWER_WORKFLOW="$REPO_ROOT/.github/workflows/ec2-power.yml"
readonly TERRAFORM_DIR="$REPO_ROOT/infra/aws-ec2"

for required in "$SYNC" "$UPDATER" "$INSTALLER" "$BUILD_BUNDLE" "$DEPLOY_ON_NODE" \
  "$UNITS/talk-with-neighbors-duckdns-update.service" \
  "$UNITS/talk-with-neighbors-duckdns-update.timer" \
  "$DEPLOY_WORKFLOW" "$POWER_WORKFLOW" "$TERRAFORM_DIR/main.tf" "$TERRAFORM_DIR/outputs.tf"; do
  [[ -s "$required" ]] || { echo "Missing public DNS asset: $required" >&2; exit 1; }
done

# Terraform must not allocate an Elastic IP: it is billed while the node is stopped.
if grep -Eq '^resource "aws_eip(_association)?"' "$TERRAFORM_DIR"/*.tf; then
  echo "Terraform must not allocate an Elastic IP" >&2
  exit 1
fi
if grep -Fq 'aws_eip.' "$TERRAFORM_DIR"/*.tf; then
  echo "Terraform must not reference an Elastic IP" >&2
  exit 1
fi
grep -Fq 'associate_public_ip_address = true' "$TERRAFORM_DIR/main.tf"
grep -Fq 'sid       = "ReadDuckDnsToken"' "$TERRAFORM_DIR/main.tf"
grep -Fq '"ssm:GetParameter"' "$TERRAFORM_DIR/main.tf"
if grep -Fq 'ec2:DescribeAddresses' "$TERRAFORM_DIR/main.tf"; then
  echo "The deploy role no longer needs ec2:DescribeAddresses" >&2
  exit 1
fi

# Workflows must resolve the current address on every start instead of asserting a fixed one.
for workflow in "$DEPLOY_WORKFLOW" "$POWER_WORKFLOW"; do
  if grep -Eq 'describe-addresses|elastic_ip=|== "\$elastic_ip"' "$workflow"; then
    echo "$workflow must not depend on an Elastic IP" >&2
    exit 1
  fi
  grep -Fq 'bash deploy/k8s/sync-public-dns.sh "$INSTANCE_ID" "$public_host" "$DUCKDNS_TOKEN_PARAMETER"' "$workflow"
  grep -Fq "DUCKDNS_TOKEN_PARAMETER: \${{ vars.DUCKDNS_TOKEN_PARAMETER || '/talk-with-neighbors/duckdns/token' }}" "$workflow"
done
[[ "$(grep -Fc 'bash deploy/k8s/sync-public-dns.sh' "$DEPLOY_WORKFLOW")" == "2" ]] || {
  echo "Both the frontend-only and the full deployment must sync DNS" >&2
  exit 1
}

# The runner-side sync must never print the token and must fail on a stale record.
grep -Fq '::add-mask::${token}' "$SYNC"
grep -Fq -- '--data-urlencode "token=${token}"' "$SYNC"
grep -Fq 'exit 1' "$SYNC"
if grep -Eq 'set -x|curl.*(-v|--verbose)' "$SYNC" "$UPDATER"; then
  echo "DNS scripts must not trace the DuckDNS token" >&2
  exit 1
fi

# The node updater reads the token from SSM on demand and uses IMDSv2.
grep -Fq 'X-aws-ec2-metadata-token-ttl-seconds' "$UPDATER"
grep -Fq 'aws ssm get-parameter --region "$region" --name "$DUCKDNS_TOKEN_PARAMETER" --with-decryption' "$UPDATER"
grep -Fq 'ExecStart=/usr/local/sbin/talk-with-neighbors-duckdns-update' "$UNITS/talk-with-neighbors-duckdns-update.service"
grep -Fq 'ConditionPathExists=/etc/talk-with-neighbors/duckdns.conf' "$UNITS/talk-with-neighbors-duckdns-update.service"
grep -Fq 'OnBootSec=' "$UNITS/talk-with-neighbors-duckdns-update.timer"
grep -Fq 'OnUnitActiveSec=' "$UNITS/talk-with-neighbors-duckdns-update.timer"
grep -Fq 'Unit=talk-with-neighbors-duckdns-update.service' "$UNITS/talk-with-neighbors-duckdns-update.timer"
grep -Fq '/usr/local/sbin/talk-with-neighbors-duckdns-update' "$INSTALLER"
grep -Fq 'systemctl enable --now "$TIMER"' "$INSTALLER"

# The bundle carries the updater and the node installs it on every deployment.
grep -Fq '"$SCRIPT_DIR/duckdns-update.sh"' "$BUILD_BUNDLE"
grep -Fq '"$SCRIPT_DIR/install-duckdns-update.sh"' "$BUILD_BUNDLE"
grep -Fq 'DUCKDNS_TOKEN_PARAMETER' "$BUILD_BUNDLE"
grep -Fq '"$bundle/duckdns.conf"' "$BUILD_BUNDLE"
grep -Fq '"$RELEASE_DIR/install-duckdns-update.sh"' "$DEPLOY_ON_NODE"
grep -Fq '"$RELEASE_DIR/duckdns-update.sh"' "$DEPLOY_ON_NODE"
grep -Fq 'bash "$RELEASE_DIR/install-duckdns-update.sh" "$RELEASE_DIR"' "$DEPLOY_ON_NODE"

echo "Public DNS follows the auto-assigned public IP without an Elastic IP"
