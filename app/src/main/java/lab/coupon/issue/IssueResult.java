package lab.coupon.issue;

import org.springframework.http.HttpStatus;

public enum IssueResult {
    ISSUED(HttpStatus.OK),
    SOLD_OUT(HttpStatus.CONFLICT),
    DUPLICATE(HttpStatus.CONFLICT),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    RETRY_EXHAUSTED(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    IssueResult(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
