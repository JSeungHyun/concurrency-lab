package lab.coupon.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lab.coupon.domain.Coupon;
import lab.coupon.domain.CouponIssueRepository;
import lab.coupon.domain.CouponRepository;
import lab.coupon.domain.CouponStockRepository;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import lab.coupon.issue.strategy.OptimisticLockIssuer;
import lab.coupon.issue.strategy.RedisLuaIssuer;
import lab.coupon.issue.strategy.StockShardIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CouponController {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final CouponStockRepository stockRepository;
    private final RedisLuaIssuer redisLuaIssuer;
    private final StockShardIssuer stockShardIssuer;
    private final OptimisticLockIssuer optimisticLockIssuer;
    private final Map<String, CouponIssuer> issuers;

    public CouponController(CouponRepository couponRepository, CouponIssueRepository issueRepository,
                            CouponStockRepository stockRepository, RedisLuaIssuer redisLuaIssuer,
                            StockShardIssuer stockShardIssuer, OptimisticLockIssuer optimisticLockIssuer,
                            List<CouponIssuer> issuers) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.stockRepository = stockRepository;
        this.redisLuaIssuer = redisLuaIssuer;
        this.stockShardIssuer = stockShardIssuer;
        this.optimisticLockIssuer = optimisticLockIssuer;
        this.issuers = issuers.stream().collect(Collectors.toMap(CouponIssuer::strategy, Function.identity()));
    }

    public record CreateCouponRequest(String name, int totalQuantity) {
    }

    public record IssueRequest(long userId) {
    }

    @PostMapping("/coupons")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateCouponRequest request) {
        Coupon coupon = couponRepository.save(new Coupon(request.name(), request.totalQuantity()));
        redisLuaIssuer.registerTotal(coupon.getId(), coupon.getTotalQuantity());
        stockShardIssuer.registerSlots(coupon.getId(), coupon.getTotalQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", coupon.getId()));
    }

    /**
     * 실험 판정용: 카운터와 실제 발급 행 수를 함께 돌려준다.
     * 재고 분할 전략은 쿠폰 행 대신 슬롯 행을 올리므로 카운터 = 쿠폰 컬럼 + 슬롯 합계다.
     */
    @GetMapping("/coupons/{couponId}")
    public Map<String, Object> get(@PathVariable long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND));
        long issuedRows = issueRepository.countByCouponId(couponId);
        long issuedQuantity = coupon.getIssuedQuantity() + stockRepository.sumIssued(couponId);
        return Map.of(
                "id", coupon.getId(),
                "totalQuantity", coupon.getTotalQuantity(),
                "issuedQuantity", issuedQuantity,
                "issuedRows", issuedRows,
                "overIssued", issuedRows > coupon.getTotalQuantity(),
                "counterMatchesRows", issuedQuantity == issuedRows);
    }

    @PostMapping("/coupons/{couponId}/issue")
    public Map<String, Object> issue(@PathVariable long couponId,
                                     @RequestParam(defaultValue = "naive") String strategy,
                                     @RequestBody IssueRequest request) {
        CouponIssuer issuer = issuers.get(strategy);
        if (issuer == null) {
            throw new UnknownStrategyException(strategy, issuers.keySet());
        }
        issuer.issue(couponId, request.userId());
        return Map.of("result", IssueResult.ISSUED);
    }

    @GetMapping("/strategies")
    public List<String> strategies() {
        return issuers.keySet().stream().sorted().toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("optimisticRetries", optimisticLockIssuer.retryCount());
    }
}
