output "artifact_registry_url" {
  description = "Docker registry URL"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}

output "github_actions_service_account_email" {
  description = "Service account impersonated by GitHub Actions"
  value       = google_service_account.github_actions.email
}

output "github_actions_workload_identity_provider" {
  description = "Workload Identity Provider used by GitHub Actions"
  value       = google_iam_workload_identity_pool_provider.github_actions.name
}

output "vm_internal_ips" {
  description = "Fixed private IPs"
  value = {
    for name, vm in google_compute_instance.vm :
    name => vm.network_interface[0].network_ip
  }
}

output "ssh_commands" {
  description = "IAP SSH commands"
  value = {
    for name, vm in google_compute_instance.vm :
    name => "gcloud compute ssh ${vm.name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap --ssh-flag=-P --ssh-flag=22"
  }
}

output "local_tunnel_commands" {
  description = "SSH tunnels for local access"
  value = {
    payment_service = "gcloud compute ssh ${google_compute_instance.vm["app"].name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 8085:localhost:8085"
    test_tools      = "gcloud compute ssh ${google_compute_instance.vm["test"].name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 8090:localhost:8090"
    grafana         = "gcloud compute ssh ${google_compute_instance.vm["test"].name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 13000:localhost:13000"
    prometheus      = "gcloud compute ssh ${google_compute_instance.vm["test"].name} --zone ${var.zone} --project ${var.project_id} --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 19090:localhost:19090"
  }
}
