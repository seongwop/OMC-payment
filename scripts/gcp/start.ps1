param(
    [string]$ProjectId = "omc-payment",
    [string]$Zone = "asia-northeast3-a",
    [switch]$ScaleOut
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param([string[]]$Arguments)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & gcloud @Arguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "gcloud failed with exit code $exitCode"
    }
}

Invoke-Checked @(
    "compute", "instances", "start", "omc-payment-infra-vm",
    "--project", $ProjectId,
    "--zone", $Zone,
    "--quiet"
)

for ($attempt = 1; $attempt -le 60; $attempt++) {
    & gcloud compute ssh omc-payment-infra-vm `
        --project $ProjectId `
        --zone $Zone `
        --tunnel-through-iap `
        --ssh-flag=-P `
        --ssh-flag=22 `
        --quiet `
        --command "sudo docker inspect --format '{{.State.Health.Status}}' omc-kafka 2>/dev/null | grep -q healthy" *> $null
    if ($LASTEXITCODE -eq 0) {
        break
    }
    if ($attempt -eq 60) {
        throw "Infra services did not become ready"
    }
    Start-Sleep -Seconds 10
}

$appVmNames = @("omc-payment-app-vm")
if ($ScaleOut) {
    $appVmNames += "omc-payment-app-2-vm"
}

$applicationStartArguments = @(
    "compute", "instances", "start"
)
$applicationStartArguments += $appVmNames
$applicationStartArguments += @(
    "omc-payment-test-vm",
    "--project", $ProjectId,
    "--zone", $Zone,
    "--quiet"
)
Invoke-Checked $applicationStartArguments

Invoke-Checked @(
    "compute", "instances", "list",
    "--project", $ProjectId,
    "--filter", "name~'omc-payment-.*-vm'",
    "--format", "table(name,status,networkInterfaces[0].networkIP)"
)
