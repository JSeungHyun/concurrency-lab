# 재고 분할 슬롯 수에 따른 처리량 비교. 슬롯 수마다 앱을 새로 띄우고, 같은 절차로 예열한 뒤 잰다.
#   .\scripts\slot-sweep.ps1                         # 슬롯 1, 10, 50 / 재고 부족·충분 / 3회
#   .\scripts\slot-sweep.ps1 -Slots 1,5,10,20,50 -Repeat 3
# 결과: results/slot-sweep-<세션>.md (RUN_ID 는 <세션>-s<슬롯>-<burst|write>-r<회차>)
param(
    [int[]]$Slots = @(1, 10, 50),
    [int]$Repeat = 3,
    [int]$WarmupRuns = 5
)

$root = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $root 'docker-compose.yml'
$session = Get-Date -Format 'yyyyMMdd-HHmmss'
$situations = [ordered]@{ burst = 100; write = 3000 }

function Wait-App {
    for ($i = 0; $i -lt 60; $i++) {
        try { Invoke-RestMethod http://localhost:8080/api/strategies -TimeoutSec 2 | Out-Null; return } catch { Start-Sleep 2 }
    }
    throw 'app did not come up'
}

foreach ($n in $Slots) {
    $env:STOCK_SLOTS = "$n"
    docker compose -f $compose up -d --force-recreate app 2>$null | Out-Null
    Wait-App
    # 실행 하나가 1~2초로 짧아서 JIT 상태가 결과를 좌우한다. 기록 전에 같은 경로를 충분히 데운다.
    for ($w = 1; $w -le $WarmupRuns; $w++) {
        docker compose -f $compose run --rm -e SCENARIO=burst -e STRATEGY=stock-shard -e QUANTITY=1000 -e WARMUP=1 k6 run --quiet /scripts/issue.js 2>$null | Out-Null
    }
    foreach ($situation in $situations.Keys) {
        for ($r = 1; $r -le $Repeat; $r++) {
            $runId = "$session-s$n-$situation-r$r"
            Write-Host "== slots $n / $situation / round $r"
            docker compose -f $compose run --rm -e SCENARIO=burst -e STRATEGY=stock-shard -e QUANTITY=$($situations[$situation]) -e RUN_ID=$runId k6 run --quiet /scripts/issue.js 2>$null | Out-Null
        }
    }
}
Remove-Item Env:STOCK_SLOTS
docker compose -f $compose up -d --force-recreate app 2>$null | Out-Null

function Get-Median([double[]]$values) {
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    if ($n % 2) { return $sorted[[int][math]::Floor($n / 2)] }
    return [math]::Round(($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2, 1)
}

$lines = @("# Slot sweep $session", '', "warmup $WarmupRuns runs per slot count, $Repeat recorded rounds", '',
    '| slots | situation | integrity passed | RPS (min / median / max) | p50 ms median | p95 ms median | p99 ms median |',
    '|---|---|---|---|---|---|---|')
foreach ($n in $Slots) {
    foreach ($situation in $situations.Keys) {
        $runs = Get-ChildItem (Join-Path $root 'results') -Filter "$session-s$n-$situation-r*_*.json" |
            ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json }
        if (-not $runs) { continue }
        $rps = @($runs | ForEach-Object { [double]$_.performance.rps })
        $passed = @($runs | Where-Object { $_.integrity.passed }).Count
        $lines += "| $n | $situation | $passed/$($runs.Count) | $(($rps | Measure-Object -Minimum).Minimum) / **$(Get-Median $rps)** / $(($rps | Measure-Object -Maximum).Maximum) | $(Get-Median @($runs | ForEach-Object { [double]$_.performance.latencyMs.p50 })) | $(Get-Median @($runs | ForEach-Object { [double]$_.performance.latencyMs.p95 })) | $(Get-Median @($runs | ForEach-Object { [double]$_.performance.latencyMs.p99 })) |"
    }
}
$out = Join-Path $root "results\slot-sweep-$session.md"
$lines | Out-File $out -Encoding utf8
$lines | ForEach-Object { Write-Host $_ }
Write-Host "saved: $out"
