package lab.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;

@Entity
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private int totalQuantity;

    private int issuedQuantity;

    // 낙관적 락 전략에서만 의미가 있다. 다른 전략은 벌크 UPDATE 로 이 값을 건드리지 않는다.
    @Version
    private long version;

    protected Coupon() {
    }

    public Coupon(String name, int totalQuantity) {
        this.name = name;
        this.totalQuantity = totalQuantity;
    }

    public void issue() {
        if (issuedQuantity >= totalQuantity) {
            throw new IssueException(IssueResult.SOLD_OUT);
        }
        issuedQuantity++;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedQuantity() {
        return issuedQuantity;
    }
}
