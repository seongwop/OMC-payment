data "google_compute_image" "debian" {
  family  = "debian-12"
  project = "debian-cloud"
}

resource "google_compute_address" "internal" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${each.key}-internal-ip"
  address_type = "INTERNAL"
  address      = each.value.internal_ip
  region       = var.region
  subnetwork   = google_compute_subnetwork.main.id
}

resource "google_compute_disk" "infra_data" {
  for_each = var.infra_data_disks

  name = "${var.name_prefix}-${each.key}"
  type = each.value.type
  zone = var.zone
  size = each.value.size_gb

  labels = {
    app  = var.name_prefix
    role = "infra"
  }
}

resource "google_compute_instance" "vm" {
  for_each = var.vm_specs

  name         = "${var.name_prefix}-${each.key}-vm"
  machine_type = each.value.machine_type
  zone         = var.zone
  tags         = [var.name_prefix, "${var.name_prefix}-ssh", "${var.name_prefix}-${each.value.role}"]

  labels = {
    app  = var.name_prefix
    role = each.value.role
  }

  boot_disk {
    auto_delete = true

    initialize_params {
      image = data.google_compute_image.debian.self_link
      size  = each.value.boot_disk_gb
      type  = "pd-balanced"
    }
  }

  dynamic "attached_disk" {
    for_each = each.key == "infra" ? google_compute_disk.infra_data : {}

    content {
      source      = attached_disk.value.id
      device_name = attached_disk.key
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.main.id
    network_ip = google_compute_address.internal[each.key].address
  }

  metadata = {
    block-project-ssh-keys = "false"
  }

  metadata_startup_script = replace(
    file("${path.module}/scripts/startup.sh"),
    "__ROLE__",
    each.value.role
  )

  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  scheduling {
    automatic_restart   = true
    on_host_maintenance = "MIGRATE"
    provisioning_model  = "STANDARD"
  }

  allow_stopping_for_update = true
  deletion_protection       = false

  lifecycle {
    # Operational bootstrap changes are delivered by the deployment scripts.
    # Replacing a VM just to update this script would wipe its boot-disk runtime.
    ignore_changes = [metadata_startup_script]
  }

  depends_on = [
    google_project_iam_member.vm_artifact_reader,
    google_project_iam_member.vm_logging_writer,
    google_project_iam_member.vm_monitoring_writer,
  ]
}
