# Runs every strategy under identical load and writes a comparison table.
#   .\scripts\run-all.ps1                                     # burst, all strategies
#   .\scripts\run-all.ps1 -Scenario smoke
#   .\scripts\run-all.ps1 -Scenario load -Strategies pessimistic,atomic-update -Env @{ RATE = 500; DURATION = '1m' }
#   .\scripts\run-all.ps1 -Scenario burst -Quantity 3000 -Repeat 3
# Scenario knobs (pass through -Env): see scenarioConfig() in k6/issue.js
#   load/soak : RATE, DURATION, VUS, MAX_VUS
#   stress    : START_RATE, PEAK_RATE, STEPS, STEP_DURATION, VUS, MAX_VUS
#   spike     : BASE_RATE, PEAK_RATE, SPIKE_DURATION, VUS, MAX_VUS
param(
    [ValidateSet('smoke', 'burst', 'load', 'stress', 'spike', 'soak')]
    [string]$Scenario = 'burst',
    [string[]]$Strategies = @('naive', 'synchronized', 'pessimistic', 'optimistic', 'atomic-update', 'redisson-lock', 'redis-lua', 'stock-shard'),
    [int]$Quantity,
    [int]$Requests,
    [int]$Vus,
    [hashtable]$Env = @{},
    [int]$Repeat = 1,
    [switch]$NoBuild,
    [switch]$NoCapture
)

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if ($NoBuild) { docker compose up -d } else { docker compose up -d --build }
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

    # Only pass what was given explicitly, so each scenario keeps its own defaults.
    $envArgs = @('-e', "SCENARIO=$Scenario")
    if ($PSBoundParameters.ContainsKey('Quantity')) { $envArgs += @('-e', "QUANTITY=$Quantity") }
    if ($PSBoundParameters.ContainsKey('Requests')) { $envArgs += @('-e', "REQUESTS=$Requests") }
    if ($PSBoundParameters.ContainsKey('Vus')) { $envArgs += @('-e', "VUS=$Vus") }
    foreach ($key in $Env.Keys) { $envArgs += @('-e', "$key=$($Env[$key])") }

    # Warm up the JVM (JIT, connection pools) so the first strategy is not penalised.
    Write-Host '== warmup (not recorded)'
    docker compose run --rm -e SCENARIO=burst -e STRATEGY=atomic-update -e QUANTITY=500 -e REQUESTS=1000 -e VUS=50 -e WARMUP=1 k6 run --quiet /scripts/issue.js

    $session = Get-Date -Format 'yyyyMMdd-HHmmss'
    for ($r = 1; $r -le $Repeat; $r++) {
        foreach ($s in $Strategies) {
            $runId = "$session-r$r"
            Write-Host "== $Scenario / $s (round $r/$Repeat)"
            docker compose run --rm @envArgs -e STRATEGY=$s -e RUN_ID=$runId k6 run --quiet /scripts/issue.js
            # k6 exits 99 when an SLO threshold fails; the report records it, so keep going.
            if ($LASTEXITCODE -ne 0) { Write-Warning "k6 exited with $LASTEXITCODE for $s" }
        }
    }

    & (Join-Path $PSScriptRoot 'compare.ps1') -Session $session

    if (-not $NoCapture) {
        for ($r = 1; $r -le $Repeat; $r++) {
            & (Join-Path $PSScriptRoot 'capture.ps1') -RunId "$session-r$r"
        }
    }
}
finally {
    Pop-Location
}
