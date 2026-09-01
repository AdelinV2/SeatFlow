resource "google_compute_network" "seatflow_vpc" {
  name                    = var.network_name
  project                 = var.project_id
  auto_create_subnetworks = false
  description             = "Dedicated custom VPC network for SeatFlow single-VM runtime"

  depends_on = [google_project_service.services]
}

resource "google_compute_subnetwork" "seatflow_subnet" {
  name                     = var.subnet_name
  project                  = var.project_id
  region                   = var.region
  network                  = google_compute_network.seatflow_vpc.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true
  description              = "Subnet for SeatFlow Compute Engine VM"
}

resource "google_compute_address" "seatflow_static_ip" {
  name        = "${var.vm_name}-static-ip"
  project     = var.project_id
  region      = var.region
  description = "Static external IPv4 address for SeatFlow production web endpoint (${var.domain_name})"

  depends_on = [google_project_service.services]
}

# Firewall rule: Allow public HTTP (80) and HTTPS (443) ingress
resource "google_compute_firewall" "allow_web" {
  name        = "seatflow-allow-web"
  project     = var.project_id
  network     = google_compute_network.seatflow_vpc.name
  description = "Allow inbound HTTP and HTTPS traffic to SeatFlow Nginx edge proxy"

  direction = "INGRESS"
  priority  = 1000

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["seatflow-web"]
}

# Firewall rule: Allow administrative SSH strictly via Google Cloud IAP (Identity-Aware Proxy)
resource "google_compute_firewall" "allow_iap_ssh" {
  name        = "seatflow-allow-iap-ssh"
  project     = var.project_id
  network     = google_compute_network.seatflow_vpc.name
  description = "Allow authenticated SSH access exclusively via GCP Identity-Aware Proxy (IAP)"

  direction = "INGRESS"
  priority  = 1000

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  # Official Google Cloud Identity-Aware Proxy (IAP) CIDR range
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["seatflow-vm"]
}
