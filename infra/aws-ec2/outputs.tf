output "instance_id" {
  description = "EC2 instance ID used by the SSM deployment workflow."
  value       = aws_instance.app.id
}

output "instance_public_ip" {
  description = "Stable Elastic IP associated with the single portfolio node."
  value       = aws_eip.app.public_ip
}

output "elastic_ip_allocation_id" {
  description = "Elastic IP allocation associated with the portfolio node."
  value       = aws_eip.app.id
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
  description = "Private, unversioned S3 bucket used for short-lived deployment bundles."
  value       = aws_s3_bucket.deployment.id
}

output "deployment_prefix" {
  description = "Deployment bundle prefix that expires after one day."
  value       = local.deployment_prefix
}

output "github_deploy_role_arn" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN GitHub Actions environment variable."
  value       = aws_iam_role.github_deploy.arn
}

output "instance_role_arn" {
  description = "EC2 role inherited by k3s pods through IMDSv2 for scoped S3 access."
  value       = aws_iam_role.instance.arn
}

output "monthly_budget_name" {
  description = "AWS Budgets name when budget_alert_email is configured; otherwise null."
  value       = try(aws_budgets_budget.monthly[0].name, null)
}
