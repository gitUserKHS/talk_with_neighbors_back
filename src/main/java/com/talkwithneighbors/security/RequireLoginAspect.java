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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class RequireLoginAspect {

    private final SessionValidationService sessionValidationService;

    @Before("@annotation(com.talkwithneighbors.security.RequireLogin) || @within(com.talkwithneighbors.security.RequireLogin)")
    public void beforeMethodExecution(JoinPoint joinPoint) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes)) {
            log.warn("[RequireLoginAspect] Not a ServletRequestAttributes context, skipping UserSession injection.");
            return;
        }
        HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
        
        UserSession userSession = null;

        // 1. HttpServletRequest attribute 에서 UserSession 가져오기 (AuthInterceptor가 설정한 값)
        Object userSessionAttr = request.getAttribute("USER_SESSION");
        if (userSessionAttr instanceof UserSession) {
            userSession = (UserSession) userSessionAttr;
            log.debug("[RequireLoginAspect] UserSession found in request attribute: {}", userSession.getUserIdStr());
        } else {
            // 2. Request attribute에 없으면 (이론상 AuthInterceptor를 통과했다면 있어야 함)
            //    혹시 모를 경우 대비하여 HttpSession 확인 (BaseController나 테스트 환경 고려)
            HttpSession httpSession = request.getSession(false);
            if (httpSession != null) {
                Object sessionAttrInHttpSession = httpSession.getAttribute("USER_SESSION");
                if (sessionAttrInHttpSession instanceof UserSession) {
                    userSession = (UserSession) sessionAttrInHttpSession;
                    log.debug("[RequireLoginAspect] UserSession found in HttpSession attribute: {}", userSession.getUserIdStr());
                }
            }

            // 3. 그래도 없으면 헤더에서 직접 읽어와서 검증 (최후의 수단, AuthInterceptor에서 이미 처리했어야 함)
            if (userSession == null) {
                String sessionIdFromHeader = request.getHeader("X-Session-Id");
                if (sessionIdFromHeader != null && !sessionIdFromHeader.isEmpty()) {
                    log.warn("[RequireLoginAspect] UserSession not found in attributes/session, attempting to validate from header (SHOULD NOT HAPPEN if AuthInterceptor ran): {}", sessionIdFromHeader);
                    try {
                        // 쉼표 처리 등은 SessionValidationService 내부에서 처리될 것으로 가정
                        userSession = sessionValidationService.validateSession(sessionIdFromHeader);
                    } catch (RuntimeException e) {
                        log.error("[RequireLoginAspect] Header session validation failed: {}", e.getMessage());
                        // 여기서 예외를 던지면 요청이 중단됨. AuthInterceptor에서 이미 처리했어야 함.
                        throw e; 
                    }
                } else {
                    log.error("[RequireLoginAspect] UserSession not found in attributes/session and no X-Session-Id header.");
                    // AuthInterceptor가 이미 401을 처리했어야 함. Aspect에서는 추가적인 예외 발생보다는 경고 로깅.
                    // 또는 상황에 따라 여기서도 예외를 발생시켜 접근을 막을 수 있음.
                    // throw new RuntimeException("UserSession not available and no session ID in header.");
                    return; // UserSession 주입 불가, 메서드 실행은 계속될 수 있으나 UserSession 파라미터는 null
                }
            }
        }

        // UserSession이 성공적으로 구해졌는지 최종 확인
        if (userSession == null || userSession.getUserId() == null) {
            log.error("[RequireLoginAspect] Failed to obtain a valid UserSession for injection.");
            // UserSession 파라미터에 null이 주입되거나, 여기서 예외를 던져 요청을 막을 수 있음.
            // 예를 들어: throw new RuntimeException("Valid UserSession could not be obtained for injection.");
            return; // 주입할 UserSession이 없음
        }

        // 메서드 파라미터에 UserSession 타입이 있으면 주입
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType().equals(UserSession.class)) {
                log.debug("[RequireLoginAspect] Injecting UserSession into parameter {}: {}", parameters[i].getName(), userSession.getUserIdStr());
                args[i] = userSession;
                break; // UserSession 파라미터는 하나만 있다고 가정
            }
        }
    }
}