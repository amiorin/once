terraform {
  required_providers {
    resend = {
      source = "registry.terraform.io/y0n0zawa/resend"
    }
  }
}

variable "resend_api_key" {
  sensitive = true
}

provider "resend" {
  api_key = var.resend_api_key
}

resource "resend_domain" "domain1" {
  name           = "bigconfig.website"
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
