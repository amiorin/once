# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {
  # token comes from HCLOUD_TOKEN in the environment
}

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable.
resource "hcloud_ssh_key" "machine" {
  name       = "<{ profile }>"
  public_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %>resource "hcloud_server" "node1" {
  name        = "<{ hcloud-name }>"
  image       = "<{ hcloud-image }>"
  server_type = "<{ hcloud-server-type }>"
  location    = "<{ hcloud-location  }>"
<% if ssh-keygen %>  ssh_keys    = [hcloud_ssh_key.machine.id]
<% else %>  ssh_keys    = ["<{ hcloud-ssh-keys }>"]
<% endif %>  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
<% if ssh-keygen %>    private_key = file("<{ ssh-private-key-path }>")
<% endif %>  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip = hcloud_server.node1.ipv4_address
    sudoer = "root"
    name = "<{ profile }>"
    user = "root"
<% if ssh-keygen %>    ssh_key_id = hcloud_ssh_key.machine.id
<% endif %>  }
}
