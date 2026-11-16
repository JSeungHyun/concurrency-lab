package lab.coupon.issue.strategy;

import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueWriter;
import org.springframework.stereotype.Component;

/**
 * JVM 모니터 락. 트랜잭션을 락 안에서 시작하고 끝내므로 커밋 전에 락이 풀리는 함정은 피한다.
 * 다만 프로세스 하나 안에서만 유효하다. 앱 인스턴스가 둘이면 다시 깨진다.
 */
@Component
public class SynchronizedIssuer implements CouponIssuer {

    private final IssueWriter writer;

    public SynchronizedIssuer(IssueWriter writer) {
        this.writer = writer;
    }

    @Override
    public String strategy() {
        return "synchronized";
    }

    @Override
    public synchronized void issue(long couponId, long userId) {
        writer.issueReadCheckWrite(couponId, userId);
    }
}
