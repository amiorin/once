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
  zones = toset(<{ zones-hcl|safe }>)
}

resource "resend_domain" "domains" {
  for_each = local.zones

  name           = "notifications.${each.value}"
  region         = "eu-west-1"
  open_tracking  = false
  click_tracking = false
  tls            = "opportunistic"
}

output "params" {
  value = {
    domains = [
      for zone in sort(keys(resend_domain.domains)) : {
        zone    = zone
        records = resend_domain.domains[zone].records
        id      = resend_domain.domains[zone].id
      }
    ]
  }
  sensitive = true
}
