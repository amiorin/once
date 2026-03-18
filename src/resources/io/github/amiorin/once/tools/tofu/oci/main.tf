terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 8.4.0" # Using the modern 5.x branch
    }
  }
}

provider "oci" {
  config_file_profile = "DEFAULT"
}

data "oci_core_subnet" "public_subnet" {
  subnet_id = "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
}

data "oci_core_images" "ubuntu_24_04_arm" {
  compartment_id           = "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_instance" "ampere_vm" {
  availability_domain = "xTQn:EU-FRANKFURT-1-AD-1"
  compartment_id      = "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
  display_name        = "my-ampere-instance"
  shape               = "VM.Standard.A1.Flex"
  shape_config {
    ocpus         = 2
    memory_in_gbs = 12
  }
  create_vnic_details {
    subnet_id        = data.oci_core_subnet.public_subnet.id
    assign_public_ip = true
  }
  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_24_04_arm.images[0].id
    boot_volume_size_in_gbs = 100
    boot_volume_vpus_per_gb = 30
  }
  metadata = {
    ssh_authorized_keys = file("~/.ssh/id_ed25519.pub")
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
