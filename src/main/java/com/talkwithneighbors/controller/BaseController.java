package com.talkwithneighbors.controller;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.security.SessionAuthenticationFilter;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

public abstract class BaseController {

    @Autowired
    protected SessionValidationService sessionValidationService;

    @Autowired
    protected UserService userService;

    protected User getCurrentUser(HttpServletRequest request) {
        Object authenticated = request.getAttribute(
                SessionAuthenticationFilter.USER_SESSION_ATTRIBUTE);
        if (authenticated instanceof UserSession userSession && userSession.getUserId() != null) {
            return userService.getUserById(userSession.getUserId());
        }

        String sessionId = sessionCookie(request);
        if (sessionId != null) {
            UserSession userSession = sessionValidationService.validateSession(sessionId);
            if (userSession != null && userSession.getUserId() != null) {
                return userService.getUserById(userSession.getUserId());
            }
        }

        throw new AuthException("세션이 없어요. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
    }

    private String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SessionAuthenticationFilter.SESSION_COOKIE.equals(cookie.getName())
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
