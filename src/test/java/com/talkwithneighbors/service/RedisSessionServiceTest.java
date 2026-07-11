package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisSessionServiceTest {

    private RedisTemplate<String, String> redisTemplate;
    private UserSessionRepository userSessionRepository;
    private RedisSessionService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        userSessionRepository = mock(UserSessionRepository.class);
        service = new RedisSessionService(
                redisTemplate,
                new ObjectMapper(),
                userSessionRepository,
                mock(UserRepository.class),
                mock(UserOnlineStatusListener.class)
        );
    }

    @Test
    void rejectsRedisOnlySessionWhenDatabaseSessionDoesNotExist() {
        when(userSessionRepository.findById("stale-session")).thenReturn(Optional.empty());

        assertNull(service.getSession("stale-session"));

        verify(userSessionRepository).findById("stale-session");
        verifyNoInteractions(redisTemplate);
    }
}
