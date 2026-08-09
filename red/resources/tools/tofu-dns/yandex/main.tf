# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = ">= 0.120"
    }
  }
}

provider "yandex" {
  # token comes from YC_TOKEN in the environment
  cloud_id  = "<{ yandex-cloud-id }>"
  folder_id = "<{ yandex-folder-id }>"
}

locals {
  zones = toset(<{ zones-hcl|safe }>)
}

# Unlike Cloudflare, where every zone must already exist in the account, the
# public zones are created here: Yandex serves every public zone from the same
# nameservers (ns1.yandexcloud.net, ns2.yandexcloud.net), so delegation is a
# one-time NS change at the registrar and the zone itself carries no further
# configuration. Keep the display name under Yandex's 63-character limit even
# for a valid long domain; the hash makes truncated names deterministic and
# distinct. The actual DNS zone remains the full value below.
resource "yandex_dns_zone" "domains" {
  for_each = local.zones

  name   = "zone-${substr(replace(each.value, ".", "-"), 0, 40)}-${substr(md5(each.value), 0, 12)}"
  zone   = "${each.value}."
  public = true
}

# The A records live in apps.tf.json: one per application host, generated from
# the desired-state application list. smtp.tf.json holds each sending domain's
# records. Both select the matching yandex_dns_zone.domains entry.
