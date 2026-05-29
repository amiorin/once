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
  name           = "notifications.<{ domain }>"
  region         = "eu-west-1"
  open_tracking  = false
  click_tracking = false
  tls            = "opportunistic"
}

output "params" {
  value = {
    records = resend_domain.domain1.records
    id = resend_domain.domain1.id
    smtp_username = "<{ resend-username }>"
    smtp_password = "<{ resend-password }>"
    smtp_server = "<{ resend-server }>"
    smtp_port = "<{ resend-port }>"
    smtp_use_starttls = true
  }
  sensitive = true
}
