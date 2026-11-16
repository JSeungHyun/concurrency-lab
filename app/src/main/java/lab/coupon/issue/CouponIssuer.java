package lab.coupon.issue;

/**
 * 같은 "쿠폰 1장 발급" 을 서로 다른 동시성 제어 방식으로 구현한다.
 * 실패는 {@link IssueException} 으로 알린다.
 */
public interface CouponIssuer {

    /** URL 의 strategy 파라미터로 쓰는 이름 */
    String strategy();

    void issue(long couponId, long userId);
}
