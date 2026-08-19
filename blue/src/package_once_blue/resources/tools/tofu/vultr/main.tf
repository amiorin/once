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

resource "vultr_instance" "node1" {
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
  ssh_key_ids = ["<{ vultr-ssh-keys }>"]
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
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
    ip = vultr_instance.node1.main_ip
    sudoer = "root"
    name = "<{ profile }>"
    user = "root"
  }
}
