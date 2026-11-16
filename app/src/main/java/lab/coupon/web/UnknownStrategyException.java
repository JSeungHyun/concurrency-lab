package lab.coupon.web;

import java.util.Set;

public class UnknownStrategyException extends RuntimeException {

    public UnknownStrategyException(String strategy, Set<String> available) {
        super("unknown strategy '" + strategy + "', available: " + available);
    }
}
