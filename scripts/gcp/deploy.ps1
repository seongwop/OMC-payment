param(
    [string]$ProjectId = "omc-payment",
    [string]$Region = "asia-northeast3",
    [string]$Zone = "asia-northeast3-a",
    [string]$ImageTag = "",
    [switch]$SkipTerraform,
    [switch]$SkipBuild,
    [switch]$EnableVerifier,
    [ValidateSet("infra", "app", "test")]
    [string[]]$Roles = @("infra", "app", "test")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$TerraformDir = Join-Path $RepoRoot "infra\gcp"
$RemoteDir = "/opt/omc-payment"
$RegistryHost = "$Region-docker.pkg.dev"
$Registry = "$RegistryHost/$ProjectId/omc-payment"

function Invoke-Checked {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Command @Arguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "$Command failed with exit code $exitCode"
    }
}

function Get-CommandOutput {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Command @Arguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "$Command failed with exit code $exitCode"
    }
    if ($null -eq $output) {
        return ""
    }
    return ([string]::Join("`n", $output)).Trim()
}

function Ensure-SecretVersion {
    param([string]$SecretId)

    $version = Get-CommandOutput "gcloud" @(
        "secrets", "versions", "list", $SecretId,
        "--project", $ProjectId,
        "--limit", "1",
        "--format", "value(name)"
    )

    if (-not [string]::IsNullOrWhiteSpace($version)) {
        return
    }

    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    }
    finally {
        $random.Dispose()
    }
    $secretValue = [Convert]::ToBase64String($bytes).
        TrimEnd("=").
        Replace("+", "A").
        Replace("/", "B")

    $secretFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText(
            $secretFile,
            $secretValue,
            [Text.UTF8Encoding]::new($false)
        )
        Invoke-Checked "gcloud" @(
            "secrets", "versions", "add", $SecretId,
            "--project", $ProjectId,
            "--data-file", $secretFile,
            "--quiet"
        )
    }
    finally {
        Remove-Item -LiteralPath $secretFile -Force
    }
}

function Get-SecretValue {
    param([string]$SecretId)

    return Get-CommandOutput "gcloud" @(
        "secrets", "versions", "access", "latest",
        "--secret", $SecretId,
        "--project", $ProjectId
    )
}

function Wait-ForDocker {
    param([string]$VmName)

    Write-Host "Waiting for Docker startup on $VmName..."
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & gcloud compute ssh $VmName `
                --project $ProjectId `
                --zone $Zone `
                --tunnel-through-iap `
                --ssh-flag=-P `
                --ssh-flag=22 `
                --quiet `
                --command "sudo systemctl is-active docker >/dev/null && sudo systemctl cat omc-payment-compose.service >/dev/null" *> $null
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }

        if ($exitCode -eq 0) {
            return
        }
        Start-Sleep -Seconds 10
    }

    throw "Docker startup did not complete on $VmName"
}

function Copy-TextFileAsLf {
    param(
        [string]$Source,
        [string]$Destination
    )

    $content = [IO.File]::ReadAllText($Source).Replace("`r`n", "`n")
    [IO.File]::WriteAllText(
        $Destination,
        $content,
        [Text.UTF8Encoding]::new($false)
    )
}

