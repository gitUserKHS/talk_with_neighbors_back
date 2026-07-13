data "aws_partition" "current" {}

data "aws_ssm_parameter" "ubuntu_arm64_ami" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}

locals {
  instance_name       = "${var.project_name}-k3s"
  ubuntu_arm64_ami_id = nonsensitive(data.aws_ssm_parameter.ubuntu_arm64_ami.value)
  media_prefix        = "media/"
  deployment_prefix   = "deployments/"
  k3s_version_url     = replace(var.k3s_version, "+", "%2B")

  github_oidc_provider_arn = coalesce(
    var.github_oidc_provider_arn,
    try(aws_iam_openid_connect_provider.github[0].arn, null)
  )

  k3s_user_data = <<-USER_DATA
    #!/usr/bin/env bash
    set -Eeuo pipefail
    exec > >(tee -a /var/log/talk-with-neighbors-bootstrap.log) 2>&1
    export DEBIAN_FRONTEND=noninteractive

    # A small swap file helps the 2 GiB portfolio node tolerate short memory
    # spikes. k3s is explicitly configured not to reject a swap-enabled host.
    if [[ ! -f /swapfile ]]; then
      fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=progress
    fi
    chmod 0600 /swapfile
    if [[ "$(blkid -s TYPE -o value /swapfile 2>/dev/null || true)" != "swap" ]]; then
      mkswap /swapfile
    fi
    if ! swapon --show=NAME --noheadings | grep -Eq '^[[:space:]]*/swapfile[[:space:]]*$'; then
      swapon /swapfile
    fi
    if ! grep -Eq '^/swapfile[[:space:]]' /etc/fstab; then
      printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
    fi
    printf 'vm.swappiness=10\n' > /etc/sysctl.d/99-talk-with-neighbors-swap.conf
    sysctl -w vm.swappiness=10

    apt-get update -y
    apt-get install -y ca-certificates curl jq unzip

    if systemctl list-unit-files --no-legend | awk '{print $1}' | grep -Fxq amazon-ssm-agent.service; then
      systemctl enable --now amazon-ssm-agent.service
      ssm_service=amazon-ssm-agent.service
    elif systemctl list-unit-files --no-legend | awk '{print $1}' | grep -Fxq snap.amazon-ssm-agent.amazon-ssm-agent.service; then
      snap start --enable amazon-ssm-agent
      ssm_service=snap.amazon-ssm-agent.amazon-ssm-agent.service
    elif command -v snap >/dev/null 2>&1; then
      if ! snap list amazon-ssm-agent >/dev/null 2>&1; then
        snap install amazon-ssm-agent --classic
      fi
      snap start --enable amazon-ssm-agent
      ssm_service=snap.amazon-ssm-agent.amazon-ssm-agent.service
    else
      echo 'Amazon SSM Agent is unavailable' >&2
      exit 1
    fi
    systemctl is-active --quiet "$ssm_service"

    if ! command -v aws >/dev/null 2>&1; then
      aws_cli_dir="$(mktemp -d)"
      curl --fail --show-error --location \
        https://awscli.amazonaws.com/awscli-exe-linux-aarch64-${var.aws_cli_version}.zip \
        --output "$aws_cli_dir/awscliv2.zip"
      printf '%s  %s\n' '${var.aws_cli_aarch64_sha256}' "$aws_cli_dir/awscliv2.zip" | sha256sum --check --status
      unzip -q "$aws_cli_dir/awscliv2.zip" -d "$aws_cli_dir"
      "$aws_cli_dir/aws/install" --bin-dir /usr/local/bin --install-dir /usr/local/aws-cli
      rm -rf -- "$aws_cli_dir"
    fi

    install -d -m 0755 /etc/rancher/k3s
    cat > /etc/rancher/k3s/config.yaml <<'K3S_CONFIG'
    write-kubeconfig-mode: "0640"
    secrets-encryption: true
    kubelet-arg:
      - "fail-swap-on=false"
    K3S_CONFIG

    curl --fail --show-error --location \
      https://raw.githubusercontent.com/k3s-io/k3s/${local.k3s_version_url}/install.sh \
      --output /tmp/install-k3s.sh
    printf '%s  %s\n' '${var.k3s_install_script_sha256}' /tmp/install-k3s.sh | sha256sum --check --status
    chmod 0700 /tmp/install-k3s.sh
    INSTALL_K3S_VERSION='${var.k3s_version}' /tmp/install-k3s.sh
    rm -f -- /tmp/install-k3s.sh
    systemctl enable --now k3s

    node_ready=false
    for attempt in $(seq 1 60); do
      if /usr/local/bin/k3s kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1; then
        node_ready=true
        break
      fi
      sleep 5
    done
    if [[ "$node_ready" != true ]]; then
      journalctl --unit k3s --no-pager --lines 200
      exit 1
    fi

    install -d -m 0700 /var/lib/talk-with-neighbors/release
    touch /var/lib/rancher/k3s/server/node-ready
  USER_DATA
}

