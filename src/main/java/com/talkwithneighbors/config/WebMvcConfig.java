package com.talkwithneighbors.config;

import com.talkwithneighbors.interceptor.SessionCookieInterceptor;
import com.talkwithneighbors.security.UserSessionArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final UserSessionArgumentResolver userSessionArgumentResolver;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods}")
    private String allowedMethods;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionCookieInterceptor())
                .addPathPatterns("/**");  // 모든 경로에 적용
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userSessionArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 요청 경로에 대해
                .allowedOrigins(split(allowedOrigins))
                .allowedMethods(split(allowedMethods))
                .allowedHeaders("*") // 모든 헤더 허용
                .exposedHeaders("X-Session-Id") // 클라이언트에서 접근 가능한 응답 헤더
                .allowCredentials(true) // 인증 정보 허용
                .maxAge(3600); // 1시간 동안 pre-flight 요청 결과 캐싱
    }

    private String[] split(String values) {
        return values.split("\\s*,\\s*");
    }
}
