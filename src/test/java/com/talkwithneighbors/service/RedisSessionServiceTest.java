package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserSessionRepository;
import com.talkwithneighbors.entity.Session;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.talkwithneighbors.websocket.AuthenticatedWebSocketSessionRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisSessionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserSessionRepository userSessionRepository;
    private UserRepository userRepository;
    private AuthenticatedWebSocketSessionRegistry webSocketSessions;
    private RedisSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        userSessionRepository = mock(UserSessionRepository.class);
        userRepository = mock(UserRepository.class);
        webSocketSessions = mock(AuthenticatedWebSocketSessionRegistry.class);
        service = new RedisSessionService(
                redisTemplate,
                objectMapper,
                userSessionRepository,
                userRepository,
                mock(UserOnlineStatusListener.class),
                webSocketSessions,
                mock(ObjectProvider.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class))
        );
    }

    private User neighbor() {
        User user = new User();
        user.setId(7L);
        user.setUsername("neighbor");
        user.setEmail("hidden@example.test");
        return user;
    }

    private Session databaseSession(String sessionId, LocalDateTime lastAccessedAt) {
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setUser(neighbor());
        session.setLastAccessedAt(lastAccessedAt);
        session.setExpiresAt(lastAccessedAt.plusHours(24));
        return session;
    }

    private String trustedEntry(long lastAccessedAtEpochSecond) throws Exception {
        return trustedEntry(lastAccessedAtEpochSecond, Instant.now().getEpochSecond());
    }

    private String trustedEntry(long lastAccessedAtEpochSecond, long verifiedAtEpochSecond) throws Exception {
        return objectMapper.writeValueAsString(new RedisSessionService.SessionCacheEntry(
                7L, "neighbor", "hidden@example.test", "neighbor",
                lastAccessedAtEpochSecond, verifiedAtEpochSecond, true));
    }

    private RedisSessionService.SessionCacheEntry writtenEntry(String key) throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(key), json.capture(), anyLong(), any());
        return objectMapper.readValue(json.getValue(), RedisSessionService.SessionCacheEntry.class);
    }

    @Test
    void rejectsRedisOnlySessionWhenDatabaseSessionDoesNotExist() {
        // 과거 형식의 원시 항목에는 dbBacked 표식이 없으므로 절대 신뢰하지 않는다.
        when(valueOperations.get("session:stale-session"))
                .thenReturn("{\"userIdStr\":\"7\",\"username\":\"ghost\",\"email\":\"g@example.test\"}");
        when(userSessionRepository.findByIdWithUser("stale-session")).thenReturn(Optional.empty());

        assertNull(service.getSession("stale-session"));

        verify(userSessionRepository).findByIdWithUser("stale-session");
        verify(userSessionRepository, never()).save(any(Session.class));
        verifyNoInteractions(userRepository);
    }

    @Test
    void expiredDatabaseSessionRevokesItsOpenWebSocketTransports() {
        Session expired = new Session();
        expired.setSessionId("expired-session");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(userSessionRepository.findByIdWithUser("expired-session"))
                .thenReturn(Optional.of(expired));

        assertNull(service.getSession("expired-session"));

        verify(userSessionRepository).deleteById("expired-session");
        verify(redisTemplate).delete("session:expired-session");
        verify(webSocketSessions).closeSessionsForCredential("expired-session");
    }

    @Test
    void warmRedisHitDoesNotTouchTheDatabaseWithinFiveMinutes() throws Exception {
        when(valueOperations.get("session:warm-session"))
                .thenReturn(trustedEntry(Instant.now().getEpochSecond() - 60));
        when(redisTemplate.hasKey("online:7")).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(neighbor()));

        assertEquals(7L, service.getSession("warm-session").getUserId());
        // 같은 프로세스에서 60초 안의 두 번째 요청은 users 행도 다시 쓰지 않는다.
        assertEquals(7L, service.getSession("warm-session").getUserId());

        verifyNoInteractions(userSessionRepository);
        verify(userRepository, times(1)).findById(7L);
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void staleTouchStillUpdatesSessionsRow() throws Exception {
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        when(valueOperations.get("session:stale-touch"))
                .thenReturn(trustedEntry(Instant.now().getEpochSecond() - 600));
        when(userSessionRepository.findByIdWithUser("stale-touch"))
                .thenReturn(Optional.of(databaseSession("stale-touch", tenMinutesAgo)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(neighbor()));

        assertEquals(7L, service.getSession("stale-touch").getUserId());

        ArgumentCaptor<Session> saved = ArgumentCaptor.forClass(Session.class);
        verify(userSessionRepository).save(saved.capture());
        assertTrue(saved.getValue().getLastAccessedAt().isAfter(tenMinutesAgo));
        assertTrue(saved.getValue().getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("session:stale-touch"), anyString(), anyLong(), any());
    }

    @Test
    void recentDatabaseTouchIsNotRewrittenOnCacheMiss() {
        when(valueOperations.get("session:recent-touch")).thenReturn(null);
        when(userSessionRepository.findByIdWithUser("recent-touch"))
                .thenReturn(Optional.of(databaseSession("recent-touch", LocalDateTime.now().minusMinutes(1))));
        when(userRepository.findById(7L)).thenReturn(Optional.of(neighbor()));

        assertEquals(7L, service.getSession("recent-touch").getUserId());

        verify(userSessionRepository, never()).save(any(Session.class));
    }

    @Test
    void websocketValidationReadsSessionWithoutTouchingTtlOrPresence() throws Exception {
        // 접근 시각은 한 시간 전이지만 데이터베이스 확인은 1분 전이므로 sessions 행을 읽지 않는다.
        when(valueOperations.get("session:read-only-session"))
                .thenReturn(trustedEntry(Instant.now().getEpochSecond() - 3600, Instant.now().getEpochSecond() - 60));

        assertEquals(7L, service.getSessionWithoutTouch("read-only-session").getUserId());

        verifyNoInteractions(userSessionRepository, userRepository, webSocketSessions);
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void websocketValidationReverifiesStaleEntryWithoutTouchingTheRow() throws Exception {
        long now = Instant.now().getEpochSecond();
        LocalDateTime lastAccessed = LocalDateTime.now().minusHours(1);
        when(valueOperations.get("session:reverify"))
                .thenReturn(trustedEntry(now - 3600, now - 600));
        when(userSessionRepository.findByIdWithUser("reverify"))
                .thenReturn(Optional.of(databaseSession("reverify", lastAccessed)));

        assertEquals(7L, service.getSessionWithoutTouch("reverify").getUserId());

        verify(userSessionRepository, never()).save(any(Session.class));
        verifyNoInteractions(userRepository, webSocketSessions);
        RedisSessionService.SessionCacheEntry rewritten = writtenEntry("session:reverify");
        assertEquals(now - 3600, rewritten.lastAccessedAt());
        assertTrue(rewritten.verifiedAt() >= now);
    }

    @Test
    void websocketValidationRejectsSurvivingKeyWhoseRowWasRevoked() throws Exception {
        // 폐기 중 Redis 삭제가 실패해 남은 항목은 터치 창이 지나면 sessions 행과 대조해 거부하고 지운다.
        long now = Instant.now().getEpochSecond();
        when(valueOperations.get("session:revoked"))
                .thenReturn(trustedEntry(now - 60, now - 600));
        when(userSessionRepository.findByIdWithUser("revoked")).thenReturn(Optional.empty());

        assertNull(service.getSessionWithoutTouch("revoked"));

        verify(redisTemplate).delete("session:revoked");
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void websocketValidationRejectsSurvivingKeyWhoseRowExpired() throws Exception {
        long now = Instant.now().getEpochSecond();
        when(valueOperations.get("session:row-expired"))
                .thenReturn(trustedEntry(now - 60, now - 600));
        when(userSessionRepository.findByIdWithUser("row-expired"))
                .thenReturn(Optional.of(databaseSession("row-expired", LocalDateTime.now().minusHours(25))));

        assertNull(service.getSessionWithoutTouch("row-expired"));

        verify(redisTemplate).delete("session:row-expired");
        verify(userSessionRepository, never()).save(any(Session.class));
    }

    @Test
    void getSessionWithoutTouchFallsBackToDatabaseOnRedisMiss() {
        when(valueOperations.get("session:db-only")).thenReturn(null);
        when(userSessionRepository.findByIdWithUser("db-only"))
                .thenReturn(Optional.of(databaseSession("db-only", LocalDateTime.now().minusHours(3))));

        assertEquals(7L, service.getSessionWithoutTouch("db-only").getUserId());

        verify(userSessionRepository, never()).save(any(Session.class));
        verifyNoInteractions(userRepository, webSocketSessions);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void removeUserSessionsSucceedsWhenRedisThrows() {
        List<Session> sessions = List.of(databaseSession("s-1", LocalDateTime.now()));
        when(userSessionRepository.findByUserId(7L)).thenReturn(sessions);
        when(redisTemplate.delete(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> service.removeUserSessions("7"));

        verify(webSocketSessions).closeSessionsForCredential("s-1");
        verify(userSessionRepository).deleteAll(sessions);
    }

    @Test
    void cleanupExpiredSessionsUsesBulkDeleteAndClosesSockets() {
        when(userSessionRepository.findExpiredSessionIds(any(LocalDateTime.class)))
                .thenReturn(List.of("old-1", "old-2"));
        when(userSessionRepository.deleteExpiredBefore(any(LocalDateTime.class))).thenReturn(2);

        service.cleanupExpiredSessions();

        verify(redisTemplate).delete("session:old-1");
        verify(redisTemplate).delete("session:old-2");
        verify(webSocketSessions).closeSessionsForCredential("old-1");
        verify(webSocketSessions).closeSessionsForCredential("old-2");
        verify(userSessionRepository).deleteExpiredBefore(any(LocalDateTime.class));
        verify(userSessionRepository, never()).deleteAll(anyList());
    }

    @Test
    void refreshUserSessionsRewritesActiveEntriesWithoutRevivingOrExtending() throws Exception {
        LocalDateTime activeLastAccessed = LocalDateTime.now().minusHours(2);
        Session active = databaseSession("active", activeLastAccessed);
        Session expired = databaseSession("expired", LocalDateTime.now().minusHours(25));
        when(userSessionRepository.findByUserId(7L)).thenReturn(List.of(active, expired));

        service.refreshUserSessions(7L, "새이름");

        verify(userSessionRepository, never()).save(any(Session.class));
        verifyNoInteractions(userRepository);
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.eq("session:expired"), anyString(), anyLong(), any());
        RedisSessionService.SessionCacheEntry rewritten = writtenEntry("session:active");
        assertEquals("새이름", rewritten.nickname());
        assertEquals(activeLastAccessed.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(),
                rewritten.lastAccessedAt());
        assertTrue(rewritten.dbBacked());
    }

    @Test
    void updateSessionKeepsTwentyFourHourExpiry() {
        Session session = databaseSession("renamed", LocalDateTime.now().minusHours(1));
        when(userSessionRepository.findById("renamed")).thenReturn(Optional.of(session));
        when(userRepository.findById(7L)).thenReturn(Optional.of(neighbor()));

        service.updateSession("renamed", 7L, "새이름");

        ArgumentCaptor<Session> saved = ArgumentCaptor.forClass(Session.class);
        verify(userSessionRepository).save(saved.capture());
        assertTrue(saved.getValue().getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
    }
}
