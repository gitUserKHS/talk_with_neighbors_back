package com.talkwithneighbors.config;

import com.talkwithneighbors.interceptor.SessionCookieInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionCookieInterceptor())
                .addPathPatterns("/**");  // 모든 경로에 적용
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 요청 경로에 대해
                .allowedOrigins("http://localhost:3000") // 프론트엔드 URL
                .allowedMethods("*") // 모든 HTTP 메소드 허용
                .allowedHeaders("*") // 모든 헤더 허용
                .exposedHeaders("X-Session-Id") // 클라이언트에서 접근 가능한 응답 헤더
                .allowCredentials(true) // 인증 정보 허용
                .maxAge(3600); // 1시간 동안 pre-flight 요청 결과 캐싱
    }
} 