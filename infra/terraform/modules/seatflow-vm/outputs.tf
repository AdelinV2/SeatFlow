output "vm_name" {
  description = "Compute Engine VM instance name"
  value       = google_compute_instance.seatflow_vm.name
}

output "vm_zone" {
  description = "Compute Engine VM zone"
  value       = google_compute_instance.seatflow_vm.zone
}

output "static_ip" {
  description = "Static external IPv4 address assigned to the SeatFlow production VM"
  value       = google_compute_address.seatflow_static_ip.address
}

output "artifact_registry_repository" {
  description = "Full name of the Artifact Registry repository"
  value       = google_artifact_registry_repository.seatflow_repo.name
}

output "artifact_registry_repository_id" {
  description = "Repository ID of the Artifact Registry repository"
  value       = google_artifact_registry_repository.seatflow_repo.repository_id
}

output "runtime_service_account_email" {
  description = "Email of the VM runtime service account"
  value       = google_service_account.vm_runtime.email
}

output "deploy_service_account_email" {
  description = "Email of the GitHub Actions deploy service account"
  value       = google_service_account.github_deploy.email
}

output "wif_provider_name" {
  description = "Full resource name of the Workload Identity Provider for GitHub Actions"
  value       = google_iam_workload_identity_pool_provider.github_provider.name
}

output "wif_pool_name" {
  description = "Full resource name of the Workload Identity Pool"
  value       = google_iam_workload_identity_pool.github_pool.name
}

output "vpc_network_name" {
  description = "Name of the custom VPC network"
  value       = google_compute_network.seatflow_vpc.name
}

output "vpc_subnet_name" {
  description = "Name of the custom VPC subnet"
  value       = google_compute_subnetwork.seatflow_subnet.name
}
