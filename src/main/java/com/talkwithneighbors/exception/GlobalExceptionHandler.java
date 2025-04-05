package com.talkwithneighbors.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e) {
        log.error("Auth error: {} at {}", e.getMessage(), e.getStackTrace()[0]);
        return ResponseEntity
                .status(e.getStatus())
                .body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(DataAccessResourceFailureException e) {
        log.error("Database error: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Database error occurred"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception e) {
        log.error("Unexpected error: {} at {}", e.getMessage(), e.getStackTrace()[0]);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("An unexpected error occurred"));
    }

    record ErrorResponse(String message) {}
} 