package com.talkwithneighbors.service;

import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionValidationService {

    static final String SESSION_EXPIRED_CODE = "SESSION_EXPIRED";

    private final RedisSessionService redisSessionService;

    public UserSession validateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            log.debug("Session validation failed: Session ID is null or empty");
            throw sessionExpired();
        }

        // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();

        UserSession userSession = redisSessionService.getSession(actualSessionId);
        if (userSession == null) {
            log.debug("Session validation failed because the credential is missing or expired.");
            throw sessionExpired();
        }
        return userSession;
    }

    /** Validates without extending TTL or writing presence for each STOMP frame. */
    public UserSession validateSessionWithoutTouch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw sessionExpired();
        }
        UserSession userSession = redisSessionService.getSessionWithoutTouch(sessionId);
        if (userSession == null || userSession.getUserId() == null) {
            throw sessionExpired();
        }
        return userSession;
    }

    private AuthException sessionExpired() {
        return new AuthException("세션이 만료됐어요. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED, SESSION_EXPIRED_CODE);
    }
}
