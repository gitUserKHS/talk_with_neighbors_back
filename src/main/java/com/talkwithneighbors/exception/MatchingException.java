package com.talkwithneighbors.exception;

import org.springframework.http.HttpStatus;

public class MatchingException extends RuntimeException {
    private final HttpStatus status;
    /** 클라이언트가 로케일별 문구로 바꿔 보여줄 안정적인 오류 코드(UPPER_SNAKE). 없으면 null. */
    private final String code;

    public MatchingException(String message, HttpStatus status) {
        this(message, status, null);
    }

    public MatchingException(String message, HttpStatus status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
