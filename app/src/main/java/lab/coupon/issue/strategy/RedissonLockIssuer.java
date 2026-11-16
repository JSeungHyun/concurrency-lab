package lab.coupon.issue.strategy;

import java.util.concurrent.TimeUnit;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import lab.coupon.issue.IssueWriter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 분산 락. 인스턴스가 여러 대여도 유효하다.
 * leaseTime 을 주지 않아 watchdog 이 락을 연장한다.
 */
@Component
public class RedissonLockIssuer implements CouponIssuer {

    private final RedissonClient redisson;
    private final IssueWriter writer;
    private final long waitMs;

    public RedissonLockIssuer(RedissonClient redisson, IssueWriter writer,
                              @Value("${coupon.lock-wait-ms}") long waitMs) {
        this.redisson = redisson;
        this.writer = writer;
        this.waitMs = waitMs;
    }

    @Override
    public String strategy() {
        return "redisson-lock";
    }

    @Override
    public void issue(long couponId, long userId) {
        RLock lock = redisson.getLock("coupon:" + couponId + ":lock");
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IssueException(IssueResult.LOCK_TIMEOUT);
        }
        if (!acquired) {
            throw new IssueException(IssueResult.LOCK_TIMEOUT);
        }
        try {
            writer.issueReadCheckWrite(couponId, userId);
        } finally {
            lock.unlock();
        }
    }
}
