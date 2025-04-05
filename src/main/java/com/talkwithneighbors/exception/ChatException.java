package com.talkwithneighbors.exception;

import org.springframework.http.HttpStatus;

public class ChatException extends RuntimeException {
    private final HttpStatus status;

    public ChatException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
} 