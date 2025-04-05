package com.talkwithneighbors.exception;

import org.springframework.http.HttpStatus;

public class MatchingException extends RuntimeException {
    private final HttpStatus status;

    public MatchingException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
} 