resource "random_id" "media_bucket_suffix" {
  byte_length = 6

  keepers = {
    project_name = var.project_name
  }
}

resource "random_id" "deployment_bucket_suffix" {
  byte_length = 6

  keepers = {
    project_name = var.project_name
  }
}

resource "aws_vpc" "app" {
  cidr_block                       = var.vpc_cidr
  enable_dns_support               = true
  enable_dns_hostnames             = true
  assign_generated_ipv6_cidr_block = false
  instance_tenancy                 = "default"

  tags = {
    Name      = "${var.project_name}-vpc"
    Component = "network"
  }
}

resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id

  tags = {
    Name      = "${var.project_name}-igw"
    Component = "network"
  }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.app.id
  cidr_block              = var.public_subnet_cidr
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true

  tags = {
    Name      = "${var.project_name}-public"
    Component = "network"
  }

  lifecycle {
    precondition {
      condition     = startswith(var.availability_zone, var.aws_region)
      error_message = "availability_zone must belong to aws_region."
    }
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.app.id

  tags = {
    Name      = "${var.project_name}-public"
    Component = "network"
  }
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.app.id
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Gateway endpoints have no hourly endpoint charge and keep EC2-to-S3 traffic
# off the public internet without requiring a NAT gateway.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.app.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]

  tags = {
    Name      = "${var.project_name}-s3"
    Component = "network"
  }
}

