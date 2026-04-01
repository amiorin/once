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

resource "resend_domain" "domain1" {
  name           = "<{ domain }>"
  region         = "eu-west-1"
  open_tracking  = false
  click_tracking = false
  tls            = "opportunistic"
}

output "params" {
  value = {
    resend_domain = resend_domain.domain1
  }
}
