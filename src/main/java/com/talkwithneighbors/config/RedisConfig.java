package com.talkwithneighbors.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Redis는 Spring Boot가 자동 구성하는 {@code StringRedisTemplate}만 사용한다.
 * 이전에 여기서 만들던 {@code RedisTemplate<String, Object>} 빈은 어디에서도 주입되지 않았고
 * 트랜잭션 지원까지 켜져 있어 제거했다. 이 클래스는 삭제해도 무방하다.
 */
@Configuration
@Profile("!local")
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {
}
