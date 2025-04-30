package com.talkwithneighbors.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 환경을 위한 Spring 구성 클래스
 */
@TestConfiguration
@EnableSpringHttpSession
public class TestConfig {
    
    /**
     * 테스트용 메모리 기반 세션 저장소
     */
    @Bean
    @Primary
    public SessionRepository<?> sessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }
} 