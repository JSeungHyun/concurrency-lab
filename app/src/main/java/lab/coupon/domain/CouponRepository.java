package lab.coupon.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

    // 읽은 값 + 1 을 그대로 덮어쓴다. 동시에 같은 값을 읽은 요청끼리 갱신이 사라진다(lost update).
    @Modifying
    @Query("update Coupon c set c.issuedQuantity = :issuedQuantity where c.id = :id")
    int overwriteIssuedQuantity(@Param("id") Long id, @Param("issuedQuantity") int issuedQuantity);

    // 조건과 증가가 한 문장이라 DB 의 행 잠금 안에서 원자적으로 처리된다.
    @Modifying
    @Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 "
            + "where c.id = :id and c.issuedQuantity < c.totalQuantity")
    int incrementIfAvailable(@Param("id") Long id);

    @Modifying
    @Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 where c.id = :id")
    int increment(@Param("id") Long id);
}
