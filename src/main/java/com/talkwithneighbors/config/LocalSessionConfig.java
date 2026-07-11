package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Profile("local")
@EnableSpringHttpSession
public class LocalSessionConfig {
    @Bean
    @Primary
    public SessionRepository<?> localSessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }
}
