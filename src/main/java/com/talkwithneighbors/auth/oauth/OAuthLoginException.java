package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.exception.AuthException;
import org.springframework.http.HttpStatus;

public class OAuthLoginException extends AuthException {
    private final String code;

    public OAuthLoginException(String code, String message, HttpStatus status) {
        super(message, status);
        this.code = code;
    }

    public String getCode() { return code; }
}
