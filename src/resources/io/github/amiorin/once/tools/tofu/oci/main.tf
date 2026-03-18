terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 8.4.0" # Using the modern 5.x branch
    }
  }
}

provider "oci" {
  config_file_profile = "<{ config-file-profile }>"
}

data "oci_core_subnet" "public_subnet" {
  subnet_id = "<{ subnet-id }>"
}

data "oci_core_images" "ubuntu_24_04_arm" {
  compartment_id           = "<{ compartment-id }>"
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_instance" "ampere_vm" {
  availability_domain = "<{ availability-domain }>"
  compartment_id      = "<{ compartment-id }>"
  display_name        = "<{ display-name }>"
  shape               = "<{ shape }>"
  shape_config {
    ocpus         = <{ ocpus }>
    memory_in_gbs = <{ memory-in-gbs }>
  }
  create_vnic_details {
    subnet_id        = data.oci_core_subnet.public_subnet.id
    assign_public_ip = true
  }
  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_24_04_arm.images[0].id
    boot_volume_size_in_gbs = <{ boot-volume-size-in-gbs }>
    boot_volume_vpus_per_gb = <{ boot-volume-vpus-per-gb }>
  }
  metadata = {
    ssh_authorized_keys = file("<{ ssh-authorized-keys }>")
  }
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.public_ip
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
}

output "params" {
  value = {
    ip = oci_core_instance.ampere_vm.public_ip
    sudoer = "ubuntu"
    uid = "1001"
    name = "<{ package }>"
    user = "ubuntu"
  }
}
