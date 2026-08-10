param(
    [Parameter(Mandatory = $true)]
    [string]$RunId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resultDir = Join-Path $repoRoot "load-test\results\$RunId"
$dashboardPath = Join-Path $resultDir "dashboard-timeseries.csv"
$rows = Import-Csv -LiteralPath $dashboardPath

$publishedRows = @($rows | Where-Object metric -eq "k6_event_driver_published_total" | Sort-Object timestamp_utc)
$resetIndex = -1
for ($index = 1; $index -lt $publishedRows.Count; $index++) {
    if ([double]$publishedRows[$index].value -lt [double]$publishedRows[$index - 1].value) {
        $resetIndex = $index
        break
    }
}
if ($resetIndex -lt 0) {
    throw "Could not find the k6 counter reset that marks this run."
}

$runStartTimestamp = $publishedRows[$resetIndex].timestamp_utc
$baselineTimestamp = $publishedRows[$resetIndex - 2].timestamp_utc
$listenerRows = @($rows | Where-Object metric -eq "kafka_listener_total_by_node" | Sort-Object timestamp_utc, series)
$baselineByNode = @{}
foreach ($row in $listenerRows | Where-Object timestamp_utc -eq $baselineTimestamp) {
    $baselineByNode[$row.series] = [double]$row.value
}
if ($baselineByNode.Count -ne 2) {
    throw "Expected listener baselines for both app nodes at $baselineTimestamp."
}

$lastListenerByNode = @{}
foreach ($node in $baselineByNode.Keys) {
    $lastListenerByNode[$node] = $baselineByNode[$node]
}

$pipelineRows = [System.Collections.Generic.List[object]]::new()
$previousConsumed = 0.0
$previousTimestamp = $null

foreach ($publishedRow in $publishedRows | Where-Object timestamp_utc -ge $runStartTimestamp) {
    $timestamp = $publishedRow.timestamp_utc
    foreach ($listenerRow in $listenerRows | Where-Object timestamp_utc -eq $timestamp) {
        $lastListenerByNode[$listenerRow.series] = [double]$listenerRow.value
    }

    $consumed = 0.0
    foreach ($node in $baselineByNode.Keys) {
        $consumed += $lastListenerByNode[$node] - $baselineByNode[$node]
    }
    $consumed = [Math]::Max(0, $consumed)
    $produced = [double]$publishedRow.value
    $backlog = [Math]::Max(0, $produced - $consumed)

    $upRows = @($rows | Where-Object {
        $_.metric -eq "app_up_by_node" -and $_.timestamp_utc -eq $timestamp
    })
    $activeNodes = @($upRows | Where-Object { [double]$_.value -ge 1 }).Count

    $throughput = 0.0
    if ($null -ne $previousTimestamp) {
        $elapsed = ([DateTimeOffset]::Parse($timestamp) - [DateTimeOffset]::Parse($previousTimestamp)).TotalSeconds
        if ($elapsed -gt 0) {
            $throughput = ($consumed - $previousConsumed) / $elapsed
        }
    }

    $pipelineRows.Add([pscustomobject]@{
        run_id = $RunId
        timestamp_utc = $timestamp
        produced_total = [long]$produced
        consumed_total_from_listener = [long]$consumed
        listener_backlog_estimate = [long]$backlog
        consumer_throughput_eps = [Math]::Round($throughput, 3)
        active_app_nodes = $activeNodes
        app_1_listener_total = [long]$lastListenerByNode["app-1"]
        app_2_listener_total = [long]$lastListenerByNode["app-2"]
    })

    $previousConsumed = $consumed
    $previousTimestamp = $timestamp
}

$finalRow = $pipelineRows[$pipelineRows.Count - 1]
$stoppedNodeScrapeGap = [Math]::Max(
    0,
    [long]$finalRow.produced_total - [long]$finalRow.consumed_total_from_listener
)
foreach ($row in $pipelineRows) {
    $adjustment = if ([int]$row.active_app_nodes -lt 2) { $stoppedNodeScrapeGap } else { 0 }
    $adjustedConsumed = [Math]::Min(
        [long]$row.produced_total,
        [long]$row.consumed_total_from_listener + $adjustment
    )
    $row | Add-Member -NotePropertyName stopped_node_unscraped_completion_adjustment -NotePropertyValue $adjustment
    $row | Add-Member -NotePropertyName adjusted_consumed_total -NotePropertyValue $adjustedConsumed
    $row | Add-Member -NotePropertyName adjusted_listener_backlog_estimate -NotePropertyValue ([Math]::Max(0, [long]$row.produced_total - $adjustedConsumed))
}

$outputPath = Join-Path $resultDir "pipeline-analysis.csv"
$pipelineRows | Export-Csv -LiteralPath $outputPath -NoTypeInformation -Encoding utf8
Write-Output $outputPath
