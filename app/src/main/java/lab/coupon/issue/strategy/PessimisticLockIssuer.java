package lab.coupon.issue.strategy;

import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueWriter;
import org.springframework.stereotype.Component;

/** SELECT ... FOR UPDATE 로 쿠폰 행을 잠근다. */
@Component
public class PessimisticLockIssuer implements CouponIssuer {

    private final IssueWriter writer;

    public PessimisticLockIssuer(IssueWriter writer) {
        this.writer = writer;
    }

    @Override
    public String strategy() {
        return "pessimistic";
    }

    @Override
    public void issue(long couponId, long userId) {
        writer.issueWithRowLock(couponId, userId);
    }
}
