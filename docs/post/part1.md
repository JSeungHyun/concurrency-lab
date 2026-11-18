<!--
  블로그 1편 템플릿. scripts/build-post.ps1 -Template docs\post\part1.md 가 {{...}} 자리를 실험 결과로 채운다.
  라벨: burst = 쿠폰 100장/3,000건/200명, big = 쿠폰 100장/30,000건/1,000명
  문장은 여기서 고치고, 수치는 손으로 쓰지 않는다. (본문에 직접 쓴 수치는 표의 중앙값을 옮긴 것이다)
-->

# 동시성 테스트 (레디스, 비관적 & 낙관적 LOCK 등) 1편: 방법별 비교

## 시작한 이유

수습 과제로 쇼핑몰을 만들 때 동시성 문제를 Redis 로 처리했다. 그때는 다른 방법과 비교해 보지 않았고, Redis 로 한번 해 봤다는 정도였다.

지나고 보니 동시성을 제어하는 방법은 여러 가지였다. DB 의 비관적 락, 낙관적 락, 조건부 UPDATE, 거기에 Redis 까지. 그런데 어떤 상황에서 어떤 방법이 유리한지는 제대로 공부해 본 적이 없었다. 그래서 같은 기능을 방법만 바꿔 가며 직접 부하를 걸어 보기로 했다. 이 글은 그 과정을 정리한 것이다.

시작할 때 궁금했던 것은 세 가지다.

1. Redis 를 쓰면 정말 DB 락보다 잘 버틸까?
2. 비관적 락과 낙관적 락은 실제로 어떤 차이가 날까?
3. Redis 없이 DB 만으로 충분한 경우도 있을까?

테스트 전에는 Redis 가 어떤 경우든 가장 잘 버틸 거라고 예상했다.

## 먼저 결과

{{IMG:burst:chart-soldout}}

- Redis 를 쓰면 더 잘 버티는 것은 맞았다. 다만 차이는 부하가 클 때 벌어졌다. 요청 3,000건에서는 DB 조건부 UPDATE 의 2배 정도였는데 30,000건으로 늘리자 약 4배가 됐다.
- Redis 를 쓴다고 다 빠른 것도 아니었다. 같은 Redis 라도 분산 락(Redisson)으로 쓰면 가장 느렸고 Lua 스크립트로 쓰면 가장 빨랐다.
- DB 만 쓰는 방법 중에서는 조건부 UPDATE 가 코드도 가장 간단하고 속도도 좋았다.

이 결과는 재고보다 요청이 훨씬 많은 매진 상황 기준이다. 재고가 충분하면 결과가 완전히 달라지는데 그 내용은 2편에서 다룬다.

## 테스트 방법

선착순 쿠폰 발급 API 를 대상으로 했다. 남은 수량을 확인하고 남아 있으면 한 장 발급하는 기능이다.

```
 k6 (부하 발생) ──▶ Spring Boot 앱 ──▶ MySQL 8.4
      │                   └─────────▶ Redis 7.4
      ▼
 InfluxDB ──▶ Grafana
```

- 전부 Docker Compose 로 PC 한 대에서 실행했다. 앱 컨테이너는 CPU 2코어, 메모리 1GB 로 제한했다.
- 부하는 k6 로 걸었다. 기본 조건은 {{CONDITIONS:burst}} 이다.
- 조건마다 3번씩 반복했고 글에 나오는 수치는 3회 중앙값이다.

확인한 지표는 다음과 같다.

| 지표 | 의미 |
|---|---|
| 초과 발급 | 쿠폰 수량보다 더 발급된 건수. 0이어야 한다 |
| 갱신 손실 | 실제 발급 건수와 쿠폰 테이블의 발급 수량 컬럼의 차이. 0이어야 한다 |
| RPS | 초당 처리한 요청 수 |
| p50 / p99 | 응답 시간을 빠른 순으로 정렬했을 때 50% / 99% 지점 |

초과 발급이 1건이라도 나오면 처리량과 상관없이 실패로 봤다. 부하테스트와 별개로 방법마다 정합성이 지켜지는지는 JUnit 동시성 테스트 24개로 먼저 확인했다.

## 제어가 없으면 생기는 문제

```java
@Transactional
public void issue(long couponId, long userId) {
    Coupon coupon = couponRepository.findById(couponId).orElseThrow();   // 1. 읽고
    if (coupon.getIssuedQuantity() >= coupon.getTotalQuantity()) {       // 2. 검사하고
        throw new IssueException(SOLD_OUT);
    }
    couponRepository.overwriteIssuedQuantity(couponId, coupon.getIssuedQuantity() + 1);  // 3. 쓴다
    issueRepository.save(new CouponIssue(couponId, userId));
}
```

