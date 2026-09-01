# Staging Validation Root
# Validates module composition and HCL syntax in CI/CD dry runs without provisioning a second permanent runtime VM.

module "seatflow_vm" {
  source = "../../modules/seatflow-vm"

  project_id                    = var.project_id
  region                        = var.region
  zone                          = var.zone
  environment                   = "staging-validation"
  vm_name                       = "seatflow-staging-dryrun"
  machine_type                  = "e2-highmem-2"
  boot_disk_size_gb             = 80
  boot_disk_type                = "pd-balanced"
  boot_disk_image               = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
  github_repo                   = var.github_repo
  domain_name                   = "staging.seat-flow.me"
  enable_ops_agent              = false
  deletion_protection           = false
  network_name                  = "seatflow-staging-vpc"
  subnet_name                   = "seatflow-staging-subnet"
  subnet_cidr                   = "10.0.2.0/24"
  artifact_registry_repo_id     = "seatflow-staging"
  workload_identity_pool_id     = "seatflow-staging-pool"
  workload_identity_provider_id = "seatflow-staging-provider"
}
