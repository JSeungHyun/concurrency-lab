package lab.coupon.issue.strategy;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import lab.coupon.issue.IssueWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** @Version 충돌을 감지하면 트랜잭션 밖에서 재시도한다. 경합이 심할수록 재시도가 폭증한다. */
@Component
public class OptimisticLockIssuer implements CouponIssuer {

    private final IssueWriter writer;
    private final int maxRetries;
    private final AtomicLong retryCount = new AtomicLong();

    public OptimisticLockIssuer(IssueWriter writer,
                                @Value("${coupon.optimistic-max-retries}") int maxRetries) {
        this.writer = writer;
        this.maxRetries = maxRetries;
    }

    @Override
    public String strategy() {
        return "optimistic";
    }

    @Override
    public void issue(long couponId, long userId) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                writer.issueWithVersion(couponId, userId);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                retryCount.incrementAndGet();
                sleepQuietly(ThreadLocalRandom.current().nextLong(5, 30));
            }
        }
        throw new IssueException(IssueResult.RETRY_EXHAUSTED);
    }

    /** 누적 재시도 횟수. 실험 기록용으로 /api/stats 에 노출한다. */
    public long retryCount() {
        return retryCount.get();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IssueException(IssueResult.RETRY_EXHAUSTED);
        }
    }
}
