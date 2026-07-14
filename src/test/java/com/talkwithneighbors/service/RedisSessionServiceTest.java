package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserSessionRepository;
import com.talkwithneighbors.entity.Session;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.talkwithneighbors.websocket.AuthenticatedWebSocketSessionRegistry;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisSessionServiceTest {

    private RedisTemplate<String, String> redisTemplate;
    private UserSessionRepository userSessionRepository;
    private AuthenticatedWebSocketSessionRegistry webSocketSessions;
    private RedisSessionService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        userSessionRepository = mock(UserSessionRepository.class);
        webSocketSessions = mock(AuthenticatedWebSocketSessionRegistry.class);
        service = new RedisSessionService(
                redisTemplate,
                new ObjectMapper(),
                userSessionRepository,
                mock(UserRepository.class),
                mock(UserOnlineStatusListener.class),
                webSocketSessions
        );
    }

    @Test
    void rejectsRedisOnlySessionWhenDatabaseSessionDoesNotExist() {
        when(userSessionRepository.findById("stale-session")).thenReturn(Optional.empty());

        assertNull(service.getSession("stale-session"));

        verify(userSessionRepository).findById("stale-session");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void expiredDatabaseSessionRevokesItsOpenWebSocketTransports() {
        Session expired = new Session();
        expired.setSessionId("expired-session");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(userSessionRepository.findById("expired-session"))
                .thenReturn(Optional.of(expired));

        assertNull(service.getSession("expired-session"));

        verify(userSessionRepository).deleteById("expired-session");
        verify(webSocketSessions).closeSessionsForCredential("expired-session");
    }

    @Test
    void websocketValidationReadsSessionWithoutTouchingTtlOrPresence() {
        User user = new User();
        user.setId(7L);
        user.setUsername("neighbor");
        user.setEmail("hidden@example.test");
        Session session = new Session();
        session.setSessionId("read-only-session");
        session.setUser(user);
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(userSessionRepository.findById("read-only-session"))
                .thenReturn(Optional.of(session));

        assertEquals(7L, service.getSessionWithoutTouch("read-only-session").getUserId());

        verify(userSessionRepository, never()).save(org.mockito.ArgumentMatchers.any(Session.class));
        verifyNoInteractions(redisTemplate, webSocketSessions);
    }
}
