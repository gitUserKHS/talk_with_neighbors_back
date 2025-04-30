package com.talkwithneighbors.controller;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.service.UserService;
import jakarta.servlet.http.HttpSession;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 모든 컨트롤러의 기본 클래스
 * 세션 ID 추출 및 사용자 정보 가져오기 기능 제공
 */
@Slf4j
public abstract class BaseController {

    @Autowired
    protected SessionValidationService sessionValidationService;

    @Autowired
    protected UserService userService;

    /**
     * HTTP 요청에서 세션 ID를 추출합니다.
     * @param request HTTP 요청
     * @return 추출된 세션 ID
     */
    protected String extractSessionId(HttpServletRequest request) {
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("No session ID provided in request to {}", request.getRequestURI());
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
        if (sessionId.contains(",")) {
            String originalSessionId = sessionId;
            sessionId = sessionId.split(",")[0].trim();
            log.info("Multiple session IDs detected. Using first: {} (from {})", sessionId, originalSessionId);
        }
        
        return sessionId;
    }

    /**
     * HTTP 요청에서 현재 로그인한 사용자 정보를 가져옵니다.
     * @param request HTTP 요청
     * @return 사용자 엔티티
     */
    protected User getCurrentUser(HttpServletRequest request) {
        // 1. 테스트 환경(HttpSession)에 USER_SESSION이 있으면 우선 사용
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            Object sessionAttr = httpSession.getAttribute("USER_SESSION");
            if (sessionAttr instanceof UserSession) {
                UserSession userSession = (UserSession) sessionAttr;
                if (userSession.getUserId() == null) {
                    log.error("UserSession userId is null for session attribute: {}", userSession);
                    throw new RuntimeException("User ID is null in session - user is not properly authenticated");
                }
                return userService.getUserById(userSession.getUserId());
            }
        }
        // 2. 실제 환경: 헤더 기반 세션 처리
        String sessionId = extractSessionId(request);
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        if (userSession == null) {
            log.error("UserSession is null");
            throw new RuntimeException("User session is null - user is not authenticated");
        }
        if (userSession.getUserId() == null) {
            log.error("UserSession userId is null for session: {}", userSession);
            throw new RuntimeException("User ID is null in session - user is not properly authenticated");
        }
        return userService.getUserById(userSession.getUserId());
    }
} 