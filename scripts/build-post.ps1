# 블로그 게시글 생성: docs/post/template.md 의 {{...}} 자리를 실험 결과와 캡처로 채운다.
#   .\scripts\build-post.ps1 -Runs @{ burst = '20261025-232718-r1' }
#   .\scripts\build-post.ps1 -Runs @{ burst = '20261025-232718-r1'; write = '20261026-101500-r1' } -Name final
# 결과: results/post/<Name>/post.md + images/ (그대로 블로그에 옮기면 된다)
# 이 파일은 한글을 담고 있어 UTF-8 BOM 으로 저장한다 (Windows PowerShell 5.1 호환).
param(
    [Parameter(Mandatory = $true)][hashtable]$Runs,
    [string]$Name = (Get-Date -Format 'yyyyMMdd-HHmm'),
    [string]$Template = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Template) { $Template = Join-Path $root 'docs\post\template.md' }
$resultsDir = Join-Path $root 'results'
$outDir = Join-Path $resultsDir "post\$Name"
$imgDir = Join-Path $outDir 'images'
New-Item -ItemType Directory -Force -Path $imgDir | Out-Null

$order = @('naive', 'synchronized', 'pessimistic', 'optimistic', 'atomic-update', 'redisson-lock', 'redis-lua', 'stock-shard')
$labels = @{
    'naive'         = '제어 없음'
    'synchronized'  = 'synchronized'
    'pessimistic'   = '비관적 락'
    'optimistic'    = '낙관적 락'
    'atomic-update' = '조건부 UPDATE'
    'redisson-lock' = 'Redisson 분산 락'
    'redis-lua'     = 'Redis Lua'
    'stock-shard'   = '재고 분할'
}

# 라벨별 실행 결과 로드
$reports = @{}
foreach ($label in $Runs.Keys) {
    $runId = $Runs[$label]
    $files = Get-ChildItem -Path $resultsDir -Filter "$($runId)_*.json" -File
    if (-not $files) { throw "results/$($runId)_*.json 이 없다 (라벨 '$label')" }
    $reports[$label] = @($files | ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } |
        Sort-Object { [array]::IndexOf($order, $_.strategy) })
}

function Missing([string]$label) {
    return "> ⚠️ ``$label`` 실행 결과가 없다. ``build-post.ps1 -Runs @{ $label = '<RUN_ID>' }`` 로 추가한다."
}

function Find-Report([string]$label, [string]$strategy) {
    return $reports[$label] | Where-Object { $_.strategy -eq $strategy } | Select-Object -First 1
}

function Format-Conditions([string]$label) {
    $c = $reports[$label][0].conditions
    $cfg = $c.config
    $load = if ($null -eq $cfg) {
        "요청 $($c.requests)건, 동시 사용자 $($c.vus)명"
    } elseif ($cfg.executor -eq 'shared-iterations') {
        "요청 $($cfg.iterations)건, 동시 사용자 $($cfg.vus)명"
    } elseif ($cfg.executor -eq 'constant-arrival-rate') {
        "초당 $($cfg.rate)건 × $($cfg.duration)"
    } else {
        "초당 $($cfg.startRate)건에서 단계적으로 " + (($cfg.stages | ForEach-Object { $_.target }) | Select-Object -Last 1) + '건까지'
    }
    return "쿠폰 $($c.quantity)장 / $load"
}

function Fmt([double]$v) { return ('{0:N0}' -f [math]::Round($v)) }

# 같은 세션의 모든 회차에서 전략 하나의 결과를 모은다.
function Get-SessionRuns([string]$label, [string]$strategy) {
    $session = $Runs[$label] -replace '-r\d+$', ''
    return @(Get-ChildItem -Path $resultsDir -Filter "$session-r*_*.json" -File |
        ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } |
        Where-Object { $_.strategy -eq $strategy })
}

# 전략 하나의 결과 한 줄. 수치는 회차 중앙값이다.
function Format-Result([string]$label, [string]$strategy) {
    $runs = Get-SessionRuns $label $strategy
    if (-not $runs) { return $null }
    $med = { param($sel) Get-Median @($runs | ForEach-Object $sel | ForEach-Object { [double]$_ }) }
    $over = & $med { $_.integrity.overIssued }
    $lost = & $med { $_.integrity.lostUpdates }
    $under = & $med { $_.integrity.underIssued }
    $allPassed = @($runs | Where-Object { -not $_.integrity.passed }).Count -eq 0
    $verdict = if ($allPassed) { '정합성 **통과**' } else { "정합성 **실패** (초과 발급 $(Fmt $over)건, 갱신 손실 $(Fmt $lost)건)" }
    if ($under -gt 0) { $verdict += " · 미발급 $(Fmt $under)건" }
    return "$verdict · RPS $(Fmt (& $med { $_.performance.rps })) · p50 $(Fmt (& $med { $_.performance.latencyMs.p50 }))ms · p99 $(Fmt (& $med { $_.performance.latencyMs.p99 }))ms"
}

