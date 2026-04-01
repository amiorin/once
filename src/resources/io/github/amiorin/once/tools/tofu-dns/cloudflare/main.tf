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

resource "cloudflare_record" "star_record" {
  zone_id = "<{ zone-id }>"
  name    = "*"
  content = "<{ ip }>"
  type    = "A"
  proxied = false
  ttl     = 60
}
