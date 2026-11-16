# concurrency-lab

선착순 쿠폰 발급을 소재로 **동시성 제어 방식을 하나씩 도입하며** 정합성과 성능이 어떻게 변하는지 부하테스트로 비교하는 실험실.

## 구성

| 서비스 | 역할 | 호스트 포트 |
|---|---|---|
| app | Spring Boot 3.5 / Java 21 쿠폰 API (CPU 2, 메모리 1g 제한) | 8080 |
| mysql | MySQL 8.4 | 13306 (`MYSQL_PORT`) |
| redis | Redis 7.4 (Redisson 락, Lua 예약) | 16379 (`REDIS_PORT`) |
| influxdb | k6 지표 저장 | 8086 |
| grafana | 대시보드 http://localhost:3000 (로그인 없음) | 3000 |
| k6 | 부하 발생기. `profiles: k6` 라 필요할 때만 실행 | - |

## 실행

```powershell
docker compose up -d --build

# 전략 하나
docker compose run --rm -e STRATEGY=pessimistic k6 run --quiet /scripts/issue.js

# 전체 전략을 같은 조건으로 돌리고 비교표 생성
.\scripts\run-all.ps1
.\scripts\run-all.ps1 -Strategies naive,optimistic -Quantity 1000 -Requests 5000 -Vus 300 -Repeat 3

# 기존 결과로 비교표만 다시 생성
.\scripts\compare.ps1 -Session 20261025-223550
```

k6 환경 변수: `STRATEGY`, `QUANTITY`(쿠폰 수량, 100), `REQUESTS`(총 요청, 3000), `VUS`(동시 사용자, 200), `RUN_ID`.
앱 튜닝 변수(`.env` 또는 셸): `DB_POOL_SIZE`, `TOMCAT_MAX_THREADS`, `VIRTUAL_THREADS`, `STOCK_SLOTS`, `APP_CPUS`, `JAVA_OPTS`.

## 전략

| strategy | 단계 | 방식 | 핵심 코드 |
|---|---|---|---|
| `naive` | 0 | 제어 없음 (읽고-검사하고-쓰기) | `IssueWriter.issueReadCheckWrite` |
| `synchronized` | 1 | JVM 모니터 락 | `SynchronizedIssuer` |
| `pessimistic` | 2 | `SELECT ... FOR UPDATE` | `IssueWriter.issueWithRowLock` |
| `optimistic` | 3 | `@Version` + 재시도 | `OptimisticLockIssuer` |
| `atomic-update` | 4 | `UPDATE ... WHERE issued < total` | `IssueWriter.issueWithConditionalUpdate` |
| `redisson-lock` | 5 | Redis 분산 락 | `RedissonLockIssuer` |
| `redis-lua` | 6 | Redis Lua 로 예약 후 DB 기록 | `RedisLuaIssuer` |
| `stock-shard` | 7 | 재고를 N개 슬롯 행으로 나눠 조건부 UPDATE (`STOCK_SLOTS`, 기본 10) | `StockShardIssuer` |

## API

- `POST /api/coupons` `{"name":"x","totalQuantity":100}` → `{"id":1}`
- `POST /api/coupons/{id}/issue?strategy=naive` `{"userId":1}` → 200 `ISSUED` / 409 `SOLD_OUT`·`DUPLICATE` / 503 `LOCK_TIMEOUT`·`RETRY_EXHAUSTED`
- `GET /api/coupons/{id}` → 수량, 카운터, 실제 발급 행 수, `overIssued`, `counterMatchesRows`
- `GET /api/strategies`, `GET /api/stats`

## 결과 기록

- 실행마다 `results/<RUN_ID>_<strategy>.json|.md` 자동 저장
- `run-all.ps1` 은 끝에 `results/comparison-<session>.md` 비교표를 만든다
- `run-all.ps1` 은 마지막에 Grafana 패널을 PNG 로 저장한다 → `results/screenshots/<RUN_ID>/` (`-NoCapture` 로 끔)
  - 전략별: `<strategy>-rps.png`, `<strategy>-latency.png`, `<strategy>-status.png`
  - 전략 비교: `compare-latency.png`, `compare-issued.png`
  - 따로 찍기: `.\scripts\capture.ps1 -RunId <RUN_ID> [-Strategy naive] [-Theme dark] [-Width 1600]`
- 블로그 게시글: `.\scripts\build-post.ps1 -Runs @{ burst = '<RUN_ID>'; write = '<RUN_ID>' }` → `results/post/<이름>/` ([docs/blog-plan.md](docs/blog-plan.md))
- `results/` 는 자동 생성물이라 git 에 올리지 않는다 (`.gitignore`)
- 정합성 판정: 실제 발급 행 ≤ 수량 **그리고** 카운터 = 실제 행 수
- 실험 기록과 글 초안은 [docs/](docs/) 에 둔다
