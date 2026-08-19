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
  label    = "<{ vultr-name }>"
  hostname = "<{ vultr-name }>"
  region   = "<{ vultr-region }>"
  plan     = "<{ vultr-plan }>"
  os_id    = <{ vultr-os-id }>
  # SSH keys are passed as a list of key ids already in the account
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
