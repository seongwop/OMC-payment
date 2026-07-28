locals {
  runtime_secret_ids = toset([
    "${var.name_prefix}-db-password",
    "${var.name_prefix}-grafana-admin-password",
  ])
}

resource "google_secret_manager_secret" "runtime" {
  for_each = local.runtime_secret_ids

  secret_id = each.value

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}
