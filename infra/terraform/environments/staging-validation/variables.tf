variable "project_id" {
  type        = string
  description = "GCP Project ID for staging validation"
  default     = "seatflow-staging-dryrun"
}

variable "region" {
  type        = string
  description = "GCP Region"
  default     = "europe-west1"
}

variable "zone" {
  type        = string
  description = "GCP Zone"
  default     = "europe-west1-b"
}

variable "github_repo" {
  type        = string
  description = "GitHub repository"
  default     = "AdelinV2/SeatFlow"
}
