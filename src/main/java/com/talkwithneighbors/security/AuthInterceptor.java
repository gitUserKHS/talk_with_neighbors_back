package com.talkwithneighbors.security;

import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.service.RedisSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final RedisSessionService redisSessionService;

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

        // 세션 ID 확인
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }
        
        String sessionId = (String) session.getAttribute("sessionId");
        if (sessionId == null) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }
        
        UserSession userSession = redisSessionService.getSession(sessionId);

        if (userSession == null) {
            throw new AuthException("로그인이 필요합니다.", HttpStatus.UNAUTHORIZED);
        }

        // 요청 속성에 사용자 정보 추가
        request.setAttribute("USER_SESSION", userSession);
        return true;
    }
} 