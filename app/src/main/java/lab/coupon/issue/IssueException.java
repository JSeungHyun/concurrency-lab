package lab.coupon.issue;

public class IssueException extends RuntimeException {

    private final IssueResult result;

    public IssueException(IssueResult result) {
        super(result.name());
        this.result = result;
    }

    public IssueResult result() {
        return result;
    }
}
