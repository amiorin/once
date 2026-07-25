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

resource "terraform_data" "trigger" {
  input = timestamp()
}

resource "resend_domain_verification" "domain1" {
  domain_id = "<{ id }>"
  lifecycle {
    replace_triggered_by = [
      terraform_data.trigger
    ]
  }
}