**결과**: {{RESULT:burst:naive}}

{{IMG:burst:compare-issued}}

100장짜리 쿠폰이 2,000장 발급됐다. 그런데 쿠폰 테이블의 발급 수량 컬럼은 100으로 기록되어 있어서 이 컬럼만 보면 정상으로 보인다. 발급 기록 테이블의 행 수를 직접 세어 봐야 알 수 있는 문제였다.

원인은 1번 읽기와 3번 쓰기 사이에 다른 요청이 끼어들 수 있다는 점이다.

```
요청 A: 1. 50 읽음 ─ 2. 통과 ─ 3. 51 저장 ─ INSERT
요청 B:   1. 50 읽음 ─ 2. 통과 ─ 3. 51 저장 ─ INSERT
```

쿠폰은 2장 발급됐는데 컬럼 값은 51이 된다. 아래 방법들은 모두 이 문제를 막기 위한 것이다. 읽기와 쓰기 사이에 다른 요청이 끼어들지 못하게 하거나, 끼어들었을 때 감지해서 다시 처리한다.

## 방법별 동작과 결과

### synchronized

```java
public synchronized void issue(long couponId, long userId) {
    writer.issueReadCheckWrite(couponId, userId);   // 트랜잭션은 락 안에서 시작하고 끝난다
}
```

**결과**: {{RESULT:burst:synchronized}}

한 번에 한 요청만 메서드에 들어가게 한다. 가장 간단하지만 락이 JVM 메모리에 있어서 서버가 2대 이상이면 쓸 수 없다. 그리고 `@Transactional` 메서드에 `synchronized` 를 같이 붙이면 락이 풀린 뒤에 커밋되기 때문에 정합성이 깨진다. 트랜잭션 전체가 락 안에 들어가도록 분리해야 한다.

