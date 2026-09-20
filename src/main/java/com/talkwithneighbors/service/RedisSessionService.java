package com.talkwithneighbors.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.Session;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserSessionRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.websocket.AuthenticatedWebSocketSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import java.security.Principal;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

@Service
@Slf4j
public class RedisSessionService implements ApplicationListener<SessionDisconnectEvent> {
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final UserOnlineStatusListener userOnlineStatusListener;
    private final AuthenticatedWebSocketSessionRegistry webSocketSessionRegistry;
    private final ObjectProvider<SimpUserRegistry> simpUserRegistry;
    /**
     * 핫 패스(getSession/getSessionWithoutTouch)는 메서드 수준 @Transactional을 쓰지 않는다. 그렇게 하면
     * Redis가 답하는 경우에도 JPA 트랜잭션이 시작되어 HikariCP 커넥션을 점유하기 때문이다.
     * 데이터베이스가 실제로 필요한 분기만 이 템플릿으로 감싼다.
     */
    private final TransactionTemplate transactionTemplate;

    /** 프로세스별 마지막 users.is_online/last_online_at 기록 시각. 요청마다 UPDATE 하지 않기 위한 스로틀. */
    private final Map<Long, Instant> lastPresenceWrite = new ConcurrentHashMap<>();

    // @Lazy를 사용하여 순환 의존성 해결
    public RedisSessionService(RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              UserSessionRepository userSessionRepository,
                              UserRepository userRepository,
                              @Lazy UserOnlineStatusListener userOnlineStatusListener,
                              AuthenticatedWebSocketSessionRegistry webSocketSessionRegistry,
                              ObjectProvider<SimpUserRegistry> simpUserRegistry,
                              TransactionTemplate transactionTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userSessionRepository = userSessionRepository;
        this.userRepository = userRepository;
        this.userOnlineStatusListener = userOnlineStatusListener;
        this.webSocketSessionRegistry = webSocketSessionRegistry;
        this.simpUserRegistry = simpUserRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    private static final String SESSION_PREFIX = "session:";
    private static final String ONLINE_PREFIX = "online:";
    private static final String PENDING_MATCH_PREFIX = "pending_match:";
    private static final String USER_CURRENT_ROOM_PREFIX = "user_current_room:";
    private static final long SESSION_EXPIRATION = 24 * 60 * 60; // 24시간
    private static final long ONLINE_EXPIRATION = 5 * 60; // 5분
    private static final long CURRENT_ROOM_EXPIRATION = 30 * 60; // 30분 (채팅방 입장 상태 만료)
    /** 이 시간 안에 접근한 세션은 Redis 항목만으로 신뢰하고 sessions 행도 다시 쓰지 않는다. */
    private static final Duration SESSION_TOUCH_INTERVAL = Duration.ofMinutes(5);
    /** users 테이블의 접속 상태를 다시 쓰는 최소 간격. ONLINE_EXPIRATION(5분)보다 충분히 짧아야 한다. */
    private static final Duration PRESENCE_WRITE_INTERVAL = Duration.ofSeconds(60);
    private static final DefaultRedisScript<Long> CLEAR_CURRENT_ROOM_IF_MATCHES =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    /**
     * Redis에 저장하는 세션 캐시 항목. {@code dbBacked}가 true인 항목만 이 서비스가 sessions 행을
     * 확인한 뒤 기록한 것이므로, 표식이 없는 항목(과거 형식이나 외부 기록)은 절대 신뢰하지 않는다.
     * <p>
     * {@code lastAccessedAt}은 sessions 행의 접근 시각(HTTP 터치 창의 기준)이고, {@code verifiedAt}은
     * 이 서비스가 마지막으로 sessions 행의 존재를 확인한 시각이다. 폐기 경로의 Redis 삭제가 실패해도
     * 남은 항목이 {@link #SESSION_TOUCH_INTERVAL}보다 오래 자격 증명으로 쓰이지 않도록 STOMP 검증은
     * {@code verifiedAt}을 기준으로 데이터베이스를 다시 확인한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionCacheEntry(
            Long userId,
            String username,
            String email,
            String nickname,
            long lastAccessedAt,
            long verifiedAt,
            boolean dbBacked
    ) {
        static SessionCacheEntry of(UserSession userSession, LocalDateTime lastAccessedAt, long verifiedAtEpochSecond) {
            return new SessionCacheEntry(
                    userSession.getUserId(),
                    userSession.getUsername(),
                    userSession.getEmail(),
                    userSession.getNickname(),
                    lastAccessedAt == null ? 0L : toEpochSecond(lastAccessedAt),
                    verifiedAtEpochSecond,
                    true);
        }

        boolean isFreshAt(long nowEpochSecond) {
            return nowEpochSecond - lastAccessedAt < SESSION_TOUCH_INTERVAL.getSeconds();
        }

        /** 마지막 데이터베이스 확인이 터치 창 안이면 sessions 행을 다시 읽지 않아도 된다. */
        boolean isVerifiedAt(long nowEpochSecond) {
            return nowEpochSecond - verifiedAt < SESSION_TOUCH_INTERVAL.getSeconds();
        }

        SessionCacheEntry verifiedAt(long verifiedAtEpochSecond) {
            return new SessionCacheEntry(userId, username, email, nickname, lastAccessedAt, verifiedAtEpochSecond, dbBacked);
        }

        UserSession toUserSession() {
            return UserSession.of(userId, username, email, nickname);
        }
    }

