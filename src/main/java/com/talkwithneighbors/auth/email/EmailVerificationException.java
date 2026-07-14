package com.talkwithneighbors.auth.email;

import com.talkwithneighbors.exception.AuthException;
import org.springframework.http.HttpStatus;

public class EmailVerificationException extends AuthException {
    private final String code;
    private final Long retryAfterSeconds;

    public EmailVerificationException(String message, HttpStatus status) {
        this("email_verification_error", message, status, null, null);
    }

    public EmailVerificationException(String message, HttpStatus status, Throwable cause) {
        this("email_verification_error", message, status, null, cause);
    }

    public EmailVerificationException(String code, String message, HttpStatus status) {
        this(code, message, status, null, null);
    }

    public EmailVerificationException(String code, String message, HttpStatus status, Long retryAfterSeconds) {
        this(code, message, status, retryAfterSeconds, null);
    }

    public EmailVerificationException(
            String code, String message, HttpStatus status, Long retryAfterSeconds, Throwable cause) {
        super(message, status);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        if (cause != null) initCause(cause);
    }

    public String getCode() { return code; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
}
