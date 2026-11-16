package lab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 재고 분할 전략용. 쿠폰 1장의 재고를 여러 행(슬롯)으로 나눠
 * 한 행(hot row)에 몰리던 쓰기 경합을 슬롯 수만큼 흩는다.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_coupon_slot", columnNames = {"couponId", "slot"}))
public class CouponStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long couponId;

    private int slot;

    private int totalQuantity;

    private int issuedQuantity;

    protected CouponStock() {
    }

    public CouponStock(Long couponId, int slot, int totalQuantity) {
        this.couponId = couponId;
        this.slot = slot;
        this.totalQuantity = totalQuantity;
    }
}
