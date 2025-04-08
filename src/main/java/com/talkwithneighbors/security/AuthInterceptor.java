package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        // OPTIONS 요청은 통과
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        // 핸들러 메소드가 아닌 경우 통과 (정적 리소스 등)
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireLogin requireLogin = handlerMethod.getMethodAnnotation(RequireLogin.class);
        if (requireLogin == null) {
            requireLogin = handlerMethod.getBeanType().getAnnotation(RequireLogin.class);
        }

        // @RequireLogin 어노테이션이 없으면 통과
        if (requireLogin == null) {
            return true;
        }

        try {
            // 세션 검증 및 사용자 정보 설정
            String sessionId = request.getHeader("X-Session-Id");
            
            // 세션 ID가 없는 경우
            if (sessionId == null || sessionId.isEmpty()) {
                log.warn("No session ID provided in request to {}", request.getRequestURI());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
            
            // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
            if (sessionId.contains(",")) {
                String originalSessionId = sessionId;
                sessionId = sessionId.split(",")[0].trim();
                log.info("Multiple session IDs detected. Using first: {} (from {})", sessionId, originalSessionId);
            }
            
            log.debug("Validating session ID: {}", sessionId);
            UserSession userSession = sessionValidationService.validateSession(sessionId);
            request.setAttribute("USER_SESSION", userSession);
            return true;
        } catch (RuntimeException e) {
            log.error("Session validation failed: {}", e.getMessage());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
    }
} 