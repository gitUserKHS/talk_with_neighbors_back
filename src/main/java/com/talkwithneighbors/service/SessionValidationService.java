package com.talkwithneighbors.service;

import com.talkwithneighbors.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionValidationService {
    
    private final RedisSessionService redisSessionService;
    
    public UserSession validateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        
        log.info("Validating session with ID: {}", actualSessionId);
        
        try {
            UserSession userSession = redisSessionService.getSession(actualSessionId);
            if (userSession == null) {
                log.error("Session not found for ID: {}", actualSessionId);
                throw new RuntimeException("세션이 만료되었습니다. 다시 로그인해주세요.");
            }
            log.info("Session validated successfully for user: {}", userSession.getUsername());
            return userSession;
        } catch (RuntimeException e) {
            log.error("Session validation failed for ID: {}", actualSessionId, e);
            throw new RuntimeException("세션이 만료되었습니다. 다시 로그인해주세요.");
        }
    }
} 