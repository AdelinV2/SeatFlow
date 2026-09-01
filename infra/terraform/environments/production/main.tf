module "seatflow_vm" {
  source = "../../modules/seatflow-vm"

  project_id                    = var.project_id
  region                        = var.region
  zone                          = var.zone
  environment                   = var.environment
  vm_name                       = var.vm_name
  machine_type                  = var.machine_type
  boot_disk_size_gb             = var.boot_disk_size_gb
  boot_disk_type                = var.boot_disk_type
  boot_disk_image               = var.boot_disk_image
  github_repo                   = var.github_repo
  domain_name                   = var.domain_name
  enable_ops_agent              = var.enable_ops_agent
  deletion_protection           = var.deletion_protection
  network_name                  = var.network_name
  subnet_name                   = var.subnet_name
  subnet_cidr                   = var.subnet_cidr
  artifact_registry_repo_id     = var.artifact_registry_repo_id
  workload_identity_pool_id     = var.workload_identity_pool_id
  workload_identity_provider_id = var.workload_identity_provider_id
}
