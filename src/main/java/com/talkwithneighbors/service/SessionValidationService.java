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
            log.warn("Session validation failed: Session ID is null or empty");
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        
        log.info("Validating session with ID: {}", actualSessionId);
        
        try {
            UserSession userSession = redisSessionService.getSession(actualSessionId);
            if (userSession == null) {
                log.warn("Session not found or expired for ID: {}. User needs to log in again.", actualSessionId);
                throw new RuntimeException("세션이 만료되었습니다. 새로고침 후 다시 로그인해주세요.");
            }
            
            // 세션 검증 성공 시 세션 만료 시간 연장은 getSession 내부 또는 필요한 곳에서 명시적으로 처리
            // log.info("Session validated successfully for user: {}, extending session", userSession.getUsername());
            // redisSessionService.extendSession(actualSessionId); // 호출 제거
            
            return userSession;
        } catch (RuntimeException e) {
            // 이미 RuntimeException인 경우 그대로 전파
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during session validation for ID: {}", actualSessionId, e);
            throw new RuntimeException("세션 검증 중 오류가 발생했습니다. 다시 로그인해주세요.");
        }
    }
} 