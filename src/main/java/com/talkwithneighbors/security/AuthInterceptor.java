package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionValidationService sessionValidationService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        log.debug("[AuthInterceptor] preHandle started for URI: {} , Method: {}", request.getRequestURI(), request.getMethod());

        // OPTIONS 요청은 통과
        if (request.getMethod().equals("OPTIONS")) {
            log.debug("[AuthInterceptor] OPTIONS request, allowing.");
            return true;
        }

        boolean chatMediaRequest = isChatMediaRequest(request);
        if (chatMediaRequest) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        }

        // 일반 정적 리소스는 통과하지만 채팅 첨부 파일은 항상 인증한다.
        if (!(handler instanceof HandlerMethod) && !chatMediaRequest) {
            log.debug("[AuthInterceptor] Handler is not HandlerMethod, allowing. Handler: {}", handler.getClass().getName());
            return true;
        }

        RequireLogin requireLogin = null;
        if (handler instanceof HandlerMethod handlerMethod) {
            requireLogin = handlerMethod.getMethodAnnotation(RequireLogin.class);
            if (requireLogin == null) {
                requireLogin = handlerMethod.getBeanType().getAnnotation(RequireLogin.class);
            }
        }

        // @RequireLogin 어노테이션이 없으면 통과
        if (requireLogin == null && !chatMediaRequest) {
            log.debug("[AuthInterceptor] No @RequireLogin annotation, allowing.");
            return true;
        }
        log.debug("[AuthInterceptor] Protected handler or chat media request, proceeding with authentication.");

        UserSession userSession = null;
        String sessionId = null;

        // 1. 테스트 환경 등에서 HttpSession에 직접 설정된 UserSession 확인
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            log.debug("[AuthInterceptor] HttpSession is not null. Session ID from httpSession.getId(): {}", httpSession.getId());
            Object sessionAttr = httpSession.getAttribute("USER_SESSION");
            if (sessionAttr != null) {
                log.debug("[AuthInterceptor] USER_SESSION attribute type: {}", sessionAttr.getClass().getName());
                if (sessionAttr instanceof UserSession) {
                    userSession = (UserSession) sessionAttr;
                    log.debug("[AuthInterceptor] UserSession found in HttpSession. UserId: {}", (userSession.getUserId() != null ? userSession.getUserId().toString() : "null"));
                } else {
                    log.warn("[AuthInterceptor] USER_SESSION attribute is not an instance of UserSession. Actual type: {}", sessionAttr.getClass().getName());
                }
            } else {
                log.debug("[AuthInterceptor] USER_SESSION attribute is null in HttpSession.");
            }
        } else {
            log.debug("[AuthInterceptor] HttpSession is null.");
        }

        // 2. HttpSession에 UserSession이 없으면, X-Session-Id 헤더 확인
        if (userSession == null) {
            log.debug("[AuthInterceptor] UserSession not found in HttpSession, checking X-Session-Id header.");
            sessionId = request.getHeader("X-Session-Id");
            if ((sessionId == null || sessionId.isBlank()) && request.getCookies() != null) {
                for (Cookie cookie : request.getCookies()) {
                    if ("TWN_SESSION".equals(cookie.getName())) {
                        sessionId = cookie.getValue();
                        break;
                    }
                }
            }
            if (sessionId != null && !sessionId.isEmpty()) {
                if (sessionId.contains(",")) {
                    String originalSessionId = sessionId;
                    sessionId = sessionId.split(",")[0].trim();
                    log.info("[AuthInterceptor] Multiple session IDs detected from header. Using first: {} (from {})", sessionId, originalSessionId);
                }
                log.debug("[AuthInterceptor] Session ID from header: {}", sessionId);
            } else {
                log.warn("[AuthInterceptor] No UserSession in HttpSession and no X-Session-Id header provided for request to {}. Responding 401.", request.getRequestURI());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
        }

        try {
            if (userSession == null && sessionId != null) { // 헤더에서 가져온 sessionId로 검증
                log.debug("[AuthInterceptor] Validating session from header ID: {}", sessionId);
                 userSession = sessionValidationService.validateSession(sessionId);
                 log.debug("[AuthInterceptor] UserSession from header validation. UserId: {}", (userSession != null && userSession.getUserId() != null ? userSession.getUserId().toString() : "null or UserSession is null"));
            }

            if (userSession == null || userSession.getUserId() == null) {
                log.warn("[AuthInterceptor] UserSession is null or UserId is null after all checks. URI: {}. Responding 401.", request.getRequestURI());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }

            request.setAttribute("USER_SESSION", userSession);
            log.debug("[AuthInterceptor] Successfully validated. User ID: {}. Setting USER_SESSION attribute.", userSession.getUserId());
            return true;
        } catch (RuntimeException e) { 
            log.error("[AuthInterceptor] Session validation failed for URI {}: {}. Responding 401.", request.getRequestURI(), e.getMessage(), e);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
    }

    private boolean isChatMediaRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
        return "/uploads/chat".equals(path) || path.startsWith("/uploads/chat/");
    }
}
