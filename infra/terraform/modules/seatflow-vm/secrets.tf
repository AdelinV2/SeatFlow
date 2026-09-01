# Secret Manager Containers for SeatFlow Runtime Configuration & Secrets
# INVARIANT: Only empty containers are provisioned by Terraform.
# Real secret payloads are injected out-of-band via gcloud secrets versions add.
# The monitoring identity pair is used only to mint short-lived Supabase access
# tokens for Prometheus; it is never written to source control or image layers.

locals {
  secret_names = [
    "postgres-admin-password",
    "postgres-app-password",
    "redis-password",
    "stripe-api-key",
    "stripe-webhook-secret",
    "resend-api-key",
    "grafana-admin-password",
    "prometheus-scrape-token",
    "prometheus-identity-email",
    "prometheus-identity-password"
  ]
}

resource "google_secret_manager_secret" "secrets" {
  for_each  = toset(local.secret_names)
  secret_id = each.value
  project   = var.project_id

  replication {
    auto {}
  }

  labels = {
    app         = "seatflow"
    environment = var.environment
    managed_by  = "terraform"
  }

  depends_on = [google_project_service.services]
}

