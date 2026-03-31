terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.0"
    }
  }
}

variable "cloudflare_api_token" {
  sensitive = true
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}

resource "cloudflare_record" "star_record" {
  zone_id = "<{ zone-id }>"
  name    = "*"
  content = "<{ ip }>"
  type    = "A"
  proxied = false
  ttl     = 60
}
