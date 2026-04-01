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

resource "resend_domain_verification" "domain1" {
  domain_id = "<{ id }>"
}
