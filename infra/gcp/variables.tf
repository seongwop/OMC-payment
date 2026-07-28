variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "operator_email" {
  description = "Google account allowed to access VMs through IAP"
  type        = string
}

variable "github_repository" {
  description = "GitHub repository allowed to deploy through Workload Identity Federation"
  type        = string
  default     = "seongwop/OMC-payment"
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "asia-northeast3"
}

variable "zone" {
  description = "Compute Engine zone"
  type        = string
  default     = "asia-northeast3-a"
}

variable "name_prefix" {
  description = "Common resource name prefix"
  type        = string
  default     = "omc-payment"
}

variable "network_cidr" {
  description = "Private subnet CIDR"
  type        = string
  default     = "10.20.0.0/24"
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker repository ID"
  type        = string
  default     = "omc-payment"
}

variable "vm_specs" {
  description = "VM role, size, disk and fixed private IP"
  type = map(object({
    role         = string
    machine_type = string
    boot_disk_gb = number
    internal_ip  = string
  }))

  default = {
    app = {
      role         = "app"
      machine_type = "e2-standard-2"
      boot_disk_gb = 30
      internal_ip  = "10.20.0.10"
    }
    infra = {
      role         = "infra"
      machine_type = "e2-standard-4"
      boot_disk_gb = 30
      internal_ip  = "10.20.0.20"
    }
    test = {
      role         = "test"
      machine_type = "e2-standard-2"
      boot_disk_gb = 50
      internal_ip  = "10.20.0.30"
    }
  }
}

variable "infra_data_disks" {
  description = "Persistent disks attached to the infra VM"
  type = map(object({
    size_gb = number
    type    = string
  }))

  default = {
    postgres-data = {
      size_gb = 40
      type    = "pd-balanced"
    }
    kafka-data = {
      size_gb = 50
      type    = "pd-balanced"
    }
  }
}
