package com.talkwithneighbors.service;

import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.security.UserSession;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionValidationService {
    
    private final RedisSessionService redisSessionService;

    public UserSession validateSession(HttpSession session) {
        if (session == null) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }

        String sessionId = (String) session.getAttribute("sessionId");
        if (sessionId == null) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }

        try {
            return redisSessionService.getSession(sessionId);
        } catch (Exception e) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }
    }
} 