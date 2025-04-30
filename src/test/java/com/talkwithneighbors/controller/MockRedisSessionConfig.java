package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.RedisSessionService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@TestConfiguration
@Profile("test")
public class MockRedisSessionConfig {
    @Bean
    public RedisSessionService redisSessionService() {
        return Mockito.mock(RedisSessionService.class);
    }
}
