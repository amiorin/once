terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

provider "cloudflare" {
  # api_token comes from CLOUDFLARE_API_TOKEN in the environment
}

locals {
  zones = toset(<{ zones-hcl|safe }>)
  common_settings = {
    always_use_https         = "on"
    automatic_https_rewrites = "on"
    tls_1_3                  = "on"
    browser_check            = "on"
    ipv6                     = "on"
    brotli                   = "on"
    early_hints              = "on"
    rocket_loader            = "on"
    ssl                      = "strict"
  }
  zone_settings = {
    for pair in setproduct(local.zones, keys(local.common_settings)) :
    "${pair[0]}:${pair[1]}" => {
      zone       = pair[0]
      setting_id = pair[1]
      value      = local.common_settings[pair[1]]
    }
  }
}

data "cloudflare_zone" "domains" {
  for_each = local.zones

  filter = {
    name = each.value
  }
}

# The A records live in apps.tf.json: one per application host, generated from
# the desired-state application list. smtp.tf.json holds each sending domain's
# records. Both select the matching data.cloudflare_zone.domains entry.

resource "cloudflare_zone_setting" "common_settings" {
  for_each = local.zone_settings

  zone_id    = data.cloudflare_zone.domains[each.value.zone].id
  setting_id = each.value.setting_id
  value      = each.value.value
}
