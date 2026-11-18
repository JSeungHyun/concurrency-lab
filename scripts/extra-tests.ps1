# Heavier load tests to see where the strategies separate under overload.
#   T1 big sold-out : 100 coupons / 30000 requests / 1000 VUs   (x3)
#   T2 partial      : 1000 coupons / 10000 requests / 500 VUs   (x3)
#   T3 rate ladder  : constant arrival 500/1000/2000/3000 rps for 20s, 1000 coupons, SLO verdict (x1)
# ASCII-only on purpose (no BOM needed). Writes results/extra-manifest.json.
param(
    [string[]]$Strategies = @('synchronized', 'pessimistic', 'optimistic', 'atomic-update', 'redisson-lock', 'redis-lua', 'stock-shard'),
    [int[]]$Rates = @(500, 1000, 2000, 3000),
    [int]$Repeat = 3
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$runAll = Join-Path $PSScriptRoot 'run-all.ps1'
$resultsDir = Join-Path $root 'results'

function Latest-Session {
    (Get-ChildItem (Join-Path $resultsDir 'comparison-*.md') |
        Sort-Object LastWriteTime | Select-Object -Last 1).BaseName -replace '^comparison-', ''
}

$manifest = [ordered]@{}

Write-Host '### T1 big sold-out'
& $runAll -Scenario burst -Strategies $Strategies -Quantity 100 -Requests 30000 -Vus 1000 -Repeat $Repeat -NoBuild -NoCapture
$manifest.bigSoldOut = Latest-Session

Write-Host '### T2 partial sold-out'
& $runAll -Scenario burst -Strategies $Strategies -Quantity 1000 -Requests 10000 -Vus 500 -Repeat $Repeat -NoBuild -NoCapture
$manifest.partial = Latest-Session

$manifest.rates = [ordered]@{}
foreach ($rate in $Rates) {
    Write-Host "### T3 rate $rate"
    & $runAll -Scenario load -Strategies $Strategies -Quantity 1000 -Env @{ RATE = $rate; DURATION = '20s'; VUS = 500; MAX_VUS = 3000 } -NoBuild -NoCapture
    $manifest.rates["$rate"] = Latest-Session
    Start-Sleep 1
}

$manifest | ConvertTo-Json -Depth 4 | Out-File (Join-Path $resultsDir 'extra-manifest.json') -Encoding utf8
Write-Host '### DONE'
$manifest | ConvertTo-Json -Depth 4
