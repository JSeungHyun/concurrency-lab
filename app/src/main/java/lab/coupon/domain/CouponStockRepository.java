package lab.coupon.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    boolean existsByCouponId(Long couponId);

    // 잠금 없는 스냅샷 읽기. 후보를 고르는 용도라 조금 낡아도 된다. 최종 판정은 아래 UPDATE 가 한다.
    @Query("select s.id from CouponStock s where s.couponId = :couponId and s.issuedQuantity < s.totalQuantity")
    List<Long> findAvailableIds(@Param("couponId") Long couponId);

    @Modifying
    @Query("update CouponStock s set s.issuedQuantity = s.issuedQuantity + 1 "
            + "where s.id = :id and s.issuedQuantity < s.totalQuantity")
    int incrementIfAvailable(@Param("id") Long id);

    @Query("select coalesce(sum(s.issuedQuantity), 0) from CouponStock s where s.couponId = :couponId")
    long sumIssued(@Param("couponId") Long couponId);
}
