package com.talkwithneighbors.controller;

import com.talkwithneighbors.security.UserSession;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestConfiguration
public class MockArgumentResolverConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(MockArgumentResolverConfig.class);

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(0, new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                boolean supports = parameter.getParameterType().equals(UserSession.class);
                log.info("[MockArgumentResolver] supportsParameter for {}: {}", parameter.getParameterType().getSimpleName(), supports);
                return supports;
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                log.info("[MockArgumentResolver] Resolving argument for UserSession");
                // 항상 고정된 테스트 UserSession 반환
                return new UserSession(1L, "testuser", "test@test.com", "testnick");
            }
        });
    }
}
