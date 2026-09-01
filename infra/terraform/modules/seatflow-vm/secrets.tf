# Secret Manager Containers for SeatFlow Runtime Configuration & Secrets
# INVARIANT: Only empty containers are provisioned by Terraform.
# Real secret payloads are injected out-of-band via gcloud secrets versions add.

locals {
  secret_names = [
    "postgres-admin-password",
    "postgres-user-service-password",
    "postgres-seatmap-service-password",
    "postgres-event-service-password",
    "postgres-reservation-service-password",
    "postgres-payment-service-password",
    "postgres-ticket-service-password",
    "postgres-notification-service-password",
    "stripe-api-key",
    "stripe-webhook-secret",
    "jwt-issuer-uri",
    "jwt-jwk-set-uri",
    "resend-api-key",
    "cors-allowed-origins"
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
