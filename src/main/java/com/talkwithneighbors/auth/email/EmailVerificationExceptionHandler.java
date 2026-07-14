package com.talkwithneighbors.auth.email;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmailVerificationExceptionHandler {
    @ExceptionHandler(EmailVerificationException.class)
    ResponseEntity<ErrorResponse> handle(EmailVerificationException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(
                        exception.getCode(), exception.getMessage(), exception.getRetryAfterSeconds()));
    }

    public record ErrorResponse(String code, String message, Long retryAfterSeconds) {}
}
