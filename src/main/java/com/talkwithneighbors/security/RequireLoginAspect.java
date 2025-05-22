package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Aspect
@Component
@RequiredArgsConstructor
public class RequireLoginAspect {

    private final SessionValidationService sessionValidationService;

    @Before("@annotation(com.talkwithneighbors.security.RequireLogin) || @within(com.talkwithneighbors.security.RequireLogin)")
    public void beforeMethodExecution(JoinPoint joinPoint) {
        // Skip non-HTTP contexts (e.g., WebSocket handlers)
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes)) {
            return;
        }
        HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        String sessionId = request.getHeader("X-Session-Id");
        UserSession userSession = sessionValidationService.validateSession(sessionId);

        // 메서드 파라미터에 UserSession 타입이 있으면 주입
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(UserSession.class)) {
                // userId가 null이면 로그 출력 및 예외 발생
                if (userSession.getUserId() == null) {
                    System.err.println("[RequireLoginAspect] userId is null! userIdStr=" + userSession.getUserIdStr() + ", session=" + userSession);
                    throw new RuntimeException("세션에서 userId를 찾을 수 없습니다. userIdStr=" + userSession.getUserIdStr());
                }
                args[i] = userSession;
            }
        }
    }
}