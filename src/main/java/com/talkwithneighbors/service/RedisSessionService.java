package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.Session;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserSessionRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import java.security.Principal;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class RedisSessionService implements ApplicationListener<SessionDisconnectEvent> {
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final UserOnlineStatusListener userOnlineStatusListener;
    
    // @Lazy를 사용하여 순환 의존성 해결
    public RedisSessionService(RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              UserSessionRepository userSessionRepository,
                              UserRepository userRepository,
                              @Lazy UserOnlineStatusListener userOnlineStatusListener) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userSessionRepository = userSessionRepository;
        this.userRepository = userRepository;
        this.userOnlineStatusListener = userOnlineStatusListener;
    }
    
    private static final String SESSION_PREFIX = "session:";
    private static final String ONLINE_PREFIX = "online:";
    private static final String PENDING_MATCH_PREFIX = "pending_match:";
    private static final String USER_CURRENT_ROOM_PREFIX = "user_current_room:";
    private static final long SESSION_EXPIRATION = 24 * 60 * 60; // 24시간
    private static final long ONLINE_EXPIRATION = 5 * 60; // 5분
    private static final long CURRENT_ROOM_EXPIRATION = 30 * 60; // 30분 (채팅방 입장 상태 만료)
    private static final long SESSION_TIMEOUT_MINUTES = 30;
    private static final DefaultRedisScript<Long> CLEAR_CURRENT_ROOM_IF_MATCHES =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    @Transactional
    public void saveSession(String sessionId, UserSession userSession) {
        // === UserSession의 username 로깅 추가 ===
        if (userSession != null && userSession.getUsername() != null) {
            log.info("Saving session for username: '{}', Original bytes: {}", 
                     userSession.getUsername(), 
                     java.util.Arrays.toString(userSession.getUsername().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try {
                 log.info("Username (re-decoded from UTF-8 bytes): '{}'", new String(userSession.getUsername().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
                 log.info("Username (decoded as ISO-8859-1): '{}'", new String(userSession.getUsername().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.ISO_8859_1));
                 log.info("Username (decoded as MS949): '{}'", new String(userSession.getUsername().getBytes(java.nio.charset.Charset.forName("MS949")), java.nio.charset.Charset.forName("MS949")));
            } catch (Exception e) {
                 log.error("Error logging username encodings during saveSession", e);
            }
        } else {
            log.warn("UserSession or its username is null when trying to save session with ID: {}", sessionId);
        }
        // === 로깅 추가 끝 ===
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

            try {
                String key = SESSION_PREFIX + sessionId;
                String value = objectMapper.writeValueAsString(userSession);
                redisTemplate.opsForValue().set(key, value, SESSION_EXPIRATION, TimeUnit.SECONDS);
            } catch (Exception redisError) {
                log.warn("Redis is unavailable. Keeping session {} in the database only.", sessionId);
            }

            setUserOnline(userSession.getUserId().toString());
            log.info("Session saved successfully for user: {} with sessionId: {}", userSession.getUsername(), sessionId);
        } catch (Exception e) {
            log.error("Error saving session", e);
            throw new RuntimeException("Failed to save session: " + e.getMessage(), e);
        }
    }

    @Transactional
    public UserSession getSession(String sessionId) {
        log.info("Getting session: {}", sessionId);

        String key = SESSION_PREFIX + sessionId;
        Optional<Session> storedSession = userSessionRepository.findById(sessionId);
        if (storedSession.isEmpty()) {
            log.info("Session {} is not present in the database. Ignoring any stale cache entry.", sessionId);
            return null;
        }

        Session session = storedSession.get();
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt() != null && !session.getExpiresAt().isAfter(now)) {
            userSessionRepository.deleteById(sessionId);
            try {
                redisTemplate.delete(key);
            } catch (Exception redisError) {
                log.debug("Redis is unavailable while removing expired session {}.", sessionId);
            }
            return null;
        }

        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));
        User user = userSessionRepository.save(session).getUser();
        if (user == null || user.getId() == null) {
            return null;
        }

        UserSession userSession = UserSession.of(
                user.getId(), user.getUsername(), user.getEmail(), user.getUsername());
        try {
            String userSessionJson = objectMapper.writeValueAsString(userSession);
            redisTemplate.opsForValue().set(key, userSessionJson, SESSION_EXPIRATION, TimeUnit.SECONDS);
        } catch (Exception redisError) {
            log.debug("Redis is unavailable. Session {} remains database-backed.", sessionId);
        }
        setUserOnline(user.getId().toString());
        return userSession;
    }
    
    private UserSession createNewSession() {
        // 새로운 세션을 생성하지 않고 예외를 발생시킵니다.
        throw new RuntimeException("Session not found in database");
    }

    @Transactional
    @CacheEvict(value = "sessions", key = "#sessionId")
    public void deleteSession(String sessionId) {
        log.info("[RedisSessionService] Deleting session: {}", sessionId);
        String sessionKey = SESSION_PREFIX + sessionId;

        UserSession userSession = null;
        try {
            String sessionJson = redisTemplate.opsForValue().get(sessionKey);
            if (sessionJson != null && !sessionJson.isEmpty()) {
                userSession = objectMapper.readValue(sessionJson, UserSession.class);
                log.info("[RedisSessionService] Found session in Redis for deletion: {}", sessionId);
            }
        } catch (Exception e) {
            log.error("[RedisSessionService] Error reading session from Redis during deletion for sessionId {}: {}", sessionId, e.getMessage());
        }

        String userIdToProcess = null;
        if (userSession != null && userSession.getUserId() != null) {
            userIdToProcess = userSession.getUserId().toString();
        } else {
            Optional<Session> rdbSessionOptional = userSessionRepository.findById(sessionId);
            if (rdbSessionOptional.isPresent()) {
                Session rdbSession = rdbSessionOptional.get();
                if (rdbSession.getUser() != null && rdbSession.getUser().getId() != null) {
                    userIdToProcess = rdbSession.getUser().getId().toString();
                    log.info("[RedisSessionService] Found userId {} from RDB for session {} to process.", userIdToProcess, sessionId);
                }
            }
        }

        try {
            redisTemplate.delete(sessionKey);
            log.info("[RedisSessionService] Deleted session key {} from Redis.", sessionKey);
        } catch (Exception e) {
            log.debug("Redis is unavailable while deleting session {}.", sessionId);
        }

        // RDB에서 현재 세션 삭제
        userSessionRepository.deleteById(sessionId);
        log.info("[RedisSessionService] Deleted session from RDB: {}", sessionId);

        if (userIdToProcess != null) {
            // 다른 활성 세션이 있는지 확인
            List<Session> otherActiveSessions = userSessionRepository.findAllByUserIdAndExpiresAtAfterAndSessionIdNot(
                Long.parseLong(userIdToProcess), LocalDateTime.now(), sessionId
            );
            
            boolean otherRedisSessionsExist = false;
            // Redis에서도 다른 활성 세션 키가 있는지 추가로 확인 (RDB와 Redis 간의 미세한 불일치 가능성 대비)
            // 이 부분은 애플리케이션의 세션 관리 전략에 따라 더욱 정교하게 만들 수 있습니다.
            // 예를 들어, 사용자의 모든 세션 ID 목록을 별도로 Redis에 관리하는 방법도 있습니다.
            // 현재는 RDB 기준으로만 판단합니다.

            if (otherActiveSessions.isEmpty()) {
                // 다른 활성 세션이 없으면 오프라인 처리
                log.info("[RedisSessionService] No other active sessions found for user {}. Setting user offline.", userIdToProcess);
                try {
                    setUserOffline(userIdToProcess);
                } catch (Exception e) {
                    log.error("[RedisSessionService] Error setting user {} offline after session {} deletion: {}", userIdToProcess, sessionId, e.getMessage(), e);
                }
            } else {
                log.info("[RedisSessionService] User {} still has {} other active session(s). Not setting to offline.", userIdToProcess, otherActiveSessions.size());
                // 다른 세션이 있으므로, 해당 세션의 활동으로 인해 온라인 상태가 유지되거나 다시 설정될 것입니다.
                // 이 경우 last_online_at을 가장 최근 활성 세션 기준으로 업데이트하는 로직을 고려할 수 있으나, 복잡도를 증가시킵니다.
                // 현재는 setUserOnline이 다른 세션 접근 시 호출될 것을 기대합니다.
            }
        } else {
            log.warn("[RedisSessionService] Could not determine userId for session {}. Skipping further offline processing.", sessionId);
        }
        log.info("[RedisSessionService] Session deletion process completed for: {}", sessionId);
    }
    
    /**
     * 사용자를 온라인 상태로 표시합니다.
     */
    @Transactional
    public void setUserOnline(String userId) {
        log.info("=== [RedisSessionService] 🎯 setUserOnline 호출됨! userId: {} ===", userId);
        
        // 이벤트 트리거 여부를 판단하기 위해, 메서드 시작 시점의 Redis 온라인 상태를 먼저 확인
        boolean wasOnlineInRedisBeforeUpdate = isUserOnlineFromRedis(userId);
        log.info("[setUserOnline] userId: {}, wasOnlineInRedisBeforeUpdate: {}", userId, wasOnlineInRedisBeforeUpdate);

        // 데이터베이스에서 실제 사용자 상태 확인 (더 정확한 상태 변경 감지)
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // boolean wasActuallyOnline = user.getIsOnline() != null && user.getIsOnline(); // 이 조건은 로깅용으로만 남기되, 이벤트 트리거에는 직접 사용하지 않음
        // LocalDateTime lastOnline = user.getLastOnlineAt();
        LocalDateTime now = LocalDateTime.now();
        
        // 마지막 온라인 시간이 1분 이내가 아니면 오프라인 상태로 간주 (로깅용)
        // boolean wasRecentlyOnline = lastOnline != null && 
        //                            lastOnline.isAfter(now.minusMinutes(1));
        
        // log.info("[setUserOnline] userId: {}, DB_wasActuallyOnline: {}, DB_wasRecentlyOnline: {}, DB_lastOnline: {}", 
        //          userId, wasActuallyOnline, wasRecentlyOnline, lastOnline);
        
        // Redis에 온라인 상태를 캐시하지만, Redis가 없어도 DB 상태는 유지합니다.
        try {
            String key = ONLINE_PREFIX + userId;
            redisTemplate.opsForValue().set(key, "online", ONLINE_EXPIRATION, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis is unavailable while marking user {} online.", userId);
        }
        
        // RDB에 온라인 상태 저장
        user.setIsOnline(true);
        user.setLastOnlineAt(now);
        userRepository.save(user);
        
        log.info("User {} is now online (RDB and Redis updated)", userId);
        
        // 실제로 오프라인에서 온라인으로 전환된 경우에만 이벤트 발생 (Redis 기준)
        // Redis에 해당 유저의 online 키가 없었다가 방금 생성된 경우에만 이벤트 트리거
        boolean shouldTriggerEvent = !wasOnlineInRedisBeforeUpdate;
        
        if (shouldTriggerEvent) {
            log.info("🎉 [setUserOnline] Triggering user online event for userId: {} (was not online in Redis before this update)", userId);
            try {
                // UserOnlineStatusListener 호출
                triggerUserOnlineEvent(Long.parseLong(userId));
            } catch (Exception e) {
                log.error("Error triggering user online event for userId {}: {}", userId, e.getMessage(), e);
            }
        } else {
            log.info("⏭️ [setUserOnline] Skipping event for userId: {} (was already online in Redis or no actual state change for triggering event)", userId);
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
     * 사용자가 온라인 상태인지 확인합니다.
     * Redis 키 존재 여부와 실제 활동 시간을 함께 고려합니다.
     */
    public boolean isUserOnline(String userId) {
        log.info("=== [RedisSessionService] isUserOnline check for userId: {} ===", userId);
        try {
            Long userLongId = Long.parseLong(userId);
            User user = userRepository.findById(userLongId).orElse(null);

            if (user == null) {
                log.warn("[isUserOnline] User not found for ID: {}. Considering OFFLINE.", userId);
                return false;
            }

            Boolean rdbIsOnline = user.getIsOnline();
            LocalDateTime lastOnlineAt = user.getLastOnlineAt();
            LocalDateTime now = LocalDateTime.now();

            // RDB의 is_online이 명시적으로 false이면 오프라인
            if (Boolean.FALSE.equals(rdbIsOnline)) {
                log.info("[isUserOnline] userId: {} is OFFLINE (RDB is_online is false).", userId);
                return false;
            }

            // RDB의 is_online이 true이거나 null(오래된 데이터 호환)일 때 lastOnlineAt 기준으로 판단
            // ONLINE_EXPIRATION (5분) 이상 활동 없으면 오프라인 간주
            if (lastOnlineAt == null || lastOnlineAt.isBefore(now.minusSeconds(ONLINE_EXPIRATION))) {
                log.info("[isUserOnline] userId: {} is OFFLINE (lastOnlineAt: {} is older than {} seconds ago or null).", 
                         userId, lastOnlineAt, ONLINE_EXPIRATION);
                // 이 경우 Redis의 online:{userId} 키도 삭제해주는 것이 좋을 수 있으나,
                // checkAndSetOfflineUsers 스케줄러나 Disconnect 이벤트 핸들러가 처리하도록 둘 수 있음
                return false;
            }
            
            // RDB상 is_online=true 이고, lastOnlineAt도 최근인 경우
            // 추가적으로 Redis의 online:{userId} 키 존재 여부도 확인 (더 확실한 온라인 판단)
            String redisOnlineKey = ONLINE_PREFIX + userId;
            boolean redisKeyExists = Boolean.TRUE.equals(redisTemplate.hasKey(redisOnlineKey));

            if (redisKeyExists) {
                 log.info("[isUserOnline] userId: {} is ONLINE (RDB is_online=true, lastOnlineAt recent, Redis key exists).", userId);
                 // Redis 키 만료 시간 갱신 (활동으로 간주)
                 redisTemplate.expire(redisOnlineKey, ONLINE_EXPIRATION, TimeUnit.SECONDS);
                 return true;
            } else {
                // RDB는 온라인인데 Redis 키가 없다면, 최근에 연결이 끊겼거나 Redis에서 만료된 직후일 수 있음.
                // 이 경우에도 lastOnlineAt이 매우 최근이라면 온라인으로 볼 수도 있지만, 보다 보수적으로 오프라인으로 판단하거나,
                // 혹은 setUserOnline을 호출하여 Redis 키를 다시 생성하도록 유도할 수 있음.
                // 여기서는 일단 RDB 상태를 더 신뢰하되, Redis 키가 없는 경우를 로그로 남김.
                log.warn("[isUserOnline] userId: {} - RDB indicates ONLINE and recent activity, but Redis key '{}' NOT found. Considering OFFLINE for consistency or re-triggering online status.", userId, redisOnlineKey);
                // 일관성을 위해 RDB는 온라인이나 Redis에 키가 없다면 오프라인으로 처리.
                // 또는 setUserOnline(userId)를 호출해서 Redis 키를 다시 만들도록 할 수도 있음.
                // 현재 로직에서는 오프라인으로 판단.
                return false; 
            }

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

    @Transactional
    public void removeUserSessions(String userId) {
        String userKey = SESSION_PREFIX + userId;
        String sessionId = redisTemplate.opsForValue().get(userKey);
        
        if (sessionId != null) {
            deleteSession(sessionId);
        }
        
        // RDB에서 해당 사용자의 모든 세션 삭제
        List<Session> sessions = userSessionRepository.findByUserId(Long.parseLong(userId));
        userSessionRepository.deleteAll(sessions);
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
        List<Session> expiredSessions = userSessionRepository.findExpiredSessions(now);
        
        for (Session session : expiredSessions) {
            String sessionKey = SESSION_PREFIX + session.getSessionId();
            String userKey = SESSION_PREFIX + session.getUser().getId();
            
            redisTemplate.delete(sessionKey);
            redisTemplate.delete(userKey);
        }
        
        userSessionRepository.deleteAll(expiredSessions);
        log.info("Cleaned up {} expired sessions", expiredSessions.size());
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
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        user.setIsOnline(false);
        userRepository.save(user);
        
        log.info("User {} is now offline", userId);
        
        // 온라인에서 오프라인으로 변경된 경우에만 이벤트 발생
        if (wasOnline) {
            try {
                triggerUserOfflineEvent(Long.parseLong(userId));
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
        log.info("Checking for inactive users");
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
    @CacheEvict(value = "sessions", key = "#sessionId")
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
        session.setUser(user);
        session.setLastAccessedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusMinutes(SESSION_TIMEOUT_MINUTES));
        
        userSessionRepository.save(session);
        
        // Redis 세션 업데이트
        try {
            String key = SESSION_PREFIX + sessionId;
            
            // UserSession 객체 생성
            UserSession userSession = UserSession.of(
                userId,
                user.getUsername(),
                user.getEmail(),
                nickname != null ? nickname : user.getUsername()
            );
            
            // Redis에 세션 저장
            String userSessionJson = objectMapper.writeValueAsString(userSession);
            redisTemplate.opsForValue().set(key, userSessionJson, SESSION_EXPIRATION, TimeUnit.SECONDS);
            
            // 온라인 상태 갱신
            setUserOnline(userId.toString());
            
            log.info("Session updated in Redis for user: {} with sessionId: {}", user.getUsername(), sessionId);
        } catch (Exception e) {
            log.error("Error updating session in Redis", e);
            // Redis 업데이트 실패해도 데이터베이스는 업데이트되었으므로 예외는 던지지 않음
        }
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
            log.info("[RedisSessionService] User {} entered room: {}", userId, roomId);
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
            log.info("[RedisSessionService] User {} left current room", userId);
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
        log.info("[RedisSessionService] Getting current room for userId: {}", userId);
        
        try {
            String key = USER_CURRENT_ROOM_PREFIX + userId;
            String roomId = redisTemplate.opsForValue().get(key);
            
            log.info("[RedisSessionService] Redis key '{}' value: '{}'", key, roomId);
            
            if (roomId != null && !roomId.isEmpty()) {
                // 만료 시간 연장
                redisTemplate.expire(key, CURRENT_ROOM_EXPIRATION, TimeUnit.SECONDS);
                log.info("[RedisSessionService] User {} is currently in room: {}", userId, roomId);
                return roomId;
            }
            
            log.info("[RedisSessionService] User {} is not in any room", userId);
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
        log.info("=== [RedisSessionService] isUserInRoom check for userId: {}, roomId: {} ===", userId, roomId);
        
        try {
            String currentRoom = getUserCurrentRoom(userId);
            boolean isInRoom = roomId.equals(currentRoom);
            log.info("[RedisSessionService] User {} current room: '{}', target room: '{}', isInRoom: {}", userId, currentRoom, roomId, isInRoom);
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
        log.info("=== [RedisSessionService] 🎯 triggerUserOnlineEvent 호출됨! userId: {} ===", userId);
        try {
            if (userOnlineStatusListener != null) {
                log.info("[RedisSessionService] UserOnlineStatusListener가 정상적으로 주입됨. 이벤트 호출 중...");
                userOnlineStatusListener.onUserOnline(userId);
                log.info("[RedisSessionService] ✅ UserOnlineStatusListener.onUserOnline() 호출 완료");
            } else {
                log.error("[RedisSessionService] ❌ UserOnlineStatusListener가 null입니다! 순환 의존성 문제 의심");
            }
        } catch (Exception e) {
            log.error("[RedisSessionService] ❌ triggerUserOnlineEvent 실행 중 오류 for userId {}: {}", userId, e.getMessage(), e);
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
        log.info("Extending session: {}", sessionId);
        
        try {
            // Redis 세션 만료 시간 연장
            String key = SESSION_PREFIX + sessionId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                redisTemplate.expire(key, SESSION_EXPIRATION, TimeUnit.SECONDS);
                log.info("Redis session extended for: {}", sessionId);
            }
            
            // 데이터베이스 세션 만료 시간 연장
            userSessionRepository.findById(sessionId)
                    .ifPresent(session -> {
                        session.setLastAccessedAt(LocalDateTime.now());
                        session.setExpiresAt(LocalDateTime.now().plusSeconds(SESSION_EXPIRATION));
                        userSessionRepository.save(session);
                        log.info("Database session extended for: {}", sessionId);
                    });
                    
        } catch (Exception e) {
            log.error("Error extending session: {}", sessionId, e);
        }
    }

    @Override
    @Transactional
    public void onApplicationEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = headerAccessor.getUser();
        String sessionId = headerAccessor.getSessionId(); // WebSocket 세션 ID

        log.info("[WebSocketDisconnectEvent] WebSocket session {} disconnected. Principal: {}", sessionId, principal);

        if (principal != null && principal.getName() != null) {
            String userId = principal.getName(); // UserPrincipal에서 반환되는 사용자 ID (문자열)
            log.info("[WebSocketDisconnectEvent] User ID {} disconnected from WebSocket session {}. Checking if user should be set offline.", userId, sessionId);

            // 여기에 해당 userId를 가진 다른 활성 WebSocket 세션이 있는지 확인하는 로직이 필요합니다.
            // 스프링에서는 기본적으로 개별 WebSocket 세션 목록을 직접 제공하지 않으므로,
            // 연결 시점에 어딘가에 (예: Redis Set) 사용자 ID별 활성 세션 ID 목록을 관리하고,
            // 연결 종료 시 해당 목록에서 제거한 후 목록이 비었는지 확인하는 방식이 필요합니다.
            // 또는, SimpUserRegistry를 사용하여 특정 사용자의 세션 수를 확인할 수도 있습니다.

            // 현재 구현에서는 일단 Disconnect 이벤트 발생 시 해당 사용자를 오프라인 처리 시도합니다.
            // 다중 접속 시 한 세션이 끊겨도 다른 세션이 살아있으면 isUserOnline() 호출 시 다시 온라인으로 처리될 수 있습니다.
            // 더 정교한 처리를 위해서는 활성 세션 추적이 필요합니다.
            try {
                // deleteSession은 RDB의 다른 활성 세션을 고려하지만,
                // WebSocket 연결 끊김은 해당 연결에 대한 처리이므로 바로 setUserOffline 시도
                log.info("[WebSocketDisconnectEvent] Attempting to set user {} offline due to WebSocket disconnect (session {}).", userId, sessionId);
                setUserOffline(userId);
            } catch (Exception e) {
                log.error("[WebSocketDisconnectEvent] Error setting user {} offline after WebSocket disconnect for session {}: {}", 
                          userId, sessionId, e.getMessage(), e);
            }
        } else {
            // Principal이 없거나 이름이 없는 경우 (예: 인증되지 않은 연결 또는 STOMP 이전 단계의 연결 종료)
            // simpSessionAttributes에서 userId를 가져오는 시도도 할 수 있습니다.
            // Map<String, Object> simpAttributes = SimpMessageHeaderAccessor.getSessionAttributes(event.getMessage().getHeaders());
            // if (simpAttributes != null && simpAttributes.containsKey("userId")) {
            //    String userId = (String) simpAttributes.get("userId");
            //    ...
            // }
            log.warn("[WebSocketDisconnectEvent] WebSocket session {} disconnected without a resolvable Principal or user ID. Cannot determine user to set offline directly from Principal.", sessionId);
            // 이 경우, Redis의 세션 ID (만약 STOMP 세션 ID와 같다면)로 사용자를 찾아 오프라인 처리하는 것을 고려할 수 있으나,
            // STOMP 세션 ID와 HTTP 세션 ID는 다를 수 있습니다. 
            // CustomHandshakeHandler 등에서 attributes에 저장한 userId를 사용해야 합니다.
            // 여기서는 우선 Principal 기반으로만 처리합니다.
        }
    }
} 
