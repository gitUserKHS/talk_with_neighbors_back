package com.talkwithneighbors.auth.nickname;

import com.talkwithneighbors.exception.AuthException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class NicknameException extends AuthException {
    private final String code;

    public NicknameException(String code, String message, HttpStatus status) {
        super(message, status);
        this.code = code;
    }
}
