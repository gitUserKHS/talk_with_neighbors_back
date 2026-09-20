output "instance_id" {
  description = "EC2 instance ID used by the SSM deployment workflow."
  value       = aws_instance.app.id
}

output "instance_public_ip" {
  description = "Public IPv4 address auto-assigned to the node at the last apply or refresh. It changes on every stop/start and is not billed while the node is stopped; the DuckDNS record follows it automatically."
  value       = aws_instance.app.public_ip
}

output "duckdns_token_parameter_name" {
  description = "SSM SecureString parameter that must hold the DuckDNS token; null when no DNS automation is granted."
  value       = var.duckdns_token_parameter_name
}

output "application_https_url" {
  description = "Canonical HTTPS origin served by Traefik with automatic Let's Encrypt renewal."
  value       = "https://${var.application_domain}"
}

output "ubuntu_arm64_ami_id" {
  description = "Ubuntu 24.04 ARM64 AMI resolved from Canonical's public SSM parameter."
  value       = local.ubuntu_arm64_ami_id
}

output "media_bucket_name" {
  description = "Private, versioned S3 bucket used for application media."
  value       = aws_s3_bucket.media.id
}

output "media_prefix" {
  description = "Object prefix granted to the application instance role."
  value       = local.media_prefix
}

output "deployment_bucket_name" {
  description = "Private S3 bucket used for deployment bundles and successful-release history."
  value       = aws_s3_bucket.deployment.id
}

output "deployment_prefix" {
  description = "Deployment bundle prefix that expires after one day."
  value       = local.deployment_prefix
}

output "mysql_backup_prefix" {
  description = "Verified logical-backup prefix retained according to mysql_backup_retention_days."
  value       = local.mysql_backup_prefix
}

output "mysql_backup_bucket_name" {
  description = "Deletion-protected, private, versioned S3 bucket for verified MySQL logical backups."
  value       = aws_s3_bucket.mysql_backup.id
}

output "release_history_prefix" {
  description = "Successful immutable-release manifest prefix used by guarded rollback."
  value       = local.release_prefix
}

output "github_deploy_role_arn" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN GitHub Actions environment variable."
  value       = aws_iam_role.github_deploy.arn
}

output "github_monitor_role_arn" {
  description = "Set this as the AWS_MONITOR_ROLE_ARN variable in the production-monitor GitHub Environment."
  value       = aws_iam_role.github_monitor.arn
}

output "instance_role_arn" {
  description = "EC2 role inherited by k3s pods through IMDSv2 for scoped S3 access."
  value       = aws_iam_role.instance.arn
}

output "monthly_budget_name" {
  description = "AWS Budgets name when budget_alert_email is configured; otherwise null."
  value       = try(aws_budgets_budget.monthly[0].name, null)
}
