param(
    [Parameter(Mandatory = $true)]
    [string]$RunId,
    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$StartUtc,
    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$EndUtc,
    [string]$ProjectId = "omc-payment",
    [string]$Zone = "asia-northeast3-a",
    [string]$TestVm = "omc-payment-test-vm",
    [int]$StepSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resultDir = Join-Path $repoRoot "load-test\results\$RunId"
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

$startEpoch = $StartUtc.ToUnixTimeSeconds()
$endEpoch = $EndUtc.ToUnixTimeSeconds()

$queries = [ordered]@{
    app_up_by_node = 'max by(node) (up{job="payment-service"})'
    app_node_cpu_pct_by_node = '100 * (1 - avg by(node) (rate(node_cpu_seconds_total{job="node",role="app",mode="idle"}[30s])))'
    infra_node_cpu_pct = '100 * (1 - avg(rate(node_cpu_seconds_total{job="node",role="infra",mode="idle"}[30s])))'
    test_node_cpu_pct = '100 * (1 - avg(rate(node_cpu_seconds_total{job="node",role="test",mode="idle"}[30s])))'
    app_process_cpu_pct_by_node = '100 * process_cpu_usage{job="payment-service"}'
    jvm_compilation_ms_per_second_by_node = 'sum by(node) (rate(jvm_compilation_time_ms_total{job="payment-service"}[30s]))'
    # Listener container indices are assigned independently in each JVM, so a
    # hard-coded #N selector can silently omit one instance after scale-out.
    # These isolated load tests publish only order.created; summing all listener
    # containers therefore captures the active order flow on every node.
    kafka_listener_rps_by_node = 'sum by(node) (rate(spring_kafka_listener_seconds_count{job="payment-service"}[30s]))'
    kafka_listener_total_by_node = 'sum by(node) (spring_kafka_listener_seconds_count{job="payment-service"})'
    kafka_listener_avg_ms_by_node = '1000 * sum by(node) (rate(spring_kafka_listener_seconds_sum{job="payment-service"}[30s])) / sum by(node) (rate(spring_kafka_listener_seconds_count{job="payment-service"}[30s]))'
    hikari_active_by_node = 'max by(node) (hikaricp_connections_active{job="payment-service"})'
    hikari_pending_by_node = 'max by(node) (hikaricp_connections_pending{job="payment-service"})'
    jvm_heap_used_mib_by_node = 'sum by(node) (jvm_memory_used_bytes{job="payment-service",area="heap"}) / 1024 / 1024'
    jvm_gc_pause_max_ms_by_node = '1000 * max by(node) (jvm_gc_pause_seconds_max{job="payment-service"})'
    tomcat_busy_threads_by_node = 'max by(node) (tomcat_threads_busy_threads{job="payment-service"})'
    k6_event_driver_published_total = 'max(k6_event_driver_publish_success_total{scenario="order_created_events"})'
    k6_event_driver_accepted_rate = 'max(k6_event_driver_publish_accepted_rate{scenario="order_created_events"})'
    k6_event_driver_publish_p95_ms = '1000 * max(k6_event_driver_publish_duration_p95{scenario="order_created_events"})'
    k6_http_failed_rate = 'max(k6_http_req_failed_rate{scenario="order_created_events"})'
}

$rows = [System.Collections.Generic.List[object]]::new()

foreach ($entry in $queries.GetEnumerator()) {
    $encodedQuery = [Uri]::EscapeDataString($entry.Value)
    $url = "http://localhost:19090/api/v1/query_range?query=$encodedQuery&start=$startEpoch&end=$endEpoch&step=$StepSeconds"
    $remoteCommand = "curl -s '$url'"
    $responseLines = & gcloud.cmd compute ssh $TestVm `
        --project=$ProjectId `
        --zone=$Zone `
        --tunnel-through-iap `
        --ssh-flag=-P `
        --ssh-flag=22 `
        --quiet `
        --command=$remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Prometheus query failed: $($entry.Key)"
    }

    $response = ([string]::Join("`n", $responseLines) | ConvertFrom-Json)
    if ($response.status -ne "success") {
        throw "Prometheus returned a failure: $($entry.Key)"
    }

    foreach ($series in $response.data.result) {
        $nodeProperty = $series.metric.PSObject.Properties["node"]
        $seriesName = if ($null -ne $nodeProperty) { [string]$nodeProperty.Value } else { "aggregate" }
        foreach ($sample in $series.values) {
            $rows.Add([pscustomobject]@{
                run_id = $RunId
                metric = $entry.Key
                series = $seriesName
                timestamp_utc = [DateTimeOffset]::FromUnixTimeMilliseconds([long]([double]$sample[0] * 1000)).ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
                value = [double]$sample[1]
            })
        }
    }
}

$outputPath = Join-Path $resultDir "dashboard-timeseries.csv"
$rows | Sort-Object metric, series, timestamp_utc | Export-Csv -LiteralPath $outputPath -NoTypeInformation -Encoding utf8
Write-Output $outputPath
