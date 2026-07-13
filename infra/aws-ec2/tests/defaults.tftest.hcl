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
