package com.talkwithneighbors.security;

import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
public class UserSessionArgumentResolver implements HandlerMethodArgumentResolver {

    private final SessionValidationService sessionValidationService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(UserSession.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new IllegalStateException("HTTP request is unavailable");
        }

        Object authenticated = request.getAttribute(
                SessionAuthenticationFilter.USER_SESSION_ATTRIBUTE);
        if (authenticated instanceof UserSession userSession) {
            return userSession;
        }

        String sessionId = sessionCookie(request);
        if (sessionId == null) {
            throw new AuthException(
                    "로그인이 필요해.",
                    HttpStatus.UNAUTHORIZED);
        }
        return sessionValidationService.validateSession(sessionId);
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
