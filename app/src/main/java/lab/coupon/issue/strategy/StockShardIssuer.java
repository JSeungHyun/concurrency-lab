package lab.coupon.issue.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lab.coupon.domain.CouponRepository;
import lab.coupon.domain.CouponStock;
import lab.coupon.domain.CouponStockRepository;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import lab.coupon.issue.IssueWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 재고를 N 개 슬롯 행으로 나누고, 요청마다 임의의 슬롯에 조건부 UPDATE 를 건다.
 * 한 행을 기다리던 줄이 N 줄로 갈라진다. 고른 슬롯이 비었으면 남은 슬롯을 차례로 시도하되,
 * 시도마다 트랜잭션을 새로 연다(한 트랜잭션이 잠그는 슬롯은 최대 1개).
 */
@Component
public class StockShardIssuer implements CouponIssuer {

    private final CouponRepository couponRepository;
    private final CouponStockRepository stockRepository;
    private final IssueWriter writer;
    private final int slots;

    public StockShardIssuer(CouponRepository couponRepository, CouponStockRepository stockRepository,
                            IssueWriter writer, @Value("${coupon.stock-slots}") int slots) {
        this.couponRepository = couponRepository;
        this.stockRepository = stockRepository;
        this.writer = writer;
        this.slots = slots;
    }

    @Override
    public String strategy() {
        return "stock-shard";
    }

    @Override
    public void issue(long couponId, long userId) {
        if (!stockRepository.existsByCouponId(couponId)) {
            loadSlots(couponId);
        }
        List<Long> candidates = stockRepository.findAvailableIds(couponId);
        int start = candidates.isEmpty() ? 0 : ThreadLocalRandom.current().nextInt(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            if (writer.issueFromSlot(couponId, candidates.get((start + i) % candidates.size()), userId)) {
                return;
            }
        }
        throw new IssueException(IssueResult.SOLD_OUT);
    }

    /** 쿠폰 생성 시점에 수량을 슬롯으로 나눠 둔다. 나머지는 앞 슬롯부터 1장씩 더 준다. */
    public void registerSlots(long couponId, int totalQuantity) {
        int count = Math.max(1, Math.min(slots, totalQuantity));
        List<CouponStock> stocks = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            int quantity = totalQuantity / count + (slot < totalQuantity % count ? 1 : 0);
            stocks.add(new CouponStock(couponId, slot, quantity));
        }
        stockRepository.saveAll(stocks);
    }

    // 슬롯이 없는 예전 쿠폰을 위한 지연 적재. 동시에 여러 요청이 와도 유니크 키가 한 번만 들어가게 막는다.
    private void loadSlots(long couponId) {
        int total = couponRepository.findById(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND))
                .getTotalQuantity();
        try {
            registerSlots(couponId, total);
        } catch (DataIntegrityViolationException alreadyLoaded) {
            // 다른 요청이 먼저 만들었다
        }
    }
}
