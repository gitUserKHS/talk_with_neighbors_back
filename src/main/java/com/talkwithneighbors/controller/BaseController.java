package com.talkwithneighbors.controller;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@Slf4j
public abstract class BaseController {

    @Autowired
    protected SessionValidationService sessionValidationService;

    @Autowired
    protected UserService userService;

    protected String extractSessionId(HttpServletRequest request) {
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("No session ID provided in request to {}", request.getRequestURI());
            throw new AuthException("세션이 없어요. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
        }

        if (sessionId.contains(",")) {
            sessionId = sessionId.split(",")[0].trim();
        }

        return sessionId;
    }

    protected User getCurrentUser(HttpServletRequest request) {
        String headerSessionId = request.getHeader("X-Session-Id");
        if (headerSessionId != null && !headerSessionId.isBlank()) {
            UserSession userSession = sessionValidationService.validateSession(extractSessionId(request));
            if (userSession == null) {
                throw new AuthException("세션이 만료되었어요. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
            }
            if (userSession.getUserId() == null) {
                throw new AuthException("세션 사용자 정보가 올바르지 않아요.", HttpStatus.UNAUTHORIZED);
            }
            return userService.getUserById(userSession.getUserId());
        }

        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            Object sessionAttr = httpSession.getAttribute("USER_SESSION");
            if (sessionAttr instanceof UserSession userSession) {
                if (userSession.getUserId() == null) {
                    throw new AuthException("세션 사용자 정보가 올바르지 않아요.", HttpStatus.UNAUTHORIZED);
                }
                return userService.getUserById(userSession.getUserId());
            }
            Long reflectedUserId = extractUserIdFromSessionObject(sessionAttr);
            if (reflectedUserId != null) {
                return userService.getUserById(reflectedUserId);
            }
        }

        throw new AuthException("세션이 없어요. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
    }

    private Long extractUserIdFromSessionObject(Object sessionAttr) {
        if (sessionAttr == null) {
            return null;
        }
        try {
            Object value = sessionAttr.getClass().getMethod("getUserId").invoke(sessionAttr);
            if (value instanceof Long userId) {
                return userId;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
