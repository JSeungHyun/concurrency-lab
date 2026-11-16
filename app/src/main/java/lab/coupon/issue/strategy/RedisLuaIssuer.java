package lab.coupon.issue.strategy;

import java.util.List;
import lab.coupon.domain.CouponRepository;
import lab.coupon.issue.CouponIssuer;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import lab.coupon.issue.IssueWriter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 수량·중복 판정을 Redis Lua 스크립트 하나로 끝내고(단일 스레드라 원자적),
 * 통과한 요청만 DB 에 기록한다. DB 기록이 실패하면 Redis 예약을 되돌린다.
 */
@Component
public class RedisLuaIssuer implements CouponIssuer {

    // 1 = 예약 성공, 0 = 매진, -1 = 중복, -2 = 수량 키 없음
    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            local total = redis.call('GET', KEYS[2])
            if not total then return -2 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end
            if redis.call('SCARD', KEYS[1]) >= tonumber(total) then return 0 end
            redis.call('SADD', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CouponRepository couponRepository;
    private final IssueWriter writer;

    public RedisLuaIssuer(StringRedisTemplate redis, CouponRepository couponRepository, IssueWriter writer) {
        this.redis = redis;
        this.couponRepository = couponRepository;
        this.writer = writer;
    }

    @Override
    public String strategy() {
        return "redis-lua";
    }

    @Override
    public void issue(long couponId, long userId) {
        long reserved = reserve(couponId, userId);
        if (reserved == -2) {
            loadTotal(couponId);
            reserved = reserve(couponId, userId);
        }
        if (reserved == 0) {
            throw new IssueException(IssueResult.SOLD_OUT);
        }
        if (reserved == -1) {
            throw new IssueException(IssueResult.DUPLICATE);
        }
        try {
            writer.recordReserved(couponId, userId);
        } catch (RuntimeException e) {
            redis.opsForSet().remove(usersKey(couponId), String.valueOf(userId));
            throw e;
        }
    }

    /** 쿠폰 생성 시점에 수량을 Redis 에 올려 둔다. */
    public void registerTotal(long couponId, int totalQuantity) {
        redis.opsForValue().set(totalKey(couponId), String.valueOf(totalQuantity));
    }

    private long reserve(long couponId, long userId) {
        Long result = redis.execute(RESERVE, List.of(usersKey(couponId), totalKey(couponId)),
                String.valueOf(userId));
        return result == null ? -2 : result;
    }

    // Redis 가 재시작돼 키가 사라졌을 때를 위한 지연 적재
    private void loadTotal(long couponId) {
        int total = couponRepository.findById(couponId)
                .orElseThrow(() -> new IssueException(IssueResult.NOT_FOUND))
                .getTotalQuantity();
        redis.opsForValue().setIfAbsent(totalKey(couponId), String.valueOf(total));
    }

    private static String usersKey(long couponId) {
        return "coupon:" + couponId + ":users";
    }

    private static String totalKey(long couponId) {
        return "coupon:" + couponId + ":total";
    }
}
