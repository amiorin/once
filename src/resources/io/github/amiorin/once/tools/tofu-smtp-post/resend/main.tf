terraform {
  required_providers {
    resend = {
      source = "registry.terraform.io/y0n0zawa/resend"
    }
  }
}

provider "resend" {
  api_key = "<{ resend-api-key }>"
}

resource "resend_domain_verification" "domain1" {
  domain_id = "<{ id }>"
}
