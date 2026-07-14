variable "project_name" {
  description = "Lowercase prefix used for AWS resource names and tags."
  type        = string
  default     = "talk-with-neighbors"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{1,28}[a-z0-9])$", var.project_name))
    error_message = "project_name must be 3-30 lowercase letters, digits, or hyphens and cannot start or end with a hyphen."
  }
}

variable "aws_region" {
  description = "AWS region for the single-node deployment."
  type        = string
  default     = "ap-northeast-2"
}

variable "application_domain" {
  description = "Public DNS name whose A record points to the portfolio node Elastic IP."
  type        = string
  default     = "talk-with-neighbors.duckdns.org"

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?[.])+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", var.application_domain))
    error_message = "application_domain must be a lowercase DNS name without a scheme, port, or path."
  }
}

variable "availability_zone" {
  description = "Availability Zone for the public subnet and EC2 instance."
  type        = string
  default     = "ap-northeast-2a"
}

variable "vpc_cidr" {
  description = "IPv4 CIDR for the dedicated low-cost VPC."
  type        = string
  default     = "10.42.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr))
    error_message = "vpc_cidr must be a valid IPv4 CIDR."
  }
}

variable "public_subnet_cidr" {
  description = "IPv4 CIDR for the single public subnet. It must be contained by vpc_cidr."
  type        = string
  default     = "10.42.1.0/24"

  validation {
    condition     = can(cidrnetmask(var.public_subnet_cidr))
    error_message = "public_subnet_cidr must be a valid IPv4 CIDR."
  }
}

variable "k3s_cluster_cidr" {
  description = "IPv4 CIDR used for k3s pod addresses. It must not overlap the VPC or service CIDR."
  type        = string
  default     = "10.244.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.k3s_cluster_cidr))
    error_message = "k3s_cluster_cidr must be a valid IPv4 CIDR."
  }
}

variable "k3s_service_cidr" {
  description = "IPv4 CIDR used for Kubernetes service addresses. It must not overlap the VPC or pod CIDR."
  type        = string
  default     = "10.96.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.k3s_service_cidr))
    error_message = "k3s_service_cidr must be a valid IPv4 CIDR."
  }
}

variable "k3s_cluster_dns" {
  description = "Plain IPv4 address assigned to CoreDNS inside k3s_service_cidr."
  type        = string
  default     = "10.96.0.10"

  validation {
    condition     = can(cidrnetmask("${var.k3s_cluster_dns}/32"))
    error_message = "k3s_cluster_dns must be an IPv4 address without a prefix."
  }
}

variable "instance_type" {
  description = "ARM64 EC2 type. t4g.small is the default low-cost choice and may be eligible for new-account AWS Free Tier credits; verify the account's billing offer."
  type        = string
  default     = "t4g.small"

  validation {
    condition = can(regex(
      "^(t4g|a1|c6g|c6gd|c6gn|c7g|c7gd|c7gn|c8g|m6g|m6gd|m7g|m7gd|m8g|r6g|r6gd|r7g|r7gd|r8g)[.][a-z0-9]+$",
      var.instance_type
    ))
    error_message = "instance_type must be an ARM64/Graviton EC2 type compatible with the Ubuntu ARM64 AMI."
  }
}

variable "root_volume_size_gib" {
  description = "Encrypted gp3 root volume size in GiB. 30 GiB is a practical minimum for k3s images, logs, and the local database."
  type        = number
  default     = 30

  validation {
    condition     = var.root_volume_size_gib >= 20 && var.root_volume_size_gib <= 16384 && floor(var.root_volume_size_gib) == var.root_volume_size_gib
    error_message = "root_volume_size_gib must be a whole number between 20 and 16384."
  }
}

variable "k3s_version" {
  description = "Pinned k3s release installed during first boot."
  type        = string
  default     = "v1.34.8+k3s1"

  validation {
    condition     = can(regex("^v[0-9]+[.][0-9]+[.][0-9]+[+]k3s[0-9]+$", var.k3s_version))
    error_message = "k3s_version must look like v1.34.8+k3s1."
  }
}

