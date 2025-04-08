package com.talkwithneighbors.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Aspect
@Component
public class RequireLoginAspect {

    @Before("@annotation(com.talkwithneighbors.security.RequireLogin) || @within(com.talkwithneighbors.security.RequireLogin)")
    public void beforeMethodExecution(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        UserSession userSession = (UserSession) request.getAttribute("USER_SESSION");

        // 메서드 파라미터에 UserSession 타입이 있으면 주입
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(UserSession.class)) {
                args[i] = userSession;
            }
        }
    }
} 