package com.talkwithneighbors.auth.nickname;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NicknameExceptionHandler {
    @ExceptionHandler(NicknameException.class)
    ResponseEntity<ErrorResponse> handle(NicknameException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
