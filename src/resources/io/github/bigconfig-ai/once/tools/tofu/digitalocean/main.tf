terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

provider "digitalocean" {
  # token comes from DIGITALOCEAN_TOKEN in the environment
}

resource "digitalocean_droplet" "node1" {
  name     = "<{ digitalocean-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
<% if digitalocean-vpc-uuid|not-empty %>  vpc_uuid = "<{ digitalocean-vpc-uuid }>"
<% endif %>
  # SSH Keys are passed as a list of IDs or Fingerprints
  ssh_keys = ["<{ digitalocean-ssh-keys }>"]
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip = digitalocean_droplet.node1.ipv4_address
    sudoer = "root"
    name = "<{ package }>"
    user = "root"
  }
}
