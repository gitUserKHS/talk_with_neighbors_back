package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserSessionArgumentResolver implements HandlerMethodArgumentResolver {

    private final SessionValidationService sessionValidationService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(UserSession.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            log.warn("[UserSessionArgumentResolver] HttpServletRequest is null, cannot resolve UserSession.");
            return null;
        }

        Object userSessionAttr = request.getAttribute("USER_SESSION");
        if (userSessionAttr instanceof UserSession) {
            log.debug("[UserSessionArgumentResolver] UserSession found in request attribute.");
            return userSessionAttr;
        }
        
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            Object sessionAttrInHttpSession = httpSession.getAttribute("USER_SESSION");
            if (sessionAttrInHttpSession instanceof UserSession) {
                log.debug("[UserSessionArgumentResolver] UserSession found in HttpSession attribute.");
                return sessionAttrInHttpSession;
            }
        }

        log.warn("[UserSessionArgumentResolver] UserSession not found in attributes or HttpSession, falling back to header (SHOULD NOT HAPPEN if AuthInterceptor ran and succeeded).");
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isEmpty()) {
            log.error("[UserSessionArgumentResolver] X-Session-Id header is missing or empty.");
            throw new RuntimeException("세션 ID가 헤더에 없습니다. @RequireLogin이 붙은 API는 인증이 필요합니다.");
        }
        
        if (sessionId.contains(",")) {
            sessionId = sessionId.split(",")[0].trim();
            log.info("[UserSessionArgumentResolver] Multiple session IDs in header, using first: {}", sessionId);
        }

        try {
            return sessionValidationService.validateSession(sessionId);
        } catch (RuntimeException e) {
            log.error("[UserSessionArgumentResolver] Session validation failed for session ID from header: {}. Error: {}", sessionId, e.getMessage());
            throw e;
        }
    }
}
