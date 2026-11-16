package lab.coupon.issue.strategy;

import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueWriter;
import org.springframework.stereotype.Component;

/** UPDATE ... SET issued = issued + 1 WHERE issued < total. 검사와 증가를 한 문장에 합친다. */
@Component
public class ConditionalUpdateIssuer implements CouponIssuer {

    private final IssueWriter writer;

    public ConditionalUpdateIssuer(IssueWriter writer) {
        this.writer = writer;
    }

    @Override
    public String strategy() {
        return "atomic-update";
    }

    @Override
    public void issue(long couponId, long userId) {
        writer.issueWithConditionalUpdate(couponId, userId);
    }
}
