# 전략별 동시성 QA 테스트(JUnit)를 compose 네트워크 안에서 실행한다.
# mysql·redis 컨테이너가 떠 있어야 한다. 결과 리포트: app/build/reports/tests/test/index.html
#   .\scripts\qa.ps1
#   .\scripts\qa.ps1 -Tests '*재고_분할*'
param(
    [string]$Tests = ''
)

$root = Split-Path -Parent $PSScriptRoot
$app = Join-Path $root 'app'

docker compose -f (Join-Path $root 'docker-compose.yml') up -d mysql redis | Out-Null

$filter = @()
if ($Tests) {
    $filter = @('--tests', $Tests)
}

docker run --rm `
    --network concurrency-lab_default `
    -e DB_HOST=mysql -e REDIS_HOST=redis `
    -v "${app}:/workspace" `
    -v concurrency-lab-gradle:/home/gradle/.gradle `
    -w /workspace `
    gradle:8.14.3-jdk21 `
    gradle test --rerun --no-daemon --console=plain @filter
$code = $LASTEXITCODE

$xmlDir = Join-Path $app 'build\test-results\test'
if (Test-Path $xmlDir) {
    $total = 0; $failed = 0
    Get-ChildItem $xmlDir -Filter '*.xml' | ForEach-Object {
        [xml]$x = Get-Content $_.FullName -Raw -Encoding UTF8
        foreach ($case in $x.testsuite.testcase) {
            $total++
            $mark = if ($case.failure -or $case.error) { $failed++; 'FAIL' } else { 'PASS' }
            '{0}  {1}  ({2}s)' -f $mark, $case.name, $case.time
        }
    }
    ''
    "합계 $total / 실패 $failed"
}
exit $code
