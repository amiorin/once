terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0"
    }
  }
}

provider "google" {
  project = "<{ google-project }>"
  region  = "<{ google-region }>"
  zone    = "<{ google-zone }>"
}

resource "google_compute_network" "network" {
  name                    = "<{ google-name }>"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "public" {
  name          = "<{ google-name }>"
  region        = "<{ google-region }>"
  network       = google_compute_network.network.id
  ip_cidr_range = "<{ google-subnet-cidr }>"
}

resource "google_compute_firewall" "node1" {
  name    = "<{ google-name }>"
  network = google_compute_network.network.name

  allow {
    protocol = "tcp"
    ports    = ["22", "80", "443"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["<{ google-name }>"]
}

resource "google_compute_address" "node1" {
  name   = "<{ google-name }>"
  region = "<{ google-region }>"
}

resource "google_compute_instance" "node1" {
  name         = "<{ google-name }>"
  machine_type = "<{ google-machine-type }>"
  zone         = "<{ google-zone }>"
  tags         = ["<{ google-name }>"]

  boot_disk {
    initialize_params {
      image = "<{ google-image-id }>"
      size  = <{ google-boot-disk-size-gb }>
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.public.id
    access_config {
      nat_ip = google_compute_address.node1.address
    }
  }
<% if google-allow-stopping-for-update %>
  # Google cannot change some attributes — the machine type among them — while
  # the instance runs. Opt in so tofu may stop it briefly to apply such a
  # change instead of failing the apply.
  allow_stopping_for_update = true
<% endif %>
  metadata = {
    ssh-keys = "ubuntu:${trimspace(file("<{ google-ssh-authorized-keys }>"))}"
  }

  connection {
    type  = "ssh"
    user  = "ubuntu"
    agent = true
    host  = google_compute_address.node1.address
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
    ip     = google_compute_address.node1.address
    sudoer = "ubuntu"
    uid    = "1000"
    name   = "<{ profile }>"
    user   = "ubuntu"
  }
}
