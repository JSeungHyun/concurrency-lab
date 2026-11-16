# Builds a markdown comparison table from results/*.json.
#   .\scripts\compare.ps1                         # every recorded run
#   .\scripts\compare.ps1 -Session 20261025-1530  # runs from one run-all session
param(
    [string]$Session = ''
)

function Get-Median([double[]]$values) {
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    if ($n % 2) { return $sorted[[int][math]::Floor($n / 2)] }
    return [math]::Round(($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2, 1)
}

$root = Split-Path -Parent $PSScriptRoot
$resultsDir = Join-Path $root 'results'
$order = @('naive', 'synchronized', 'pessimistic', 'optimistic', 'atomic-update', 'redisson-lock', 'redis-lua', 'stock-shard')

$reports = Get-ChildItem -Path $resultsDir -Filter "$Session*.json" -File |
    Where-Object { $_.Name -notlike 'comparison*' } |
    ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } |
    Sort-Object @{ Expression = { $_.scenario } }, @{ Expression = { [array]::IndexOf($order, $_.strategy) } }, runId

if (-not $reports) {
    Write-Warning "No results found in $resultsDir for session '$Session'"
    return
}

$lines = @("# Comparison $Session", '')
foreach ($group in ($reports | Group-Object { if ($_.scenario) { $_.scenario } else { 'burst' } })) {
    $c = $group.Group[0].conditions
    $lines += @(
        "## $($group.Name)",
        '',
        "quantity $($c.quantity) / config $($c.config | ConvertTo-Json -Compress -Depth 5)",
        '',
        '| strategy | run | integrity | SLO | issued rows | over-issued | lost updates | under-issued | 503 | dropped | RPS | err % | p50 ms | p95 ms | p99 ms | retries |',
        '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'
    )
    foreach ($r in $group.Group) {
        $i = $r.integrity
        $p = $r.performance
        $verdict = if ($i.passed) { 'PASS' } else { 'FAIL' }
        $slo = if ($null -eq $r.slo) { '-' } elseif ($r.slo.passed) { 'PASS' } else { 'FAIL' }
        $errPct = if ($null -ne $p.errorRate) { [math]::Round($p.errorRate * 100, 2) } else { '-' }
        $retries = if ($r.strategy -eq 'optimistic') { $r.optimisticRetries } else { '-' }
        $lines += "| $($r.strategy) | $($r.runId) | $verdict | $slo | $($i.issuedRows)/$($i.totalQuantity) | $($i.overIssued) | $($i.lostUpdates) | $($i.underIssued) | $($r.outcomes.unavailable) | $($r.outcomes.dropped) | $($p.rps) | $errPct | $($p.latencyMs.p50) | $($p.latencyMs.p95) | $($p.latencyMs.p99) | $retries |"
    }
    $lines += ''

    # With repeated rounds, a single run can mislead (order effects, GC). Rank by the median.
    $byStrategy = $group.Group | Group-Object strategy
    if (($byStrategy | Measure-Object -Property Count -Maximum).Maximum -gt 1) {
        $lines += @(
            "### median over rounds",
            '',
            '| strategy | rounds | integrity passed | RPS (min / median / max) | p95 ms median | p99 ms median |',
            '|---|---|---|---|---|---|'
        )
        $rows = foreach ($s in $byStrategy) {
            $rps = @($s.Group | ForEach-Object { [double]$_.performance.rps })
            [pscustomobject]@{
                Strategy = $s.Name
                Rounds   = $s.Count
                Passed   = @($s.Group | Where-Object { $_.integrity.passed }).Count
                RpsMin   = ($rps | Measure-Object -Minimum).Minimum
                RpsMax   = ($rps | Measure-Object -Maximum).Maximum
                Rps      = Get-Median $rps
                P95      = Get-Median @($s.Group | ForEach-Object { [double]$_.performance.latencyMs.p95 })
                P99      = Get-Median @($s.Group | ForEach-Object { [double]$_.performance.latencyMs.p99 })
            }
        }
        foreach ($m in ($rows | Sort-Object Rps -Descending)) {
            $lines += "| $($m.Strategy) | $($m.Rounds) | $($m.Passed)/$($m.Rounds) | $($m.RpsMin) / **$($m.Rps)** / $($m.RpsMax) | $($m.P95) | $($m.P99) |"
        }
        $lines += ''
    }
}

$name = if ($Session) { "comparison-$Session.md" } else { 'comparison-all.md' }
$out = Join-Path $resultsDir $name
$lines | Out-File -FilePath $out -Encoding utf8
$lines | ForEach-Object { Write-Host $_ }
Write-Host "saved: $out"