function New-RoleArchive {
    param(
        [string]$Role,
        [string]$TempRoot,
        [string[]]$EnvLines
    )

    $roleDir = Join-Path $TempRoot $Role
    New-Item -ItemType Directory -Path $roleDir -Force | Out-Null

    Copy-Item `
        -LiteralPath (Join-Path $RepoRoot "deploy\gcp\docker-compose.$Role.yml") `
        -Destination (Join-Path $roleDir "docker-compose.$Role.yml")

    [IO.File]::WriteAllLines(
        (Join-Path $roleDir ".env.gcp"),
        $EnvLines,
        [Text.UTF8Encoding]::new($false)
    )

    New-Item -ItemType Directory -Path (Join-Path $roleDir "scripts") -Force | Out-Null
    foreach ($scriptName in @("deploy-role.sh", "wait-role-health.sh")) {
        Copy-TextFileAsLf `
            -Source (Join-Path $RepoRoot "deploy\gcp\scripts\$scriptName") `
            -Destination (Join-Path $roleDir "scripts\$scriptName")
    }

    switch ($Role) {
        "infra" {
            Copy-TextFileAsLf `
                -Source (Join-Path $RepoRoot "deploy\gcp\scripts\recover-kafka-after-zookeeper-loss.sh") `
                -Destination (Join-Path $roleDir "scripts\recover-kafka-after-zookeeper-loss.sh")
            New-Item -ItemType Directory -Path (Join-Path $roleDir "assets") -Force | Out-Null
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "infra\local\01_create_payment_schema.sql") `
                -Destination (Join-Path $roleDir "assets\01_create_payment_schema.sql")
            New-Item -ItemType Directory -Path (Join-Path $roleDir "simulators\toss-pg") -Force | Out-Null
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "simulators\toss-pg\mappings") `
                -Destination (Join-Path $roleDir "simulators\toss-pg\mappings") `
                -Recurse
            New-Item -ItemType Directory -Path (Join-Path $roleDir "data\redis") -Force | Out-Null
        }
        "test" {
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "deploy\gcp\prometheus.yml") `
                -Destination (Join-Path $roleDir "prometheus.yml")
            New-Item -ItemType Directory -Path (Join-Path $roleDir "grafana") -Force | Out-Null
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "deploy\gcp\grafana\provisioning") `
                -Destination (Join-Path $roleDir "grafana\provisioning") `
                -Recurse
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "observability\grafana\dashboards") `
                -Destination (Join-Path $roleDir "grafana\dashboards") `
                -Recurse
            New-Item -ItemType Directory -Path (Join-Path $roleDir "load-test") -Force | Out-Null
            Copy-Item `
                -LiteralPath (Join-Path $RepoRoot "load-test\k6") `
                -Destination (Join-Path $roleDir "load-test\k6") `
                -Recurse
            New-Item -ItemType Directory -Path (Join-Path $roleDir "load-test\results") -Force | Out-Null
            Copy-TextFileAsLf `
                -Source (Join-Path $RepoRoot "deploy\gcp\scripts\run-load-test.sh") `
                -Destination (Join-Path $roleDir "scripts\run-load-test.sh")
        }
    }

    $archive = Join-Path $TempRoot "omc-payment-$Role.tar.gz"
    Invoke-Checked "tar" @("-czf", $archive, "-C", $roleDir, ".")
    return $archive
}

function Deploy-Role {
    param(
        [string]$Role,
        [string]$Archive
    )

    $vmName = "omc-payment-$Role-vm"
    $remoteArchive = "/tmp/omc-payment-$Role.tar.gz"
    Write-Host "Deploying $Role to $vmName..."

    Invoke-Checked "gcloud" @(
        "compute", "scp", $Archive, "${vmName}:$remoteArchive",
        "--project", $ProjectId,
        "--zone", $Zone,
        "--tunnel-through-iap",
        "--port", "22",
        "--quiet"
    )

    $remoteCommand = "sudo mkdir -p $RemoteDir; sudo tar -xzf $remoteArchive -C $RemoteDir --no-same-owner; sudo rm -f $remoteArchive; sudo chmod +x $RemoteDir/scripts/*.sh; sudo bash $RemoteDir/scripts/deploy-role.sh $Role $RegistryHost"

    Invoke-Checked "gcloud" @(
        "compute", "ssh", $vmName,
        "--project", $ProjectId,
        "--zone", $Zone,
        "--tunnel-through-iap",
        "--ssh-flag=-P",
        "--ssh-flag=22",
        "--quiet",
        "--command", $remoteCommand
    )
}

function Wait-ForRoleHealth {
    param([string]$Role)

    $vmName = "omc-payment-$Role-vm"
    $healthCommand = "sudo timeout 610 bash $RemoteDir/scripts/wait-role-health.sh $Role"

    Write-Host "Waiting for $Role health..."
    Invoke-Checked "gcloud" @(
        "compute", "ssh", $vmName,
        "--project", $ProjectId,
        "--zone", $Zone,
        "--tunnel-through-iap",
        "--ssh-flag=-P",
        "--ssh-flag=22",
        "--quiet",
        "--command", $healthCommand
    )
}

foreach ($requiredCommand in @("gcloud", "terraform", "tar", "git")) {
    if (-not (Get-Command $requiredCommand -ErrorAction SilentlyContinue)) {
        throw "$requiredCommand command was not found"
    }
}

$operatorEmail = Get-CommandOutput "gcloud" @(
    "auth", "list",
    "--filter", "status:ACTIVE",
    "--format", "value(account)"
)
if ([string]::IsNullOrWhiteSpace($operatorEmail)) {
    throw "No active gcloud account was found"
}

if ([string]::IsNullOrWhiteSpace($ImageTag)) {
    $gitSha = Get-CommandOutput "git" @("-C", $RepoRoot, "rev-parse", "--short=8", "HEAD")
    $ImageTag = "$(Get-Date -Format 'yyyyMMdd-HHmm')-$gitSha"
}

if (-not $SkipTerraform) {
    Push-Location $TerraformDir
    try {
        $env:TF_VAR_project_id = $ProjectId
        $env:TF_VAR_operator_email = $operatorEmail
        $env:TF_VAR_region = $Region
        $env:TF_VAR_zone = $Zone

        Invoke-Checked "terraform" @("init", "-upgrade")
        Invoke-Checked "terraform" @("apply", "-auto-approve")
    }
    finally {
        Remove-Item Env:TF_VAR_project_id -ErrorAction SilentlyContinue
        Remove-Item Env:TF_VAR_operator_email -ErrorAction SilentlyContinue
        Remove-Item Env:TF_VAR_region -ErrorAction SilentlyContinue
        Remove-Item Env:TF_VAR_zone -ErrorAction SilentlyContinue
        Pop-Location
    }
}

Ensure-SecretVersion "omc-payment-db-password"
Ensure-SecretVersion "omc-payment-grafana-admin-password"

if (-not $SkipBuild) {
    Push-Location $RepoRoot
    try {
        Invoke-Checked "gcloud" @(
            "builds", "submit", ".",
            "--project", $ProjectId,
            "--config", "cloudbuild.yaml",
            "--substitutions", "_REGION=$Region,_REPOSITORY=omc-payment,_IMAGE_TAG=$ImageTag",
            "--quiet"
        )
    }
    finally {
        Pop-Location
    }
}

$dbPassword = Get-SecretValue "omc-payment-db-password"
$grafanaPassword = Get-SecretValue "omc-payment-grafana-admin-password"
$verifierEnabled = if ($EnableVerifier) { "true" } else { "false" }

$envLines = @(
    "IMAGE_REGISTRY=$Registry",
    "IMAGE_TAG=$ImageTag",
    "APP_INTERNAL_IP=10.20.0.10",
    "INFRA_INTERNAL_IP=10.20.0.20",
    "TEST_INTERNAL_IP=10.20.0.30",
    "DB_USERNAME=omc",
    "DB_PASSWORD=$dbPassword",
    "GRAFANA_ADMIN_PASSWORD=$grafanaPassword",
    "TOSS_SECRET_KEY=test-secret-key",
    "GATEWAY_SECRET=local-secret",
    "KAFKA_TOPIC_DEFAULT_PARTITIONS=3",
    "KAFKA_LISTENER_CONCURRENCY=3",
    "TOSS_READ_TIMEOUT_MS=3000",
    "TOSS_MAX_CONNECTIONS=170",
    "TOSS_MAX_CONNECTIONS_PER_ROUTE=170",
    "TOSS_BULKHEAD_MAX_CONCURRENT_CALLS=160",
    "PAYMENT_DATASOURCE_MAX_POOL_SIZE=30",
    "PAYMENT_UNKNOWN_RECOVERY_INITIAL_DELAY_MS=60000",
    "PAYMENT_UNKNOWN_RECOVERY_FIXED_DELAY_MS=30000",
    "TEST_TOOLS_VERIFIER_ENABLED=$verifierEnabled",
    "TEST_TOOLS_VERIFICATION_TIMEOUT=15s"
)

foreach ($role in $Roles) {
    Wait-ForDocker "omc-payment-$role-vm"
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("omc-payment-deploy-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    foreach ($role in $Roles) {
        $archive = New-RoleArchive -Role $role -TempRoot $tempRoot -EnvLines $envLines
        Deploy-Role -Role $role -Archive $archive
        Wait-ForRoleHealth -Role $role
    }
}
finally {
    $resolvedTemp = (Resolve-Path $tempRoot -ErrorAction SilentlyContinue).Path
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedTemp -and $resolvedTemp.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}

Write-Host ""
Write-Host "Deployment completed"
Write-Host "Image tag: $ImageTag"
Write-Host "Payment service tunnel:"
Write-Host "  gcloud compute ssh omc-payment-app-vm --project $ProjectId --zone $Zone --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 8085:localhost:8085"
Write-Host "Grafana tunnel:"
Write-Host "  gcloud compute ssh omc-payment-test-vm --project $ProjectId --zone $Zone --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 13000:localhost:13000"
Write-Host "Run the default 300 RPS / 20% timeout test:"
Write-Host "  gcloud compute ssh omc-payment-test-vm --project $ProjectId --zone $Zone --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 --command 'sudo RATE=300 TIMEOUT_WEIGHT=20 /opt/omc-payment/scripts/run-load-test.sh'"