resource "aws_security_group" "app" {
  name                   = "${var.project_name}-web"
  description            = "Public HTTP and HTTPS only; administration uses SSM"
  vpc_id                 = aws_vpc.app.id
  revoke_rules_on_delete = true

  tags = {
    Name      = "${var.project_name}-web"
    Component = "k3s-node"
  }
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  description       = "Public HTTP"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  ip_protocol       = "tcp"
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "Public HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  ip_protocol       = "tcp"
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "all_ipv4" {
  security_group_id = aws_security_group.app.id
  description       = "Package, image, AWS API, and SSM access"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_s3_bucket" "media" {
  bucket        = "${var.project_name}-media-${random_id.media_bucket_suffix.hex}"
  force_destroy = var.media_bucket_force_destroy

  tags = {
    Name      = "${var.project_name}-media"
    Component = "media-storage"
  }
}

resource "aws_s3_bucket_versioning" "media" {
  bucket = aws_s3_bucket.media.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Versioning protects against accidental deletion, while bounded retention keeps
# old media versions from accumulating indefinitely in this low-cost portfolio.
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    id     = "expire-noncurrent-media"
    status = "Enabled"

    filter {
      prefix = local.media_prefix
    }

    noncurrent_version_expiration {
      noncurrent_days = var.media_noncurrent_version_expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "remove-expired-delete-markers"
    status = "Enabled"

    filter {
      prefix = local.media_prefix
    }

    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.media]
}

resource "aws_s3_bucket" "deployment" {
  bucket        = "${var.project_name}-deploy-${random_id.deployment_bucket_suffix.hex}"
  force_destroy = false

  tags = {
    Name      = "${var.project_name}-deployments"
    Component = "deployment"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "deployment" {
  bucket = aws_s3_bucket.deployment.id

  rule {
    id     = "expire-deployment-bundles"
    status = "Enabled"

    filter {
      prefix = local.deployment_prefix
    }

    expiration {
      days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_ownership_controls" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_ownership_controls" "deployment" {
  bucket = aws_s3_bucket.deployment.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "deployment" {
  bucket = aws_s3_bucket.deployment.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "deployment" {
  bucket = aws_s3_bucket.deployment.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "media_bucket_tls" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.media.arn,
      "${aws_s3_bucket.media.arn}/*"
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "media_tls" {
  bucket = aws_s3_bucket.media.id
  policy = data.aws_iam_policy_document.media_bucket_tls.json
}

data "aws_iam_policy_document" "deployment_bucket_tls" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.deployment.arn,
      "${aws_s3_bucket.deployment.arn}/*"
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "deployment_tls" {
  bucket = aws_s3_bucket.deployment.id
  policy = data.aws_iam_policy_document.deployment_bucket_tls.json
}

resource "aws_budgets_budget" "monthly" {
  count = var.budget_alert_email == null ? 0 : 1

  name         = "${var.project_name}-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "ACTUAL"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = compact([var.budget_alert_email])
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "FORECASTED"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = compact([var.budget_alert_email])
  }
}

data "aws_iam_policy_document" "instance_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.project_name}-ec2"
  assume_role_policy = data.aws_iam_policy_document.instance_assume_role.json

  tags = {
    Component = "k3s-node"
  }
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "instance_s3" {
  statement {
    sid       = "ReadBucketLocations"
    actions   = ["s3:GetBucketLocation"]
    resources = [aws_s3_bucket.media.arn, aws_s3_bucket.deployment.arn]
  }

  statement {
    sid       = "CheckDedicatedMediaBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.media.arn]
  }

  statement {
    sid = "ManageMediaObjects"
    actions = [
      "s3:AbortMultipartUpload",
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:ListMultipartUploadParts",
      "s3:PutObject"
    ]
    resources = ["${aws_s3_bucket.media.arn}/${local.media_prefix}*"]
  }

  statement {
    sid       = "ListDeploymentPrefix"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.deployment.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["deployments", "deployments/*"]
    }
  }

  statement {
    sid = "ReadDeploymentBundles"
    actions = [
      "s3:GetObject"
    ]
    resources = ["${aws_s3_bucket.deployment.arn}/${local.deployment_prefix}*"]
  }
}

resource "aws_iam_role_policy" "instance_s3" {
  name   = "s3-media-and-deployments"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.instance_s3.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${var.project_name}-ec2"
  role = aws_iam_role.instance.name
}

resource "aws_instance" "app" {
  ami                         = local.ubuntu_arm64_ami_id
  instance_type               = var.instance_type
  availability_zone           = var.availability_zone
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.app.id]
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.app.name
  monitoring                  = false
  source_dest_check           = true
  user_data                   = local.k3s_user_data
  user_data_replace_on_change = false

  dynamic "credit_specification" {
    for_each = startswith(var.instance_type, "t4g.") ? [1] : []

    content {
      cpu_credits = "standard"
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_protocol_ipv6          = "disabled"
    http_put_response_hop_limit = 2
    http_tokens                 = "required"
    instance_metadata_tags      = "disabled"
  }

  root_block_device {
    delete_on_termination = true
    encrypted             = true
    volume_size           = var.root_volume_size_gib
    volume_type           = "gp3"

    tags = {
      Name      = "${var.project_name}-root"
      Component = "k3s-node"
    }
  }

  tags = {
    Name      = local.instance_name
    Component = "k3s-node"
  }

  # MySQL and Redis currently persist on this instance's root EBS volume.
  # Canonical's "current" AMI and bootstrap edits must not silently replace it.
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  depends_on = [
    aws_iam_role_policy.instance_s3,
    aws_iam_role_policy_attachment.ssm_core,
    aws_route_table_association.public,
    aws_vpc_security_group_egress_rule.all_ipv4
  ]
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.github_oidc_provider_arn == null ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = {
    Name      = "github-actions"
    Component = "deployment"
  }
}

data "aws_iam_policy_document" "github_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_owner}/${var.github_repository}:environment:${var.github_environment}"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name                 = "${var.project_name}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_assume_role.json
  max_session_duration = 3600

  tags = {
    Component = "deployment"
  }
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "ReadDeploymentBucketLocation"
    actions   = ["s3:GetBucketLocation"]
    resources = [aws_s3_bucket.deployment.arn]
  }

  statement {
    sid       = "ListDeploymentPrefix"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.deployment.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["deployments", "deployments/*"]
    }
  }

  statement {
    sid = "UploadAndDeleteDeploymentBundles"
    actions = [
      "s3:AbortMultipartUpload",
      "s3:DeleteObject",
      "s3:ListMultipartUploadParts",
      "s3:PutObject"
    ]
    resources = ["${aws_s3_bucket.deployment.arn}/${local.deployment_prefix}*"]
  }

  statement {
    sid     = "RunDeploymentOnTheNode"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.app.arn,
      "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}::document/AWS-RunShellScript"
    ]
  }

  statement {
    sid = "MonitorOrCancelDeployment"
    actions = [
      "ssm:CancelCommand",
      "ssm:DescribeInstanceInformation",
      "ssm:GetCommandInvocation"
    ]
    resources = ["*"]
  }

  statement {
    sid = "StartAndStopTheNode"
    actions = [
      "ec2:StartInstances",
      "ec2:StopInstances"
    ]
    resources = [aws_instance.app.arn]
  }

  statement {
    sid       = "ReadNodeState"
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "ec2-k3s-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