### 비관적 락 (SELECT FOR UPDATE)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Coupon c where c.id = :id")
Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
```

**결과**: {{RESULT:burst:pessimistic}}

조회할 때 쿠폰 행에 잠금을 걸고 커밋할 때까지 유지한다. 다음 요청은 앞 요청이 끝난 뒤에야 행을 읽을 수 있다. 서버가 여러 대여도 동작하지만 모든 요청이 순서대로 하나씩 처리되고, 기다리는 동안에도 DB 커넥션을 계속 쓴다.

### 낙관적 락 (@Version)

```java
@Version
private long version;   // UPDATE ... WHERE id = ? AND version = ?
```

**결과**: {{RESULT:burst:optimistic}} / 재시도 {{RETRIES:burst}}회

잠금을 걸지 않고, 저장할 때 내가 읽은 버전과 현재 버전이 같은지만 확인한다. 다르면 실패하고 처음부터 다시 시도한다.

{{IMG:burst:optimistic-latency}}

p50 은 DB 를 쓰는 방법 중 가장 빠른데 p99 는 가장 길다. 충돌한 요청이 여러 번 재시도하면서 일부 요청만 오래 걸리기 때문이다.

### 조건부 UPDATE

```sql
UPDATE coupon SET issued_quantity = issued_quantity + 1
WHERE id = ? AND issued_quantity < total_quantity
```

**결과**: {{RESULT:burst:atomic-update}}

수량 검사와 증가를 SQL 한 문장으로 처리한다. 읽기와 쓰기가 나뉘어 있지 않아서 사이에 끼어들 수 없다. 변경된 행이 0개면 매진이다. 코드가 가장 간단하고, DB 만 쓰는 방법 중에서 가장 빨랐다.

다만 이 방법은 지켜야 할 조건을 SQL 한 문장에 담을 수 있을 때만 통한다. 값을 먼저 조회한 뒤 앱에서 계산해서 저장하거나 변경된 행 수를 확인하지 않으면, 조건이 붙어 있어도 깨진다. 1인 1매처럼 수량 조건으로 표현할 수 없는 규칙은 따로 막아야 하고, 이 프로젝트에서는 유니크 키로 처리했다.

### Redisson 분산 락

```java
RLock lock = redisson.getLock("coupon:" + couponId + ":lock");
if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) throw new IssueException(LOCK_TIMEOUT);
try {
    writer.issueReadCheckWrite(couponId, userId);
} finally {
    lock.unlock();
}
```

**결과**: {{RESULT:burst:redisson-lock}}

락을 Redis 에 두어서 서버가 여러 대여도 같은 락을 공유한다. synchronized 를 여러 서버로 확장한 방식이다. 락을 잡고 풀 때마다 Redis 와 통신하고 그 안에서 DB 작업까지 하기 때문에 정합성을 지킨 방법 중에서는 가장 느렸다.

### Redis Lua 스크립트

```lua
local total = redis.call('GET', KEYS[2])
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end   -- 중복
if redis.call('SCARD', KEYS[1]) >= tonumber(total) then return 0 end    -- 매진
redis.call('SADD', KEYS[1], ARGV[1])
return 1                                                                -- 예약 성공
```

**결과**: {{RESULT:burst:redis-lua}}

Redis 는 명령을 한 번에 하나씩 처리하고, Lua 스크립트는 중간에 다른 명령이 끼어들지 않고 한 번에 실행된다. 이 성질을 이용해 중복 확인, 수량 확인, 예약을 스크립트 하나로 처리하고 예약에 성공한 요청만 DB 에 기록한다. 매진된 요청은 DB 까지 가지 않는다.

{{IMG:burst:redis-lua-latency}}

p50 이 6ms 로 다른 방법보다 훨씬 짧다. 대부분을 차지하는 매진 요청이 Redis 에서 바로 끝나기 때문이다. 대신 데이터가 Redis 와 DB 두 곳에 있어서 서로 맞지 않을 수 있다. DB 기록이 실패하면 Redis 예약을 취소하도록 했지만, 그 사이에 앱이 종료되면 맞지 않는 상태로 남는다.

## 결과 비교

### 요청 3,000건, 동시 200명

{{MEDIAN:burst}}

표에 있는 재고 분할은 2편에서 설명하는 방법이다. 비교를 위해 같이 넣었다.

Redis Lua 가 DB 락 방식보다 2배 이상 빨랐다. DB 를 쓰는 방법들은 초당 1,000~1,450건 범위였고 그중에서는 조건부 UPDATE 가 가장 빨랐다. 비관적 락과 낙관적 락을 비교하면 처리량은 낙관적 락이 높았지만 p99 는 비관적 락이 더 짧았다. 낙관적 락은 빠른 요청과 느린 요청의 차이가 크다.

### 부하를 10배로 늘리면

요청 3,000건은 DB 도 무리 없이 처리하는 수준이라, 조건을 요청 30,000건에 동시 1,000명으로 키워서 다시 측정했다. 쿠폰은 그대로 100장이다.

{{MEDIAN:big}}

Redis Lua 는 초당 8,187건으로 조건부 UPDATE(2,138건)의 약 4배가 됐고 p99 도 0.19초로 가장 짧았다. DB 를 쓰는 방법들은 처리량이 초당 1,300~2,900건에 머물렀고 p99 는 1초 안팎까지 늘었다.

이렇게 차이가 나는 이유는 매진 요청을 어디서 처리하느냐에 있다. 30,000건 중 29,900건은 매진으로 거절되는 요청인데, DB 방식은 이 요청들도 전부 DB 커넥션을 잡고 쿠폰 행에 접근해야 매진을 알 수 있다. 커넥션 풀은 20개뿐이라 나머지 요청은 기다린다. Redis Lua 는 이 요청들을 아예 DB 에 보내지 않는다.

## 예상과 달랐던 점

처음 예상은 Redis 가 어떤 경우든 가장 잘 버틴다는 것이었다. 테스트해 보니 일부만 맞았다.

- **Redis 의 이점은 부하가 클수록 커졌다.** 요청이 적을 때는 조건부 UPDATE 와의 차이가 2배 정도였다. 트래픽이 크지 않은 서비스라면 Redis 를 추가하지 않고 DB 만으로 처리하는 것도 충분한 선택이다.
- **Redis 를 어떻게 쓰느냐에 따라 결과가 반대로 갈렸다.** 분산 락으로 쓰면 가장 느렸고 Lua 스크립트로 쓰면 가장 빨랐다. 분산 락은 요청을 한 줄로 세우는 방식이고, Lua 스크립트는 판정 자체를 Redis 에서 끝내는 방식이라 성격이 다르다.
- **낙관적 락은 매진 상황에서는 생각보다 괜찮았다.** 매진된 뒤에는 쓰기가 없어서 충돌도 없기 때문이다. 하지만 재고가 충분한 상황에서는 결과가 가장 나빴다. 이 내용은 2편에서 다룬다.

## 다음 편

여기까지는 요청 대부분이 매진으로 거절되는 상황이었다. 그래서 위 수치는 실제 쓰기 성능보다 매진을 얼마나 빨리 응답하느냐를 잰 쪽에 가깝다.

2편에서는 재고를 충분히 늘려서 모든 요청이 실제 발급까지 가도록 하고 다시 측정한다. 이 조건에서는 Redis Lua 를 포함해 어떤 락을 써도 처리량이 비슷하게 나왔다. 그 이유와, 처리량을 높이려고 시도한 재고 분할을 다룬다.
