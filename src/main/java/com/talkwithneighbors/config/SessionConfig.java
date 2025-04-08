package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionConfig {
    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setSameSite("Lax");  // AuthController와 동일하게 Lax로 설정
        serializer.setUseSecureCookie(false);  // 개발 환경에서는 false로 설정
        serializer.setCookieName("JSESSIONID");  // AuthController와 동일한 쿠키 이름
        serializer.setCookiePath("/");  // 모든 경로에서 접근 가능
        serializer.setUseHttpOnlyCookie(true);  // HttpOnly 설정
        return serializer;
    }
} 