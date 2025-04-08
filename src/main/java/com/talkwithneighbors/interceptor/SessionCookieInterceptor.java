package com.talkwithneighbors.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SessionCookieInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 클라이언트가 보낸 세션 ID 확인
        String clientSessionId = request.getHeader("X-Session-Id");
        if (clientSessionId != null && !clientSessionId.isEmpty()) {
            log.debug("클라이언트에서 전송된 세션 ID: {}", clientSessionId);
            
            // 쉼표로 구분된 세션 ID가 있을 경우 첫 번째 세션 ID만 사용
            if (clientSessionId.contains(",")) {
                clientSessionId = clientSessionId.split(",")[0].trim();
                log.debug("쉼표로 구분된 세션 ID에서 첫 번째 ID 추출: {}", clientSessionId);
            }
            
            // 세션 ID를 요청 속성에 저장 (컨트롤러에서 사용 가능)
            request.setAttribute("CLIENT_SESSION_ID", clientSessionId);
        }
        
        // 로그인/회원가입 요청은 AuthController에서 처리하므로 여기서는 처리하지 않음
        if (request.getRequestURI().contains("/api/auth/login") || 
            request.getRequestURI().contains("/api/auth/register")) {
            log.debug("로그인/회원가입 요청 - 세션 ID는 AuthController에서 처리");
            return true;
        }
        
        return true;
    }
} 