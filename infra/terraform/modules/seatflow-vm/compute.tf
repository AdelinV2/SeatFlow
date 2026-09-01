resource "google_compute_instance" "seatflow_vm" {
  name         = var.vm_name
  project      = var.project_id
  zone         = var.zone
  machine_type = var.machine_type

  description         = "SeatFlow single-VM production host running Docker Compose microservices stack"
  deletion_protection = var.deletion_protection

  tags = ["seatflow-web", "seatflow-vm"]

  labels = {
    app         = "seatflow"
    environment = var.environment
    managed_by  = "terraform"
  }

  boot_disk {
    auto_delete = false
    mode        = "READ_WRITE"

    initialize_params {
      image = var.boot_disk_image
      size  = var.boot_disk_size_gb
      type  = var.boot_disk_type
      labels = {
        app         = "seatflow"
        environment = var.environment
      }
    }
  }

  network_interface {
    network    = google_compute_network.seatflow_vpc.name
    subnetwork = google_compute_subnetwork.seatflow_subnet.name

    access_config {
      nat_ip       = google_compute_address.seatflow_static_ip.address
      network_tier = "PREMIUM"
    }
  }

  service_account {
    email  = google_service_account.vm_runtime.email
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
    preemptible         = false
    provisioning_model  = "STANDARD"
  }

  metadata = {
    enable-oslogin = "TRUE"
    startup-script = file("${path.module}/../../../scripts/bootstrap-seatflow-vm.sh")
  }

  lifecycle {
    ignore_changes = [
      metadata["ssh-keys"],
      boot_disk[0].initialize_params[0].image
    ]
  }

  depends_on = [
    google_project_service.services,
    google_compute_subnetwork.seatflow_subnet,
    google_compute_firewall.allow_web,
    google_compute_firewall.allow_iap_ssh
  ]
}
