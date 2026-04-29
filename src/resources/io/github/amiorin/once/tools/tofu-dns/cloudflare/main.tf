terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.0"
    }
  }
}

provider "cloudflare" {
  api_token = "<{ cloudflare-api-token }>"
}

data "cloudflare_zone" "domain" {
  name = "<{ domain }>"
}

resource "cloudflare_record" "star_record" {
  zone_id = data.cloudflare_zone.domain.id
  name    = "*"
  content = "<{ ip }>"
  type    = "A"
  proxied = false
  ttl     = 60
}

resource "cloudflare_record" "at_record" {
  zone_id = data.cloudflare_zone.domain.id
  name    = "@"
  content = "<{ ip }>"
  type    = "A"
  proxied = false
  ttl     = 60
}
