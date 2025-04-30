package com.talkwithneighbors.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talkwithneighbors.security.AuthInterceptor;

/**
 * 테스트를 위한 웹 설정 클래스
 * AuthInterceptor를 적용하지 않습니다
 */
@TestConfiguration
@EnableWebMvc
public class TestWebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    public TestWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 테스트에도 실제 interceptor 체인에 등록
        registry.addInterceptor(authInterceptor);
    }
}