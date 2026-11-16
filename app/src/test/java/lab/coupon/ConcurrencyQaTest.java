package lab.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.web.CouponController;
import lab.coupon.web.CouponController.CreateCouponRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 전략마다 같은 동시 요청을 실제 MySQL·Redis 에 쏘고, 결과 정합성을 검사한다.
 * docker compose 의 mysql·redis 가 떠 있어야 한다 (scripts/qa.ps1 이 compose 네트워크 안에서 실행한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrencyQaTest {

    private static final int THREADS = 64;
    private static final AtomicLong USER_SEQ = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired
    private CouponController controller;

    @Autowired
    private List<CouponIssuer> issuerList;

    private Map<String, CouponIssuer> issuers;

    @BeforeEach
    void setUp() {
        issuers = issuerList.stream().collect(Collectors.toMap(CouponIssuer::strategy, Function.identity()));
    }

    @Test
    void 모든_전략이_등록되어_있다() {
        assertThat(issuers.keySet()).containsExactlyInAnyOrder(
                "naive", "synchronized", "pessimistic", "optimistic",
                "atomic-update", "redisson-lock", "redis-lua", "stock-shard");
    }

    /** 재고 부족: 50장에 300명. 정확히 50장만 나가고 나머지는 매진이어야 한다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"synchronized", "pessimistic", "optimistic",
            "atomic-update", "redisson-lock", "redis-lua", "stock-shard"})
    void 재고_부족_상황에서_초과_발급이_없다(String strategy) throws Exception {
        long couponId = createCoupon(50);
        Outcome outcome = fire(strategy, couponId, distinctUsers(300));
        Map<String, Object> coupon = controller.get(couponId);

        assertThat(rows(coupon)).as("발급 행 수").isEqualTo(50);
        assertThat(counter(coupon)).as("카운터").isEqualTo(50);
        assertThat(outcome.issued()).as("성공 응답 수").isEqualTo(50);
        assertThat(outcome.failures()).as("매진 외 실패").isEmpty();
    }

    /** 재고 충분: 200장에 200명. 재고가 남았는데 실패하는 요청이 없어야 한다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"synchronized", "pessimistic", "optimistic",
            "atomic-update", "redisson-lock", "redis-lua", "stock-shard"})
    void 재고_충분_상황에서_전원_발급된다(String strategy) throws Exception {
        long couponId = createCoupon(200);
        Outcome outcome = fire(strategy, couponId, distinctUsers(200));
        Map<String, Object> coupon = controller.get(couponId);

        assertThat(outcome.issued()).as("성공 응답 수 (실패: %s)", outcome.failures()).isEqualTo(200);
        assertThat(rows(coupon)).isEqualTo(200);
        assertThat(counter(coupon)).isEqualTo(200);
    }

    /** 같은 사용자가 20번 동시에 눌러도 1장만 받는다. */
    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"synchronized", "pessimistic", "optimistic",
            "atomic-update", "redisson-lock", "redis-lua", "stock-shard"})
    void 같은_사용자의_동시_요청은_한_장만_발급된다(String strategy) throws Exception {
        long couponId = createCoupon(10);
        long userId = USER_SEQ.incrementAndGet();
        List<Long> sameUser = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sameUser.add(userId);
        }
        Outcome outcome = fire(strategy, couponId, sameUser);
        Map<String, Object> coupon = controller.get(couponId);

        assertThat(outcome.issued()).isEqualTo(1);
        assertThat(rows(coupon)).isEqualTo(1);
        assertThat(counter(coupon)).as("롤백된 요청이 카운터를 남기면 안 된다").isEqualTo(1);
    }

    /** 대조군: 제어 없음은 동시 요청에서 깨져야 실험이 성립한다. */
    @Test
    void 제어_없음은_초과_발급이나_갱신_손실이_난다() throws Exception {
        long couponId = createCoupon(50);
        fire("naive", couponId, distinctUsers(300));
        Map<String, Object> coupon = controller.get(couponId);

        boolean broken = rows(coupon) > 50 || counter(coupon) != rows(coupon);
        assertThat(broken).as("rows=%s counter=%s", rows(coupon), counter(coupon)).isTrue();
    }

    /** 재고 분할: 슬롯보다 수량이 적어도(7장) 정확히 7장만 나간다. */
    @Test
    void 재고_분할은_슬롯보다_적은_수량도_정확히_나눈다() throws Exception {
        long couponId = createCoupon(7);
        Outcome outcome = fire("stock-shard", couponId, distinctUsers(100));
        Map<String, Object> coupon = controller.get(couponId);

        assertThat(outcome.issued()).isEqualTo(7);
        assertThat(rows(coupon)).isEqualTo(7);
        assertThat(counter(coupon)).isEqualTo(7);
    }

    private long createCoupon(int quantity) {
        Map<String, Object> body = controller.create(new CreateCouponRequest("qa", quantity)).getBody();
        return ((Number) body.get("id")).longValue();
    }

    private static List<Long> distinctUsers(int count) {
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(USER_SEQ.incrementAndGet());
        }
        return users;
    }

    private Outcome fire(String strategy, long couponId, List<Long> users) throws Exception {
        CouponIssuer issuer = issuers.get(strategy);
        AtomicInteger issued = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long userId : users) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        issuer.issue(couponId, userId);
                        issued.incrementAndGet();
                    } catch (IssueException e) {
                        failures.computeIfAbsent(e.result().name(), k -> new AtomicInteger()).incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        failures.computeIfAbsent("DUPLICATE", k -> new AtomicInteger()).incrementAndGet();
                    } catch (RuntimeException e) {
                        failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> nonSoldOut = failures.entrySet().stream()
                .filter(e -> !e.getKey().equals("SOLD_OUT") && !e.getKey().equals("DUPLICATE"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
        return new Outcome(issued.get(), nonSoldOut);
    }

    private static long rows(Map<String, Object> coupon) {
        return ((Number) coupon.get("issuedRows")).longValue();
    }

    private static long counter(Map<String, Object> coupon) {
        return ((Number) coupon.get("issuedQuantity")).longValue();
    }

    /** failures 는 매진·중복을 뺀 실패(락 타임아웃, 재시도 소진, 교착 등) */
    private record Outcome(int issued, Map<String, Integer> failures) {
    }
}
