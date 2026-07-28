# OMC Payment GCP deployment

This deployment keeps the first benchmark topology intentionally small:

- `omc-payment-app-vm`: payment-service, `e2-standard-2`
- `omc-payment-infra-vm`: PostgreSQL, Redis, Kafka, ZooKeeper, WireMock, `e2-standard-4`
- `omc-payment-test-vm`: k6, payment-test-tools, Prometheus, Grafana, `e2-standard-2`

All VMs have fixed private IPs and no external IP. Access is through IAP SSH.
The three VMs consume the current Seoul-region E2 quota of 8 vCPUs.
PostgreSQL uses its own persistent disk. Kafka logs and ZooKeeper state share a
second persistent disk so their cluster metadata survives an infra VM rebuild.

## Deploy

From the repository root:

```powershell
.\scripts\gcp\deploy.ps1
```

The script applies Terraform, creates missing Secret Manager versions, builds
the two application images with Cloud Build, uploads the role-specific Compose
files, and checks each role's health.

The verifier is disabled during load generation. To deploy it enabled:

```powershell
.\scripts\gcp\deploy.ps1 -SkipTerraform -SkipBuild -EnableVerifier -ImageTag IMAGE_TAG
```

## GitHub Actions CI/CD

Pull requests targeting `main` run `.github/workflows/ci.yml`:

- Gradle unit and Testcontainers integration tests
- Spring Boot JAR and both Docker image builds
- Docker Compose, shell script and Terraform validation

Every push to `main` runs `.github/workflows/gcp-cd.yml`:

- tests and builds both application images
- pushes the immutable commit SHA and `latest` tags to Artifact Registry
- authenticates without a service-account key through Workload Identity Federation
- deploys infra, app and test roles through IAP SSH
- waits for each role's health check before completing

The CD workflow deliberately does not run k6. A deployed commit can be benchmarked
separately so the test window and image SHA remain explicit.

## Run the default load test

The default remote command runs 300 RPS for 90 seconds with 20% PG timeout:

```powershell
gcloud compute ssh omc-payment-test-vm `
  --project omc-payment `
  --zone asia-northeast3-a `
  --tunnel-through-iap `
  --ssh-flag=-P `
  --ssh-flag=22 `
  --command "sudo RATE=300 TIMEOUT_WEIGHT=20 /opt/omc-payment/scripts/run-load-test.sh"
```

## Local tunnels

Payment service:

```powershell
gcloud compute ssh omc-payment-app-vm --project omc-payment --zone asia-northeast3-a --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 8085:localhost:8085
```

Grafana:

```powershell
gcloud compute ssh omc-payment-test-vm --project omc-payment --zone asia-northeast3-a --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 13000:localhost:13000
```

The Grafana password is stored in Secret Manager as
`omc-payment-grafana-admin-password`.

## Cost control

Stop the VMs after a test:

```powershell
.\scripts\gcp\stop.ps1
```

Start them in dependency order:

```powershell
.\scripts\gcp\start.ps1
```

If ZooKeeper state was lost while an older Kafka disk survived, preserve the
old Kafka directory and align the new cluster with:

```bash
sudo bash /opt/omc-payment/scripts/recover-kafka-after-zookeeper-loss.sh \
  data-pre-zookeeper-recovery-YYYYMMDD
```

The script moves the previous Kafka data to the supplied backup directory
instead of deleting it.

Destroy all Terraform-managed resources when the environment is no longer
needed:

```powershell
$env:TF_VAR_project_id = "omc-payment"
$env:TF_VAR_operator_email = "YOUR_ACCOUNT"
terraform -chdir=infra/gcp destroy
```
