package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
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
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String sessionId = request.getHeader("X-Session-Id");
        if (sessionId == null) {
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        return sessionValidationService.validateSession(sessionId);
    }
}
