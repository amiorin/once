terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 8.4.0" # Using the modern 5.x branch
    }
  }
}

provider "oci" {
  config_file_profile = "<{ oci-config-file-profile }>"
}

data "oci_core_subnet" "public_subnet" {
  subnet_id = "<{ oci-subnet-id }>"
}

<% if oci-image-id|not-empty %># The image is pinned, so there is no lookup at all. source_id is ForceNew on
# oci_core_instance, and left to a data source it is whatever Canonical
# published most recently — meaning a routine apply, months after the last one,
# proposes destroying the VM because an image appeared that nobody asked for.
<% else %># No pinned image: take the newest one compatible with the shape the instance
# actually launches on. OCI images carry a compatibility list, so filtering on a
# different shape can return an image the instance cannot boot — this read
# A1.Flex while the instance took whatever oci-shape said, and only kept working
# because A1 and A2 images overlap.
#
# Convenient for a first create, and a moving target thereafter: set
# oci-image-id once the stack is real. The resource name is left alone
# deliberately — it is a state address, and renaming it would look like a
# replacement to every existing stack.
data "oci_core_images" "ubuntu_24_04_arm" {
  compartment_id           = "<{ oci-compartment-id }>"
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "<{ oci-shape }>"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}
<% endif %>
resource "oci_core_instance" "ampere_vm" {
  availability_domain = "<{ oci-availability-domain }>"
  compartment_id      = "<{ oci-compartment-id }>"
  display_name        = "<{ oci-display-name }>"
  shape               = "<{ oci-shape }>"
  shape_config {
    ocpus         = <{ oci-ocpus }>
    memory_in_gbs = <{ oci-memory-in-gbs }>
  }
  create_vnic_details {
    subnet_id        = data.oci_core_subnet.public_subnet.id
    assign_public_ip = true
  }
  source_details {
    source_type             = "image"
<% if oci-image-id|not-empty %>    source_id               = "<{ oci-image-id }>"
<% else %>    source_id               = data.oci_core_images.ubuntu_24_04_arm.images[0].id
<% endif %>    boot_volume_size_in_gbs = <{ oci-boot-volume-size-in-gbs }>
    boot_volume_vpus_per_gb = <{ oci-boot-volume-vpus-per-gb }>
  }
  metadata = {
    ssh_authorized_keys = file("<{ oci-ssh-authorized-keys }>")
  }
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.public_ip
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
    ip = oci_core_instance.ampere_vm.public_ip
    sudoer = "ubuntu"
    uid = "1001"
    name = "<{ profile }>"
    user = "ubuntu"
  }
}