variable "k3s_install_script_sha256" {
  description = "SHA-256 of install.sh from the pinned k3s_version Git tag. Update both values together."
  type        = string
  default     = "40b487f0d8ef4f5d1bf422e7bb6228cc7789c40ecc66c5ab067d396bbee9816e"

  validation {
    condition     = can(regex("^[a-f0-9]{64}$", var.k3s_install_script_sha256))
    error_message = "k3s_install_script_sha256 must be a lowercase 64-character SHA-256 digest."
  }
}

variable "aws_cli_version" {
  description = "Pinned AWS CLI v2 release installed on the ARM64 node."
  type        = string
  default     = "2.35.22"

  validation {
    condition     = can(regex("^2[.][0-9]+[.][0-9]+$", var.aws_cli_version))
    error_message = "aws_cli_version must be an AWS CLI v2 semantic version such as 2.35.22."
  }
}

variable "aws_cli_aarch64_sha256" {
  description = "SHA-256 of the pinned AWS CLI v2 Linux aarch64 zip archive."
  type        = string
  default     = "022e392e079ada523be29cdbb45061a74be6344179f8fddf2b8183f1898683f1"

  validation {
    condition     = can(regex("^[a-f0-9]{64}$", var.aws_cli_aarch64_sha256))
    error_message = "aws_cli_aarch64_sha256 must be a lowercase 64-character SHA-256 digest."
  }
}

variable "media_bucket_force_destroy" {
  description = "Allow Terraform to delete a non-empty media bucket. Keep false for deletion protection."
  type        = bool
  default     = false
}

variable "media_noncurrent_version_expiration_days" {
  description = "Days to retain replaced or deleted media object versions before S3 expires them."
  type        = number
  default     = 30

  validation {
    condition = (
      var.media_noncurrent_version_expiration_days >= 1 &&
      var.media_noncurrent_version_expiration_days <= 36500 &&
      floor(var.media_noncurrent_version_expiration_days) == var.media_noncurrent_version_expiration_days
    )
    error_message = "media_noncurrent_version_expiration_days must be a whole number between 1 and 36500."
  }
}

variable "budget_alert_email" {
  description = "Email address for optional AWS Budgets alerts. Leave null to skip budget creation and email confirmation."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.budget_alert_email == null || can(regex("^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$", var.budget_alert_email))
    error_message = "budget_alert_email must be null or a plausible email address."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly AWS cost budget in USD when budget_alert_email is set."
  type        = number
  default     = 10

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd must be greater than zero."
  }
}

variable "github_owner" {
  description = "GitHub organization or user that owns the deployment repository."
  type        = string
  default     = "gitUserKHS"

  validation {
    condition     = can(regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$", var.github_owner))
    error_message = "github_owner must be a valid GitHub organization or user name."
  }
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the deployment role."
  type        = string
  default     = "talk_with_neighbors_back"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository contains unsupported characters."
  }
}

variable "github_environment" {
  description = "Protected GitHub Environment whose OIDC subject may assume the deployment role."
  type        = string
  default     = "production"

  validation {
    condition     = length(trimspace(var.github_environment)) > 0 && !strcontains(var.github_environment, ":")
    error_message = "github_environment cannot be empty or contain a colon."
  }
}

variable "github_oidc_provider_arn" {
  description = "Existing GitHub Actions OIDC provider ARN. Leave null to create it in this AWS account."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.github_oidc_provider_arn == null || can(regex("^arn:[^:]+:iam::[0-9]{12}:oidc-provider/token[.]actions[.]githubusercontent[.]com$", var.github_oidc_provider_arn))
    error_message = "github_oidc_provider_arn must be null or the ARN of the token.actions.githubusercontent.com provider."
  }
}

variable "ses_sender_identity_arn" {
  description = "Verified Amazon SES email/domain identity ARN allowed to send verification mail. Leave null to keep email sending disabled."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.ses_sender_identity_arn == null || can(regex("^arn:[^:]+:ses:[a-z0-9-]+:[0-9]{12}:identity/.+$", var.ses_sender_identity_arn))
    error_message = "ses_sender_identity_arn must be null or a verified Amazon SES identity ARN."
  }
}
