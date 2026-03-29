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

resource "cloudflare_record" "example_record" {
  zone_id = "f526f293f6aaa115c0e8fb498b3b99f8"
  name    = "test"
  content = "<{ ip }>"
  type    = "A"
  proxied = false
  ttl     = 60
}