    private static long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /** Redis 항목이 sessions 행보다 오래 살아남지 않도록 행의 만료 시각까지의 남은 초를 TTL로 쓴다. */
    private static long ttlSecondsUntil(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt == null) {
            return SESSION_EXPIRATION;
        }
        long remaining = Duration.between(now, expiresAt).getSeconds();
        return Math.max(1L, Math.min(SESSION_EXPIRATION, remaining));
    }

    @Transactional
    public void saveSession(String sessionId, UserSession userSession) {
        if (userSession == null || userSession.getUserId() == null) {
            throw new IllegalArgumentException("A valid user session is required");
        }
        try {
            // 세션 DB를 기준 저장소로 유지합니다. Redis는 빠른 조회를 위한 보조 저장소입니다.
            LocalDateTime now = LocalDateTime.now();
            Session session = new Session();
            session.setSessionId(sessionId);

            // 사용자 정보 조회 및 설정
            User user = userRepository.findById(userSession.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found: " + userSession.getUserId()));
            session.setUser(user);
            session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));
            session.setLastAccessedAt(now);

            userSessionRepository.save(session);

            writeCacheEntry(SESSION_PREFIX + sessionId, userSession, now, session.getExpiresAt(), now);

            setUserOnline(userSession.getUserId().toString());
        } catch (Exception e) {
            log.error("Error saving session", e);
            throw new RuntimeException("Failed to save session: " + e.getMessage(), e);
        }
    }

    /**
     * 인증된 HTTP 요청마다 호출된다. 5분 안에 접근한 세션은 Redis 항목만으로 답하고,
     * 그보다 오래된 세션만 sessions 행을 읽어 접근 시각과 만료 시각을 갱신한다.
     * <p>
     * 의도적으로 {@code @Transactional}이 아니다. Redis가 답하는 경로에서 JPA 트랜잭션을 열면
     * 쿼리 없이도 커넥션 풀을 점유하므로, sessions 행이 필요한 분기만 {@link #transactionTemplate}로 감싼다.
     */
    public UserSession getSession(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        SessionCacheEntry cached = readCacheEntry(key);
        if (cached != null && cached.isFreshAt(Instant.now().getEpochSecond())) {
            UserSession userSession = cached.toUserSession();
            setUserOnline(userSession.getUserId().toString());
            return userSession;
        }

        boolean hadCacheEntry = cached != null;
        UserSession userSession = transactionTemplate.execute(status -> loadAndTouchSession(sessionId, key, hadCacheEntry));
        if (userSession == null) {
            return null;
        }
        setUserOnline(userSession.getUserId().toString());
        return userSession;
    }

    /** getSession의 데이터베이스 분기. 항상 {@link #transactionTemplate} 안에서 실행된다. */
    private UserSession loadAndTouchSession(String sessionId, String key, boolean hadCacheEntry) {
        Optional<Session> storedSession = userSessionRepository.findByIdWithUser(sessionId);
        if (storedSession.isEmpty()) {
            if (hadCacheEntry) {
                deleteCacheKey(key);
            }
            return null;
        }

        Session session = storedSession.get();
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt() != null && !session.getExpiresAt().isAfter(now)) {
            userSessionRepository.deleteById(sessionId);
            deleteCacheKey(key);
            webSocketSessionRegistry.closeSessionsForCredential(sessionId);
            return null;
        }

        LocalDateTime lastAccessedAt = session.getLastAccessedAt();
        if (lastAccessedAt == null || lastAccessedAt.isBefore(now.minus(SESSION_TOUCH_INTERVAL))) {
            session.setLastAccessedAt(now);
            session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));
            userSessionRepository.save(session);
            lastAccessedAt = now;
        }

        User user = session.getUser();
        if (user == null || user.getId() == null) {
            return null;
        }

        UserSession userSession = UserSession.of(
                user.getId(), user.getUsername(), user.getEmail(), user.getUsername());
        writeCacheEntry(key, userSession, lastAccessedAt, session.getExpiresAt(), now);
        return userSession;
    }

    /**
     * Reads the session without extending its lifetime or updating online
     * presence. Used by high-frequency STOMP authorization: a trusted Redis
     * entry whose last database verification is younger than
     * {@link #SESSION_TOUCH_INTERVAL} answers without any sessions read, and
     * no JPA transaction is opened on that path. Once per interval the sessions
     * row is re-read so that a key left behind by a failed revocation delete
     * cannot outlive the row by more than the interval; the re-check never
     * extends {@code expires_at}.
     */
    public UserSession getSessionWithoutTouch(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        SessionCacheEntry cached = readCacheEntry(key);
        long nowEpochSecond = Instant.now().getEpochSecond();
        if (cached != null && cached.isVerifiedAt(nowEpochSecond)) {
            return cached.toUserSession();
        }
        Optional<Session> storedSession = userSessionRepository.findByIdWithUser(sessionId);
        if (storedSession.isEmpty()) {
            if (cached != null) {
                deleteCacheKey(key);
            }
            return null;
        }
        Session session = storedSession.get();
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt() != null && !session.getExpiresAt().isAfter(now)) {
            if (cached != null) {
                deleteCacheKey(key);
            }
            return null;
        }
        User user = session.getUser();
        if (user == null || user.getId() == null) {
            return null;
        }
        if (cached != null) {
            // 행이 아직 살아 있으므로 확인 시각만 갱신한다. 접근 시각과 sessions 행은 바뀌지 않는다.
            writeCacheEntry(key, cached.verifiedAt(nowEpochSecond), ttlSecondsUntil(session.getExpiresAt(), now));
            return cached.toUserSession();
        }
        return UserSession.of(user.getId(), user.getUsername(), user.getEmail(), user.getUsername());
    }

    private SessionCacheEntry readCacheEntry(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            SessionCacheEntry entry = objectMapper.readValue(json, SessionCacheEntry.class);
            if (!entry.dbBacked() || entry.userId() == null) {
                return null;
            }
            return entry;
        } catch (Exception redisError) {
            log.debug("Redis is unavailable or holds an unreadable session entry. Falling back to the database.");
            return null;
        }
    }

    /** sessions 행을 방금 확인한 뒤 호출한다. 확인 시각은 지금이고 TTL은 행의 만료 시각까지다. */
    private void writeCacheEntry(String key, UserSession userSession, LocalDateTime lastAccessedAt,
                                 LocalDateTime expiresAt, LocalDateTime now) {
        writeCacheEntry(key,
                SessionCacheEntry.of(userSession, lastAccessedAt, toEpochSecond(now)),
                ttlSecondsUntil(expiresAt, now));
    }

    private void writeCacheEntry(String key, SessionCacheEntry entry, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception redisError) {
            log.debug("Redis is unavailable. The session remains database-backed.");
        }
    }

    /**
     * 폐기 경로의 Redis 삭제. 실패해도 호출자의 트랜잭션은 계속되지만, 남은 항목은
     * {@link #getSession}/{@link #getSessionWithoutTouch}가 터치 창 안에 sessions 행과 대조해 지운다.
     * 운영자가 장애를 볼 수 있도록 WARN으로 남긴다.
     */
    private void deleteCacheKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception redisError) {
            log.warn("Redis is unavailable while deleting session entry {}. It will be re-verified against the "
                    + "sessions table within {} seconds.", key, SESSION_TOUCH_INTERVAL.getSeconds());
        }
    }

    @Transactional
    public void deleteSession(String sessionId) {
        String sessionKey = SESSION_PREFIX + sessionId;

        SessionCacheEntry cached = readCacheEntry(sessionKey);

        String userIdToProcess = null;
        if (cached != null) {
            userIdToProcess = cached.userId().toString();
        } else {
            Optional<Session> rdbSessionOptional = userSessionRepository.findById(sessionId);
            if (rdbSessionOptional.isPresent()) {
                Session rdbSession = rdbSessionOptional.get();
                if (rdbSession.getUser() != null && rdbSession.getUser().getId() != null) {
                    userIdToProcess = rdbSession.getUser().getId().toString();
                }
            }
        }

        deleteCacheKey(sessionKey);

        // RDB에서 현재 세션 삭제
        userSessionRepository.deleteById(sessionId);
        webSocketSessionRegistry.closeSessionsForCredential(sessionId);

        if (userIdToProcess != null) {
            // 다른 활성 세션이 있는지 확인 (RDB 기준)
            List<Session> otherActiveSessions = userSessionRepository.findAllByUserIdAndExpiresAtAfterAndSessionIdNot(
                Long.parseLong(userIdToProcess), LocalDateTime.now(), sessionId
            );

            if (otherActiveSessions.isEmpty()) {
                // 다른 활성 세션이 없으면 오프라인 처리
                log.debug("[RedisSessionService] No other active sessions found for user {}. Setting user offline.", userIdToProcess);
                try {
                    setUserOffline(userIdToProcess);
                } catch (Exception e) {
                    log.error("[RedisSessionService] Error setting user {} offline after session deletion: {}", userIdToProcess, e.getMessage(), e);
                }
            } else {
                log.debug("[RedisSessionService] User {} still has {} other active session(s). Not setting to offline.", userIdToProcess, otherActiveSessions.size());
            }
        } else {
            log.warn("[RedisSessionService] Could not determine the session user. Skipping offline processing.");
        }
    }

    /**
     * 사용자를 온라인 상태로 표시합니다.
     *
     * Redis의 online 키는 매번 갱신하지만, users 행은 오프라인에서 온라인으로 바뀌었거나
     * 이 프로세스가 마지막으로 기록한 지 {@link #PRESENCE_WRITE_INTERVAL}이 지났을 때만 다시 쓴다.
     */
    @Transactional
    public void setUserOnline(String userId) {
        // 이벤트 트리거 여부를 판단하기 위해, 메서드 시작 시점의 Redis 온라인 상태를 먼저 확인
        boolean wasOnlineInRedisBeforeUpdate = isUserOnlineFromRedis(userId);
        log.debug("[setUserOnline] userId: {}, wasOnlineInRedisBeforeUpdate: {}", userId, wasOnlineInRedisBeforeUpdate);

        // Redis에 온라인 상태를 캐시하지만, Redis가 없어도 DB 상태는 유지합니다.
        try {
            String key = ONLINE_PREFIX + userId;
            redisTemplate.opsForValue().set(key, "online", ONLINE_EXPIRATION, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis is unavailable while marking user {} online.", userId);
        }

        Long userLongId = Long.parseLong(userId);
        Instant now = Instant.now();
        Instant lastWrite = lastPresenceWrite.get(userLongId);
        boolean presenceWriteDue = lastWrite == null
                || lastWrite.isBefore(now.minus(PRESENCE_WRITE_INTERVAL));
        if (!wasOnlineInRedisBeforeUpdate || presenceWriteDue) {
            // getSession의 Redis 적중 경로에는 바깥 트랜잭션이 없으므로 조회와 저장을 한 트랜잭션으로 묶는다.
            transactionTemplate.executeWithoutResult(status -> {
                User user = userRepository.findById(userLongId)
                        .orElseThrow(() -> new RuntimeException("User not found"));
                user.setIsOnline(true);
                user.setLastOnlineAt(LocalDateTime.now());
                userRepository.save(user);
            });
            lastPresenceWrite.put(userLongId, now);
            log.debug("User {} presence written to the database.", userId);
        }

        // 실제로 오프라인에서 온라인으로 전환된 경우에만 이벤트 발생 (Redis 기준)
        if (!wasOnlineInRedisBeforeUpdate) {
            log.debug("[setUserOnline] Triggering user online event for userId: {}", userId);
            try {
                triggerUserOnlineEvent(userLongId);
            } catch (Exception e) {
                log.error("Error triggering user online event for userId {}: {}", userId, e.getMessage(), e);
            }
        }
    }

    /**
     * Redis에서만 온라인 상태를 확인합니다 (이벤트 중복 방지용)
     */
    private boolean isUserOnlineFromRedis(String userId) {
        try {
            String key = ONLINE_PREFIX + userId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 이 프로세스에 사용자의 STOMP 세션이 열려 있는지 확인합니다.
     */
    private boolean hasActiveWebSocketSession(String userId) {
        try {
            SimpUserRegistry registry = simpUserRegistry.getIfAvailable();
            if (registry == null) {
                return false;
            }
            SimpUser simpUser = registry.getUser(userId);
            return simpUser != null && !simpUser.getSessions().isEmpty();
        } catch (Exception e) {
            log.debug("[isUserOnline] SimpUserRegistry lookup failed for userId: {}", userId);
            return false;
        }
    }

    /**
     * 사용자가 온라인 상태인지 확인합니다.
     * 열린 WebSocket 세션, Redis online 키 순으로 확인하고, Redis가 응답하지 않을 때만
     * users 행의 is_online/last_online_at으로 판단합니다.
     */
    public boolean isUserOnline(String userId) {
        if (hasActiveWebSocketSession(userId)) {
            log.debug("[isUserOnline] userId: {} is ONLINE (active WebSocket session).", userId);
            return true;
        }
        String redisOnlineKey = ONLINE_PREFIX + userId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisOnlineKey))) {
                // Redis 키 만료 시간 갱신 (활동으로 간주)
                redisTemplate.expire(redisOnlineKey, ONLINE_EXPIRATION, TimeUnit.SECONDS);
                log.debug("[isUserOnline] userId: {} is ONLINE (Redis key exists).", userId);
                return true;
            }
            log.debug("[isUserOnline] userId: {} is OFFLINE (no Redis key).", userId);
            return false;
        } catch (Exception redisError) {
            log.debug("[isUserOnline] Redis is unavailable. Falling back to the database for userId: {}", userId);
            return isUserOnlineFromDatabase(userId);
        }
    }

    private boolean isUserOnlineFromDatabase(String userId) {
        try {
            User user = userRepository.findById(Long.parseLong(userId)).orElse(null);
            if (user == null) {
                log.debug("[isUserOnline] User not found for ID: {}. Considering OFFLINE.", userId);
                return false;
            }
            if (Boolean.FALSE.equals(user.getIsOnline())) {
                return false;
            }
            // is_online이 true이거나 null(오래된 데이터 호환)일 때 lastOnlineAt 기준으로 판단
            LocalDateTime lastOnlineAt = user.getLastOnlineAt();
            return lastOnlineAt != null
                    && !lastOnlineAt.isBefore(LocalDateTime.now().minusSeconds(ONLINE_EXPIRATION));
        } catch (NumberFormatException e) {
            log.error("[isUserOnline] Invalid userId format: {}. Considering OFFLINE.", userId, e);
            return false;
        } catch (Exception e) {
            log.error("[isUserOnline] Error checking online status for userId: {}. Considering OFFLINE. Error: {}", userId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 오프라인 사용자에게 매칭 요청을 저장합니다.
     */
    public void savePendingMatch(String userId, String matchId) {
        try {
            String key = PENDING_MATCH_PREFIX + userId;
            redisTemplate.opsForList().rightPush(key, matchId);
            redisTemplate.expire(key, SESSION_EXPIRATION, TimeUnit.SECONDS);
            log.info("[savePendingMatch] Pending matchId: {} saved for offline userId: {}", matchId, userId);
        } catch (Exception e) {
            log.debug("Redis is unavailable. Pending match {} will be delivered through the database notification flow.", matchId);
        }
    }

    /**
     * 사용자의 대기 중인 매칭 요청을 조회합니다.
     */
    public List<String> getPendingMatches(String userId) {
        try {
            String key = PENDING_MATCH_PREFIX + userId;
            List<String> matches = redisTemplate.opsForList().range(key, 0, -1);
            if (matches != null && !matches.isEmpty()) {
                redisTemplate.delete(key);
            }
            return matches;
        } catch (Exception e) {
            log.debug("Redis is unavailable while loading pending matches for user {}.", userId);
            return List.of();
        }
    }

    /**
     * 사용자의 모든 세션을 끊습니다. 비밀번호 재설정과 계정 삭제가 호출하므로
     * Redis 장애가 호출자의 트랜잭션을 실패시키지 않아야 합니다.
     */
    @Transactional
    public void removeUserSessions(String userId) {
        List<Session> sessions = userSessionRepository.findByUserId(Long.parseLong(userId));
        for (Session session : sessions) {
            deleteCacheKey(SESSION_PREFIX + session.getSessionId());
            webSocketSessionRegistry.closeSessionsForCredential(session.getSessionId());
        }
        userSessionRepository.deleteAll(sessions);
    }

    /**
     * 닉네임 변경처럼 세션에 담긴 사용자 정보가 바뀌었을 때 사용자의 활성 세션 캐시 항목을 다시 씁니다.
     * 순수한 Redis 재기록이다: sessions 행의 접근/만료 시각은 그대로 두고, 아직 정리되지 않은
     * 만료 행은 건너뛴다(만료된 자격 증명을 되살리거나 다른 기기의 세션을 24시간 연장하면 안 된다).
     */
    @Transactional(readOnly = true)
    public void refreshUserSessions(Long userId, String nickname) {
        LocalDateTime now = LocalDateTime.now();
        for (Session session : userSessionRepository.findByUserId(userId)) {
            if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
                continue;
            }
            User user = session.getUser();
            if (user == null || user.getId() == null) {
                continue;
            }
            UserSession userSession = UserSession.of(
                    user.getId(),
                    user.getUsername(),
                    user.getEmail(),
                    nickname != null ? nickname : user.getUsername());
            writeCacheEntry(SESSION_PREFIX + session.getSessionId(), userSession,
                    session.getLastAccessedAt(), session.getExpiresAt(), now);
        }
    }

    /**
     * 서버 시작 시 실행되는 초기화 메서드
     * 만료된 세션을 정리하고 오프라인 사용자를 설정합니다.
     */
    @PostConstruct
    public void initializeSessionManagement() {
        log.info("Initializing session management on server startup");
        try {
            // 의존성이 제대로 주입되었는지 확인
            if (userSessionRepository == null) {
                log.warn("userSessionRepository is null, skipping initialization");
                return;
            }

            if (userRepository == null) {
                log.warn("userRepository is null, skipping initialization");
                return;
            }

            if (redisTemplate == null) {
                log.warn("redisTemplate is null, skipping initialization");
                return;
            }

            cleanupExpiredSessions();
            checkAndSetOfflineUsers();
            log.info("Session management initialization completed successfully");
        } catch (Exception e) {
            log.error("Error during session management initialization", e);
            // 서버 시작을 방해하지 않기 위해 예외는 로깅만 하고 넘어갑니다
        }
    }

    @Scheduled(cron = "0 0 * * * *") // 매시간 실행
    @Transactional
    public void cleanupExpiredSessions() {
        log.info("Cleaning up expired sessions");
        LocalDateTime now = LocalDateTime.now();
        List<String> expiredSessionIds = userSessionRepository.findExpiredSessionIds(now);

        for (String sessionId : expiredSessionIds) {
            deleteCacheKey(SESSION_PREFIX + sessionId);
            webSocketSessionRegistry.closeSessionsForCredential(sessionId);
        }

        int deleted = userSessionRepository.deleteExpiredBefore(now);
        log.info("Cleaned up {} expired sessions", deleted);
    }

    /**
     * 사용자를 오프라인 상태로 표시합니다.
     */
    @Transactional
    public void setUserOffline(String userId) {
        // 이전 온라인 상태 확인
        boolean wasOnline = isUserOnlineFromRedis(userId);

        try {
            String key = ONLINE_PREFIX + userId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("Redis is unavailable while marking user {} offline.", userId);
        }

        // RDB에 오프라인 상태 저장
        Long userLongId = Long.parseLong(userId);
        User user = userRepository.findById(userLongId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsOnline(false);
        userRepository.save(user);
        lastPresenceWrite.remove(userLongId);

        log.debug("User {} is now offline", userId);

        // 온라인에서 오프라인으로 변경된 경우에만 이벤트 발생
        if (wasOnline) {
            try {
                triggerUserOfflineEvent(userLongId);
            } catch (Exception e) {
                log.error("Error triggering user offline event for userId {}: {}", userId, e.getMessage(), e);
            }
        }
    }

    /**
     * 5분 동안 활동이 없는 사용자를 오프라인 상태로 변경합니다.
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    @Transactional
    public void checkAndSetOfflineUsers() {
        log.debug("Checking for inactive users");
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);

        // RDB에서 5분 이상 활동이 없는 온라인 사용자 조회
        List<User> inactiveUsers = userRepository.findByIsOnlineTrueAndLastOnlineAtBefore(fiveMinutesAgo);

        for (User user : inactiveUsers) {
            setUserOffline(user.getId().toString());
        }

        if (!inactiveUsers.isEmpty()) {
            log.info("{} users set to offline due to inactivity", inactiveUsers.size());
        }
    }

    @Transactional
    public void updateSession(String sessionId, Long userId, String nickname) {
        // 데이터베이스 세션 업데이트
        Session session = userSessionRepository.findById(sessionId)
                .orElseGet(() -> {
                    Session newSession = new Session();
                    newSession.setSessionId(sessionId);
                    return newSession;
                });

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        LocalDateTime now = LocalDateTime.now();
        session.setUser(user);
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));

        userSessionRepository.save(session);

        // Redis 세션 갱신. 실패해도 데이터베이스는 갱신되었으므로 예외를 던지지 않는다.
        UserSession userSession = UserSession.of(
            userId,
            user.getUsername(),
            user.getEmail(),
            nickname != null ? nickname : user.getUsername()
        );
        writeCacheEntry(SESSION_PREFIX + sessionId, userSession, now, session.getExpiresAt(), now);

        // 온라인 상태 갱신
        setUserOnline(userId.toString());
    }

    /**
     * 사용자가 채팅방에 입장했음을 기록합니다.
     *
     * @param userId 사용자 ID
     * @param roomId 채팅방 ID
     */
    @Transactional
    public void setUserCurrentRoom(String userId, String roomId) {
        try {
            String key = USER_CURRENT_ROOM_PREFIX + userId;
            redisTemplate.opsForValue().set(key, roomId, CURRENT_ROOM_EXPIRATION, TimeUnit.SECONDS);
            log.debug("[RedisSessionService] User {} entered room: {}", userId, roomId);
        } catch (Exception e) {
            log.error("[RedisSessionService] Error setting user current room for userId: {}, roomId: {}", userId, roomId, e);
        }
    }

    /**
     * 사용자가 채팅방에서 나갔음을 기록합니다.
     *
     * @param userId 사용자 ID
     */
    @Transactional
    public void clearUserCurrentRoom(String userId) {
        try {
            String key = USER_CURRENT_ROOM_PREFIX + userId;
            redisTemplate.delete(key);
            log.debug("[RedisSessionService] User {} left current room", userId);
        } catch (Exception e) {
            log.error("[RedisSessionService] Error clearing user current room for userId: {}", userId, e);
        }
    }

    /**
     * Clears presence for a deleted room without racing a user who has already
     * moved to another room.
     */
    public void clearUserCurrentRoomIfMatches(String userId, String expectedRoomId) {
        try {
            String key = USER_CURRENT_ROOM_PREFIX + userId;
            redisTemplate.execute(
                    CLEAR_CURRENT_ROOM_IF_MATCHES,
                    List.of(key),
                    expectedRoomId
            );
        } catch (Exception exception) {
            // Presence is ephemeral. A Redis failure must not fail durable outbox delivery.
            log.warn("[RedisSessionService] Failed to clear deleted current room. userId={}, roomId={}",
                    userId, expectedRoomId, exception);
        }
    }

    /**
     * 사용자가 현재 어느 채팅방에 있는지 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 현재 채팅방 ID (없으면 null)
     */
    public String getUserCurrentRoom(String userId) {
        try {
            String key = USER_CURRENT_ROOM_PREFIX + userId;
            String roomId = redisTemplate.opsForValue().get(key);

            if (roomId != null && !roomId.isEmpty()) {
                // 만료 시간 연장
                redisTemplate.expire(key, CURRENT_ROOM_EXPIRATION, TimeUnit.SECONDS);
                log.debug("[RedisSessionService] User {} is currently in room: {}", userId, roomId);
                return roomId;
            }

            log.debug("[RedisSessionService] User {} is not in any room", userId);
            return null;
        } catch (Exception e) {
            log.error("[RedisSessionService] Error getting user current room for userId: {}", userId, e);
            return null;
        }
    }

    /**
     * 사용자가 특정 채팅방에 입장해 있는지 확인합니다.
     *
     * @param userId 사용자 ID
     * @param roomId 채팅방 ID
     * @return 해당 채팅방에 입장해 있으면 true
     */
    public boolean isUserInRoom(String userId, String roomId) {
        try {
            String currentRoom = getUserCurrentRoom(userId);
            boolean isInRoom = roomId.equals(currentRoom);
            log.debug("[RedisSessionService] User {} current room: '{}', target room: '{}', isInRoom: {}", userId, currentRoom, roomId, isInRoom);
            return isInRoom;
        } catch (Exception e) {
            log.error("[RedisSessionService] Error checking if user {} is in room {}", userId, roomId, e);
            return false;
        }
    }

    /**
     * 사용자 온라인 이벤트를 발생시킵니다.
     */
    private void triggerUserOnlineEvent(Long userId) {
        try {
            if (userOnlineStatusListener != null) {
                userOnlineStatusListener.onUserOnline(userId);
            } else {
                log.error("[RedisSessionService] UserOnlineStatusListener is null. A circular dependency is likely.");
            }
        } catch (Exception e) {
            log.error("[RedisSessionService] triggerUserOnlineEvent failed for userId {}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 사용자 오프라인 이벤트를 발생시킵니다.
     */
    private void triggerUserOfflineEvent(Long userId) {
        try {
            userOnlineStatusListener.onUserOffline(userId);
        } catch (Exception e) {
            log.error("Error in triggerUserOfflineEvent for userId {}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 세션 만료 시간을 연장합니다.
     */
    @Transactional
    public void extendSession(String sessionId) {

        try {
            // Redis 세션 만료 시간 연장
            String key = SESSION_PREFIX + sessionId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                redisTemplate.expire(key, SESSION_EXPIRATION, TimeUnit.SECONDS);
                log.debug("Redis session expiration extended.");
            }

            // 데이터베이스 세션 만료 시간 연장
            userSessionRepository.findById(sessionId)
                    .ifPresent(session -> {
                        session.setLastAccessedAt(LocalDateTime.now());
                        session.setExpiresAt(LocalDateTime.now().plusSeconds(SESSION_EXPIRATION));
                        userSessionRepository.save(session);
                        log.debug("Database session expiration extended.");
                    });

        } catch (Exception e) {
            log.error("Error extending session", e);
        }
    }

    @Override
    @Transactional
    public void onApplicationEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = headerAccessor.getUser();

        log.debug("[WebSocketDisconnectEvent] WebSocket disconnected. Principal present: {}", principal != null);

        if (principal != null && principal.getName() != null) {
            String userId = principal.getName(); // UserPrincipal에서 반환되는 사용자 ID (문자열)
            log.debug("[WebSocketDisconnectEvent] User ID {} disconnected. Checking offline status.", userId);

            // 다중 접속 시 한 세션이 끊겨도 다른 세션이 살아있으면 isUserOnline() 호출 시
            // SimpUserRegistry 기준으로 다시 온라인으로 판단됩니다.
            try {
                setUserOffline(userId);
            } catch (Exception e) {
                log.error("[WebSocketDisconnectEvent] Error setting user {} offline after WebSocket disconnect: {}",
                          userId, e.getMessage(), e);
            }
        } else {
            // Principal이 없거나 이름이 없는 경우 (예: 인증되지 않은 연결 또는 STOMP 이전 단계의 연결 종료)
            log.warn("[WebSocketDisconnectEvent] WebSocket disconnected without a resolvable Principal.");
        }
    }
}
