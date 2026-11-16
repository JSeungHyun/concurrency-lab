package lab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_coupon_user", columnNames = {"couponId", "userId"}))
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long couponId;

    private Long userId;

    private Instant issuedAt;

    protected CouponIssue() {
    }

    public CouponIssue(Long couponId, Long userId) {
        this.couponId = couponId;
        this.userId = userId;
        this.issuedAt = Instant.now();
    }
}
