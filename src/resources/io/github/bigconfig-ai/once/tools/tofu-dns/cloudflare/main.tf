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

data "cloudflare_zone" "domain" {
  filter = {
    name = "<{ zone }>"
  }
}

# The A records live in apps.tf.json: one per application host, generated from
# the desired-state application list.

resource "cloudflare_zone_setting" "common_settings" {
  for_each = {
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
  zone_id    = data.cloudflare_zone.domain.id
  setting_id = each.key
  value      = each.value
}
