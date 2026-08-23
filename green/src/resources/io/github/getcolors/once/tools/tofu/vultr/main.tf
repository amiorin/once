# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.0"
    }
  }
}

provider "vultr" {
  # api key comes from VULTR_API_KEY in the environment
}

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable.
resource "vultr_ssh_key" "machine" {
  name    = "<{ profile }>"
  ssh_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %>resource "vultr_instance" "node1" {
  # `label` is the console name and updates in place, which is what every other
  # compute template here sets. There is deliberately no `hostname`: Vultr's API
  # implements a hostname change as an OS reinstall, so the provider marks that
  # attribute ForceNew, and editing vultr-name would destroy the instance and
  # its disk rather than rename it. The attribute is Optional+Computed, so
  # omitting it keeps whatever Vultr assigned and produces no diff on an
  # instance that already exists.
  label  = "<{ vultr-name }>"
  region = "<{ vultr-region }>"
  plan   = "<{ vultr-plan }>"
  os_id  = <{ vultr-os-id }>
  # SSH keys are passed as a list of key ids already in the account. This is
  # ForceNew too: changing the key set destroys and recreates the instance
  # instead of re-authorizing it, so a disposable key lasts the life of the
  # deployment -- rotate it by rebuilding, never by editing vultr-ssh-keys on a
  # machine whose disk you intend to keep.
<% if ssh-keygen %>  ssh_key_ids = [vultr_ssh_key.machine.id]
<% else %>  ssh_key_ids = ["<{ vultr-ssh-keys }>"]
<% endif %>  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
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
    ip = vultr_instance.node1.main_ip
    sudoer = "root"
    name = "<{ profile }>"
    user = "root"
<% if ssh-keygen %>    ssh_key_id = vultr_ssh_key.machine.id
<% endif %>  }
}
