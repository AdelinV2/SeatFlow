# Service Account for VM Runtime (Hosts the SeatFlow Docker Compose stack)
resource "google_service_account" "vm_runtime" {
  account_id   = "seatflow-vm-runtime"
  project      = var.project_id
  display_name = "SeatFlow VM Runtime Service Account"
  description  = "Identity attached to Compute Engine VM for pulling Artifact Registry images and accessing Secret Manager"

  depends_on = [google_project_service.services]
}

# Runtime SA: Pull Docker images from Artifact Registry
resource "google_artifact_registry_repository_iam_member" "vm_runtime_ar_reader" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.seatflow_repo.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.vm_runtime.email}"
}

# Runtime SA: Access Secret Manager secret versions (scoped strictly to SeatFlow runtime secrets)
resource "google_secret_manager_secret_iam_member" "vm_runtime_secret_accessor" {
  for_each  = google_secret_manager_secret.secrets
  project   = var.project_id
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.vm_runtime.email}"
}

# Runtime SA: Emit Cloud Logging logs
resource "google_project_iam_member" "vm_runtime_log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.vm_runtime.email}"
}

# Runtime SA: Emit Cloud Monitoring metrics
resource "google_project_iam_member" "vm_runtime_metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.vm_runtime.email}"
}

# Service Account for GitHub Actions CD Deployment
resource "google_service_account" "github_deploy" {
  account_id   = "seatflow-github-deploy"
  project      = var.project_id
  display_name = "SeatFlow GitHub Actions Deploy Service Account"
  description  = "Identity assumed by GitHub Actions via WIF to build/push Docker images and deploy releases"

  depends_on = [google_project_service.services]
}

# Deploy SA: Push Docker images to Artifact Registry
resource "google_artifact_registry_repository_iam_member" "github_deploy_ar_writer" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.seatflow_repo.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.github_deploy.email}"
}

# Deploy SA: Compute Viewer (describe instances and verify deployment target status)
resource "google_project_iam_member" "github_deploy_compute_viewer" {
  project = var.project_id
  role    = "roles/compute.viewer"
  member  = "serviceAccount:${google_service_account.github_deploy.email}"
}

# Deploy SA: Service Account User on VM Runtime Identity
resource "google_service_account_iam_member" "github_deploy_sa_user" {
  service_account_id = google_service_account.vm_runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github_deploy.email}"
}

# Deploy SA: IAP Tunnel Resource Accessor for remote deployment via IAP SSH
resource "google_project_iam_member" "github_deploy_iap_tunnel" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "serviceAccount:${google_service_account.github_deploy.email}"
}

# Deploy SA: OS Admin Login for automated SSH command execution on OS Login-enabled VM
resource "google_project_iam_member" "github_deploy_os_admin_login" {
  project = var.project_id
  role    = "roles/compute.osAdminLogin"
  member  = "serviceAccount:${google_service_account.github_deploy.email}"
}


