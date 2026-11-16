# Clean re-measurement for the blog update: 8 strategies x 2 situations x 3 rounds,
# plus a slot sweep to prove the stock-shard effect. Writes a manifest of session ids.
# ASCII-only on purpose (no BOM needed).
param(
    [int]$Repeat = 3
)

# Keep Continue: docker/compose write progress to stderr, which under 'Stop' would
# wrap into a terminating NativeCommandError. run-all.ps1 checks $LASTEXITCODE itself.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$runAll = Join-Path $PSScriptRoot 'run-all.ps1'
$capture = Join-Path $PSScriptRoot 'capture.ps1'
$slotSweep = Join-Path $PSScriptRoot 'slot-sweep.ps1'
$resultsDir = Join-Path $root 'results'

function Latest-Session {
    (Get-ChildItem (Join-Path $resultsDir 'comparison-*.md') |
        Sort-Object LastWriteTime | Select-Object -Last 1).BaseName -replace '^comparison-', ''
}

Write-Host '### campaign 1/3 : sold-out (burst, quantity 100)'
& $runAll -Scenario burst -Repeat $Repeat -NoBuild -NoCapture
$burst = Latest-Session
Write-Host "burst session = $burst"
& $capture -RunId "$burst-r1"

Write-Host '### campaign 2/3 : ample stock (burst, quantity 3000)'
& $runAll -Scenario burst -Quantity 3000 -Repeat $Repeat -NoBuild -NoCapture
$ample = Latest-Session
Write-Host "ample session = $ample"
& $capture -RunId "$ample-r1"

Write-Host '### campaign 3/3 : slot sweep (1,5,10,20,50)'
& $slotSweep -Slots 1, 5, 10, 20, 50 -Repeat $Repeat
$slot = (Get-ChildItem (Join-Path $resultsDir 'slot-sweep-*.md') |
    Sort-Object LastWriteTime | Select-Object -Last 1).BaseName -replace '^slot-sweep-', ''
Write-Host "slot-sweep session = $slot"

$manifest = [ordered]@{ burst = $burst; ample = $ample; slotSweep = $slot }
$manifest | ConvertTo-Json | Out-File (Join-Path $resultsDir 'remeasure-manifest.json') -Encoding utf8
Write-Host '### DONE'
$manifest | ConvertTo-Json
