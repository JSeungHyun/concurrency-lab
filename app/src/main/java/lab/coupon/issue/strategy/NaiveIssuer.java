package lab.coupon.issue.strategy;

import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueWriter;
import org.springframework.stereotype.Component;

/** 0단계: 아무 제어도 없다. 문제를 재현하기 위한 기준선. */
@Component
public class NaiveIssuer implements CouponIssuer {

    private final IssueWriter writer;

    public NaiveIssuer(IssueWriter writer) {
        this.writer = writer;
    }

    @Override
    public String strategy() {
        return "naive";
    }

    @Override
    public void issue(long couponId, long userId) {
        writer.issueReadCheckWrite(couponId, userId);
    }
}
