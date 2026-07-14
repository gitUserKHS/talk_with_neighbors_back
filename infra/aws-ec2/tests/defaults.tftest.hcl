mock_provider "aws" {
  override_during = plan

  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }

  mock_data "aws_ssm_parameter" {
    defaults = {
      value = "ami-0123456789abcdef0"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

run "secure_low_cost_defaults" {
  command = plan

  assert {
    condition     = aws_instance.app.instance_type == "t4g.small"
    error_message = "The default node must remain the low-cost t4g.small ARM instance."
  }

  assert {
    condition     = aws_eip.app.domain == "vpc"
    error_message = "The portfolio node must allocate a VPC Elastic IP that survives stop/start."
  }

  assert {
    condition     = output.application_https_url == "https://talk-with-neighbors.duckdns.org"
    error_message = "The default application URL must be the canonical HTTPS DuckDNS origin."
  }

  assert {
    condition = (
      strcontains(aws_instance.app.user_data, "cluster-cidr: \"10.244.0.0/16\"") &&
      strcontains(aws_instance.app.user_data, "service-cidr: \"10.96.0.0/16\"") &&
      strcontains(aws_instance.app.user_data, "cluster-dns: \"10.96.0.10\"")
    )
    error_message = "The node bootstrap must render explicit, non-overlapping k3s network ranges."
  }

  assert {
    condition     = aws_instance.app.user_data_replace_on_change == false
    error_message = "Bootstrap edits must not replace the stateful single-node instance."
  }

  assert {
    condition = (
      strcontains(aws_instance.app.user_data, var.aws_cli_aarch64_sha256) &&
      strcontains(aws_instance.app.user_data, var.k3s_install_script_sha256)
    )
    error_message = "Root bootstrap downloads must remain pinned by SHA-256."
  }

  assert {
    condition = (
      aws_instance.app.metadata_options[0].http_tokens == "required" &&
      aws_instance.app.metadata_options[0].http_put_response_hop_limit == 2
    )
    error_message = "The k3s node must require IMDSv2 and permit the container hop."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.http.from_port == 80 &&
      aws_vpc_security_group_ingress_rule.http.to_port == 80 &&
      aws_vpc_security_group_ingress_rule.https.from_port == 443 &&
      aws_vpc_security_group_ingress_rule.https.to_port == 443
    )
    error_message = "Only the declared HTTP and HTTPS ingress rules should be public."
  }

  assert {
    condition     = aws_vpc_endpoint.s3.vpc_endpoint_type == "Gateway"
    error_message = "S3 access must use the no-hourly-charge Gateway endpoint."
  }

  assert {
    condition = (
      aws_s3_bucket.media.force_destroy == false &&
      aws_s3_bucket.deployment.force_destroy == false &&
      aws_s3_bucket_versioning.media.versioning_configuration[0].status == "Enabled" &&
      one([
        for rule in aws_s3_bucket_lifecycle_configuration.media.rule :
        rule.noncurrent_version_expiration[0].noncurrent_days
        if rule.id == "expire-noncurrent-media"
      ]) == 30
    )
    error_message = "Buckets must be deletion-protected, media versioning enabled, and old media versions bounded by default."
  }

  assert {
    condition     = length(aws_budgets_budget.monthly) == 0
    error_message = "The optional budget must not block deployments when no alert email is supplied."
  }

  assert {
    condition     = length(aws_iam_role_policy.instance_ses) == 0
    error_message = "The EC2 role must not receive SES permissions until a verified identity is explicitly supplied."
  }
}

run "budget_email_opt_in" {
  command = plan

  variables {
    budget_alert_email = "portfolio-owner@example.com"
  }

  assert {
    condition     = length(aws_budgets_budget.monthly) == 1
    error_message = "Supplying a budget email must create one monthly cost budget."
  }
}

run "ses_sender_identity_opt_in" {
  command = plan

  variables {
    ses_sender_identity_arn = "arn:aws:ses:ap-northeast-2:123456789012:identity/portfolio-owner@example.com"
  }

  assert {
    condition     = length(aws_iam_role_policy.instance_ses) == 1
    error_message = "Supplying a verified SES identity must add exactly one least-privilege EC2 role policy."
  }
}

run "reject_equal_vpc_and_cluster_cidrs" {
  command = plan

  variables {
    k3s_cluster_cidr = "10.42.0.0/16"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_cluster_cidr_nested_in_vpc" {
  command = plan

  variables {
    k3s_cluster_cidr = "10.42.128.0/17"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_vpc_nested_in_cluster_cidr" {
  command = plan

  variables {
    k3s_cluster_cidr = "10.32.0.0/12"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_service_cidr_nested_in_vpc" {
  command = plan

  variables {
    k3s_service_cidr = "10.42.128.0/17"
    k3s_cluster_dns  = "10.42.128.10"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_service_cidr_nested_in_cluster_cidr" {
  command = plan

  variables {
    k3s_service_cidr = "10.244.128.0/17"
    k3s_cluster_dns  = "10.244.128.10"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_subnet_outside_vpc" {
  command = plan

  variables {
    public_subnet_cidr = "10.41.1.0/24"
  }

  expect_failures = [aws_vpc.app]
}

run "reject_dns_outside_service_cidr" {
  command = plan

  variables {
    k3s_cluster_dns = "10.97.0.10"
  }

  expect_failures = [aws_vpc.app]
}

run "allow_adjacent_k3s_cidrs" {
  command = plan

  variables {
    k3s_cluster_cidr = "10.244.0.0/17"
    k3s_service_cidr = "10.244.128.0/17"
    k3s_cluster_dns  = "10.244.128.10"
  }

  assert {
    condition = (
      strcontains(aws_instance.app.user_data, "cluster-cidr: \"${var.k3s_cluster_cidr}\"") &&
      strcontains(aws_instance.app.user_data, "service-cidr: \"${var.k3s_service_cidr}\"") &&
      strcontains(aws_instance.app.user_data, "cluster-dns: \"${var.k3s_cluster_dns}\"")
    )
    error_message = "Validated adjacent k3s network ranges must be rendered into first-boot config."
  }
}

run "reject_ipv6_cluster_cidr" {
  command = plan

  variables {
    k3s_cluster_cidr = "fd00::/64"
  }

  expect_failures = [var.k3s_cluster_cidr]
}
