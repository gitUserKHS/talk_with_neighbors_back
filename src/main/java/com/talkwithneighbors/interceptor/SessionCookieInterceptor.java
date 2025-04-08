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
        HttpSession session = request.getSession(false);
        if (session != null) {
            String cookieValue = String.format("JSESSIONID=%s; Path=/; HttpOnly; SameSite=Lax",
                    session.getId());
            response.setHeader("Set-Cookie", cookieValue);
            log.debug("세션 쿠키 설정: {}", cookieValue);
        }
        return true;
    }
} 