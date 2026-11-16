package lab.coupon.issue;

import lab.coupon.domain.Coupon;
import lab.coupon.domain.CouponIssue;
import lab.coupon.domain.CouponIssueRepository;
import lab.coupon.domain.CouponRepository;
import lab.coupon.domain.CouponStockRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭션 경계. 락을 쓰는 전략은 이 빈의 메서드를 락 "안에서" 호출해야
 * 커밋이 끝난 뒤에 락이 풀린다.
 */
@Component
public class IssueWriter {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final CouponStockRepository stockRepository;

    public IssueWriter(CouponRepository couponRepository, CouponIssueRepository issueRepository,
                       CouponStockRepository stockRepository) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.stockRepository = stockRepository;
    }

    /** 읽고 → 검사하고 → 쓴다. 보호 장치가 없으면 초과 발급과 갱신 손실이 난다. */
    @Transactional
    public void issueReadCheckWrite(long couponId, long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND));
        ensureNotIssued(couponId, userId);
        if (coupon.getIssuedQuantity() >= coupon.getTotalQuantity()) {
            throw new IssueException(IssueResult.SOLD_OUT);
        }
        couponRepository.overwriteIssuedQuantity(couponId, coupon.getIssuedQuantity() + 1);
        issueRepository.save(new CouponIssue(couponId, userId));
    }

    @Transactional
    public void issueWithRowLock(long couponId, long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND));
        ensureNotIssued(couponId, userId);
        coupon.issue();
        issueRepository.save(new CouponIssue(couponId, userId));
    }

    /** 커밋 시점에 version 이 바뀌어 있으면 ObjectOptimisticLockingFailureException 이 난다. */
    @Transactional
    public void issueWithVersion(long couponId, long userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND));
        ensureNotIssued(couponId, userId);
        coupon.issue();
        issueRepository.save(new CouponIssue(couponId, userId));
    }

    @Transactional
    public void issueWithConditionalUpdate(long couponId, long userId) {
        ensureNotIssued(couponId, userId);
        if (couponRepository.incrementIfAvailable(couponId) == 0) {
            throw new IssueException(couponRepository.existsById(couponId)
                    ? IssueResult.SOLD_OUT
                    : IssueResult.NOT_FOUND);
        }
        issueRepository.save(new CouponIssue(couponId, userId));
    }

    /**
     * 재고 분할의 슬롯 한 번 시도. 슬롯이 비어 있으면 false 를 돌려주고 트랜잭션을 끝낸다.
     * 한 트랜잭션에서 여러 슬롯을 차례로 잠그면, 두 요청이 서로가 쥔 빈 슬롯을 기다리는 교착이 난다.
     */
    @Transactional
    public boolean issueFromSlot(long couponId, long stockId, long userId) {
        ensureNotIssued(couponId, userId);
        if (stockRepository.incrementIfAvailable(stockId) == 0) {
            return false;
        }
        issueRepository.save(new CouponIssue(couponId, userId));
        return true;
    }

    /** 수량 판정은 이미 Redis 에서 끝났고 DB 에는 기록만 남긴다. */
    @Transactional
    public void recordReserved(long couponId, long userId) {
        couponRepository.increment(couponId);
        issueRepository.save(new CouponIssue(couponId, userId));
    }

    private void ensureNotIssued(long couponId, long userId) {
        if (issueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new IssueException(IssueResult.DUPLICATE);
        }
    }
}
