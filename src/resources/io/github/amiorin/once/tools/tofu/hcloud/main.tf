# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

# Set the variable value in *.tfvars file
# or using the -var="hcloud_token=..." CLI option
variable "hcloud_token" {
  sensitive = true
}

# Configure the Hetzner Cloud Provider
provider "hcloud" {
  token = var.hcloud_token
}

resource "hcloud_server" "node1" {
  name        = "<{ name }>"
  image       = "<{ image }>"
  server_type = "<{ server-type }>"
  location    = "<{ location  }>"
  ssh_keys    = ["<{ ssh-keys }>"]
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
}

output "params" {
  value = {
    ip = hcloud_server.node1.ipv4_address
    sudoer = "root"
    name = "<{ package }>"
    user = "root"
  }
}