function Format-Table([string]$label) {
    $lines = @(
        '| 방법 | 정합성 | 발급 / 수량 | 초과 발급 | 갱신 손실 | RPS | p50 (ms) | p95 (ms) | p99 (ms) |',
        '|---|---|---|---|---|---|---|---|---|'
    )
    foreach ($r in $reports[$label]) {
        $i = $r.integrity
        $p = $r.performance
        $verdict = if ($i.passed) { '통과' } else { '**실패**' }
        $lines += "| $($labels[$r.strategy]) | $verdict | $($i.issuedRows) / $($i.totalQuantity) | $($i.overIssued) | $($i.lostUpdates) | $($p.rps) | $($p.latencyMs.p50) | $($p.latencyMs.p95) | $($p.latencyMs.p99) |"
    }
    return $lines -join "`n"
}

function Get-Median([double[]]$values) {
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    if ($n % 2) { return $sorted[[int][math]::Floor($n / 2)] }
    return [math]::Round(($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2, 1)
}

# 같은 run-all 세션의 모든 회차(<세션>-r1, -r2, ...)를 모아 전략별 중앙값으로 줄 세운다.
function Format-Median([string]$label) {
    $session = $Runs[$label] -replace '-r\d+$', ''
    $all = Get-ChildItem -Path $resultsDir -Filter "$session-r*_*.json" -File |
        ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json }
    $rows = foreach ($g in ($all | Group-Object strategy)) {
        $rps = @($g.Group | ForEach-Object { [double]$_.performance.rps })
        [pscustomobject]@{
            Strategy = $g.Name
            Rounds   = $g.Count
            Passed   = @($g.Group | Where-Object { $_.integrity.passed }).Count
            Min      = ($rps | Measure-Object -Minimum).Minimum
            Max      = ($rps | Measure-Object -Maximum).Maximum
            Rps      = Get-Median $rps
            P95      = Get-Median @($g.Group | ForEach-Object { [double]$_.performance.latencyMs.p95 })
            P99      = Get-Median @($g.Group | ForEach-Object { [double]$_.performance.latencyMs.p99 })
        }
    }
    $lines = @(
        '| 방법 | 정합성 | RPS (최소~최대) | p99 (ms) |',
        '|---|---|---|---|'
    )
    foreach ($m in ($rows | Sort-Object Rps -Descending)) {
        $verdict = if ($m.Passed -eq $m.Rounds) { '통과' } else { '**실패**' }
        $lines += "| $($labels[$m.Strategy]) | $verdict | **$(Fmt $m.Rps)** ($(Fmt $m.Min)~$(Fmt $m.Max)) | $(Fmt $m.P99) |"
    }
    return $lines -join "`n"
}

function Copy-Image([string]$label, [string]$file) {
    $src = Join-Path $resultsDir "screenshots\$($Runs[$label])\$file.png"
    if (-not (Test-Path $src)) {
        return "> ⚠️ 이미지가 없다: ``results/screenshots/$($Runs[$label])/$file.png`` (``capture.ps1 -RunId $($Runs[$label])`` 로 생성)"
    }
    $dest = "$label-$file.png"
    Copy-Item $src (Join-Path $imgDir $dest) -Force
    return "![$label $file](images/$dest)"
}

$text = Get-Content $Template -Raw -Encoding UTF8
# 템플릿 사용법 주석은 게시글에 남기지 않는다
$text = [regex]::Replace($text, '(?s)\A<!--.*?-->\s*', '')

$warnings = New-Object System.Collections.ArrayList
$text = [regex]::Replace($text, '\{\{(\w+):([\w-]+)(?::([\w-]+))?\}\}', {
        param($m)
        $kind = $m.Groups[1].Value
        $label = $m.Groups[2].Value
        $arg = $m.Groups[3].Value
        if (-not $reports.ContainsKey($label)) {
            [void]$warnings.Add("missing run label: $label")
            return (Missing $label)
        }
        switch ($kind) {
            'CONDITIONS' { return (Format-Conditions $label) }
            'TABLE' { return (Format-Table $label) }
            'MEDIAN' { return (Format-Median $label) }
            'RESULT' {
                $line = Format-Result $label $arg
                if (-not $line) { [void]$warnings.Add("no $arg in $label"); return "> ⚠️ ``$label`` 에 ``$arg`` 결과가 없다." }
                return $line
            }
            'RETRIES' {
                $runs = Get-SessionRuns $label 'optimistic'
                if ($runs) { return (Fmt (Get-Median @($runs | ForEach-Object { [double]$_.optimisticRetries }))) } else { return '?' }
            }
            'IMG' { return (Copy-Image $label $arg) }
            default {
                [void]$warnings.Add("unknown placeholder: $($m.Value)")
                return $m.Value
            }
        }
    })

$postPath = Join-Path $outDir 'post.md'
[IO.File]::WriteAllText($postPath, $text, (New-Object Text.UTF8Encoding $false))

foreach ($w in $warnings) { Write-Warning $w }
$todo = ([regex]::Matches($text, 'TODO')).Count
Write-Host "saved: $postPath"
Write-Host "images: $((Get-ChildItem $imgDir -File).Count) files in $imgDir"
if ($todo -gt 0) { Write-Host "TODO left in post: $todo (written by hand in docs/post/template.md)" }
