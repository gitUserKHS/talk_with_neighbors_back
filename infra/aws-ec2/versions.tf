terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  # Account-specific values live in the ignored backend.hcl file. Keeping the
  # backend declaration here makes the versioned, encrypted S3 state the
  # authoritative source while preserving a portable repository.
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # myApplications owns this user tag. Terraform must preserve it when it
  # reconciles resources whose lifecycle it otherwise manages.
  ignore_tags {
    keys = ["awsApplication"]
  }

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = "production"
      ManagedBy   = "Terraform"
    }
  }
}
