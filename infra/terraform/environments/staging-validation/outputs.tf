output "vm_name" {
  description = "Compute Engine VM instance name"
  value       = module.seatflow_vm.vm_name
}

output "vm_zone" {
  description = "Compute Engine VM zone"
  value       = module.seatflow_vm.vm_zone
}

output "static_ip" {
  description = "Static external IPv4 address"
  value       = module.seatflow_vm.static_ip
}

output "artifact_registry_repository" {
  description = "Artifact Registry repository name"
  value       = module.seatflow_vm.artifact_registry_repository
}

output "artifact_registry_repository_id" {
  description = "Repository ID of the Artifact Registry repository"
  value       = module.seatflow_vm.artifact_registry_repository_id
}

output "runtime_service_account_email" {
  description = "Runtime service account email"
  value       = module.seatflow_vm.runtime_service_account_email
}

output "deploy_service_account_email" {
  description = "Deploy service account email"
  value       = module.seatflow_vm.deploy_service_account_email
}

output "wif_provider_name" {
  description = "Workload Identity Provider name"
  value       = module.seatflow_vm.wif_provider_name
}

output "wif_pool_name" {
  description = "Workload Identity Pool name"
  value       = module.seatflow_vm.wif_pool_name
}

output "vpc_network_name" {
  description = "Name of the custom VPC network"
  value       = module.seatflow_vm.vpc_network_name
}

output "vpc_subnet_name" {
  description = "Name of the custom VPC subnet"
  value       = module.seatflow_vm.vpc_subnet_name
}
