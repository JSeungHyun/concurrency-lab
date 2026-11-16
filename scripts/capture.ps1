# Saves Grafana panels as PNG for blog posts, using each run's exact time window from InfluxDB.
#   .\scripts\capture.ps1 -RunId 20261025-223550-r1                  # every strategy in that run + comparison charts
#   .\scripts\capture.ps1 -RunId 20261025-223550-r1 -Strategy naive  # one strategy only
#   .\scripts\capture.ps1 -RunId 20261025-223550-r1 -Theme dark -Width 1600
# Output: results/screenshots/<RunId>/
param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$Strategy = '',
    [ValidateSet('light', 'dark')][string]$Theme = 'light',
    [int]$Width = 1200,
    [int]$Height = 500,
    [string]$GrafanaUrl = 'http://localhost:3000',
    [string]$InfluxUrl = 'http://localhost:8086'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path (Join-Path $root 'results\screenshots') $RunId
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Panels drawn per strategy (time series of one run) and once per run (cross-strategy comparison).
$perStrategyPanels = @{ 1 = 'rps'; 2 = 'latency'; 3 = 'status' }
$comparisonPanels = @{ 6 = 'compare-latency'; 7 = 'compare-issued' }

function Invoke-Influx([string]$query) {
    $uri = "$InfluxUrl/query?db=k6&epoch=ms&q=$([uri]::EscapeDataString($query))"
    return (Invoke-RestMethod -Uri $uri).results[0].series
}

function Get-TimeWindow([string]$where) {
    $first = Invoke-Influx "SELECT first(""value"") FROM ""http_reqs"" WHERE $where"
    $last = Invoke-Influx "SELECT last(""value"") FROM ""http_reqs"" WHERE $where"
    if (-not $first) { return $null }
    # pad so the first and last bars are not clipped at the chart edges
    return @{ From = [long]$first[0].values[0][0] - 2000; To = [long]$last[0].values[0][0] + 2000 }
}

function Save-Panel([int]$panelId, [string]$strategyVar, $window, [string]$file) {
    $query = @(
        'orgId=1',
        "panelId=$panelId",
        "from=$($window.From)",
        "to=$($window.To)",
        "var-run_id=$([uri]::EscapeDataString($RunId))",
        "var-strategy=$([uri]::EscapeDataString($strategyVar))",
        "width=$Width",
        "height=$Height",
        "theme=$Theme",
        'tz=Asia/Seoul'
    ) -join '&'
    $path = Join-Path $outDir $file
    Invoke-WebRequest -UseBasicParsing -Uri "$GrafanaUrl/render/d-solo/coupon-load/coupon-load?$query" -OutFile $path -TimeoutSec 120
    Write-Host "saved: $path"
}

$strategies = if ($Strategy) {
    @($Strategy)
} else {
    $series = Invoke-Influx "SHOW TAG VALUES FROM ""http_reqs"" WITH KEY = ""strategy"" WHERE ""run_id"" = '$RunId'"
    if (-not $series) { throw "No data in InfluxDB for run '$RunId'" }
    $series[0].values | ForEach-Object { $_[1] }
}

foreach ($s in $strategies) {
    $window = Get-TimeWindow """run_id"" = '$RunId' AND ""strategy"" = '$s' AND ""name"" = 'issue'"
    if (-not $window) { Write-Warning "no samples for $s in $RunId"; continue }
    foreach ($panel in $perStrategyPanels.GetEnumerator()) {
        Save-Panel $panel.Key $s $window "$s-$($panel.Value).png"
    }
}

if (-not $Strategy) {
    $window = Get-TimeWindow """run_id"" = '$RunId'"
    foreach ($panel in $comparisonPanels.GetEnumerator()) {
        Save-Panel $panel.Key '$__all' $window "$($panel.Value).png"
    }
}
