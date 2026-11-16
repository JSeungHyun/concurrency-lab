package lab.coupon.web;

import java.util.Map;
import lab.coupon.issue.IssueException;
import lab.coupon.issue.IssueResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IssueException.class)
    public ResponseEntity<Map<String, Object>> handleIssue(IssueException e) {
        return ResponseEntity.status(e.result().status()).body(Map.of("result", e.result()));
    }

    // 사전 중복 검사를 동시에 통과한 요청은 유니크 제약에서 걸린다
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("result", IssueResult.DUPLICATE));
    }

    @ExceptionHandler(UnknownStrategyException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownStrategy(UnknownStrategyException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
