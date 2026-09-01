variable "project_id" {
  type        = string
  description = "GCP Project ID"
  default     = "seatflow-production-507311"
}

variable "region" {
  type        = string
  description = "GCP Region for regional resources"
  default     = "europe-west1"
}

variable "zone" {
  type        = string
  description = "GCP Zone for Compute Engine VM"
  default     = "europe-west1-b"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
  default     = "production"
}

variable "vm_name" {
  type        = string
  description = "Compute Engine instance name"
  default     = "seatflow-production"
}

variable "machine_type" {
  type        = string
  description = "Compute Engine machine type (e2-highmem-2 = 2 vCPU, 16 GiB RAM)"
  default     = "e2-highmem-2"
}

variable "boot_disk_size_gb" {
  type        = number
  description = "Boot disk size in GB"
  default     = 80
}

variable "boot_disk_type" {
  type        = string
  description = "Boot disk storage type (pd-balanced)"
  default     = "pd-balanced"
}

variable "boot_disk_image" {
  type        = string
  description = "Ubuntu OS boot image"
  default     = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
}

variable "github_repo" {
  type        = string
  description = "GitHub repository (owner/repo) for WIF authorization"
  default     = "AdelinV2/SeatFlow"
}

variable "domain_name" {
  type        = string
  description = "Apex domain name for SeatFlow production"
  default     = "seat-flow.me"
}

variable "enable_ops_agent" {
  type        = bool
  description = "Whether to enable Cloud Ops Agent alerting policies"
  default     = true
}

variable "deletion_protection" {
  type        = bool
  description = "Prevent accidental deletion of the production VM"
  default     = true
}

variable "network_name" {
  type        = string
  description = "Custom VPC network name"
  default     = "seatflow-vpc"
}

variable "subnet_name" {
  type        = string
  description = "Custom subnet name"
  default     = "seatflow-subnet"
}

variable "subnet_cidr" {
  type        = string
  description = "Custom subnet CIDR range"
  default     = "10.0.1.0/24"
}

variable "artifact_registry_repo_id" {
  type        = string
  description = "Artifact Registry Docker repository ID"
  default     = "seatflow"
}

variable "workload_identity_pool_id" {
  type        = string
  description = "Workload Identity Pool ID"
  default     = "seatflow-github-pool"
}

variable "workload_identity_provider_id" {
  type        = string
  description = "Workload Identity Provider ID"
  default     = "seatflow-github-provider"
}
