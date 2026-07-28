param(
    [string]$ProjectId = "omc-payment",
    [string]$Zone = "asia-northeast3-a"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& gcloud compute instances stop `
    omc-payment-app-vm `
    omc-payment-test-vm `
    --project $ProjectId `
    --zone $Zone `
    --quiet
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop app/test VMs"
}

& gcloud compute instances stop `
    omc-payment-infra-vm `
    --project $ProjectId `
    --zone $Zone `
    --quiet
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop infra VM"
}
