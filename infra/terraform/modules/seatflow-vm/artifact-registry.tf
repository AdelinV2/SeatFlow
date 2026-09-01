resource "google_artifact_registry_repository" "seatflow_repo" {
  repository_id = var.artifact_registry_repo_id
  project       = var.project_id
  location      = var.region
  format        = "DOCKER"
  description   = "Docker container registry for SeatFlow microservices and frontend images"

  labels = {
    app         = "seatflow"
    environment = var.environment
    managed_by  = "terraform"
  }

  depends_on = [google_project_service.services]
}
