terraform {
  required_providers {
    resend = {
      source = "registry.terraform.io/y0n0zawa/resend"
    }
  }
}

provider "resend" {
  # api_key comes from RESEND_API_KEY in the environment
}

locals {
  domain_ids = <{ domain-ids-hcl|safe }>
}

resource "terraform_data" "trigger" {
  input = timestamp()
}

resource "resend_domain_verification" "domains" {
  for_each = local.domain_ids

  domain_id = each.value
  lifecycle {
    replace_triggered_by = [
      terraform_data.trigger
    ]
  }
}
