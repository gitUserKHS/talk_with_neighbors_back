package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.Session;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserSessionRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RedisSessionService {
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private static final String SESSION_PREFIX = "session:";
    private static final String ONLINE_PREFIX = "online:";
    private static final String PENDING_MATCH_PREFIX = "pending_match:";
    private static final long SESSION_EXPIRATION = 24 * 60 * 60; // 24시간
    private static final long ONLINE_EXPIRATION = 5 * 60; // 5분
    private static final long SESSION_TIMEOUT_MINUTES = 30;

    public void saveSession(String sessionId, UserSession userSession) {
        try {
            // Redis에 세션 저장
            String key = SESSION_PREFIX + sessionId;
            String value = objectMapper.writeValueAsString(userSession);
            redisTemplate.opsForValue().set(key, value, SESSION_EXPIRATION, TimeUnit.SECONDS);
            
            // RDB에도 세션 정보 저장
            LocalDateTime now = LocalDateTime.now();
            Session session = new Session();
            session.setSessionId(sessionId);
            
            // 사용자 정보 조회 및 설정
            User user = userRepository.findById(userSession.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found: " + userSession.getUserId()));
            session.setUser(user);
            session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));
            session.setLastAccessedAt(now);
            
            // 세션 저장
            userSessionRepository.save(session);
            
            // 사용자를 온라인 상태로 표시
            setUserOnline(userSession.getUserId().toString());
            
            log.info("Session saved successfully for user: {} with sessionId: {}", userSession.getUsername(), sessionId);
        } catch (Exception e) {
            log.error("Error saving session to Redis/RDB", e);
            throw new RuntimeException("Failed to save session: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "sessions", key = "#sessionId")
    public UserSession getSession(String sessionId) {
        log.info("Cache miss for session: {}, attempting to load from RDB", sessionId);
        
        try {
            return userSessionRepository.findById(sessionId)
                    .map(session -> {
                        User user = session.getUser();
                        // 지연 로딩 문제를 해결하기 위해 값을 미리 가져옴
                        Long userId = user.getId();
                        String username = user.getUsername();
                        String email = user.getEmail();
                        
                        return UserSession.of(
                            userId,
                            username,
                            email,
                            username  // nickname은 username과 동일하게 설정
                        );
                    })
                    .orElseThrow(() -> new RuntimeException("Session not found in database"));
        } catch (Exception e) {
            log.error("Error retrieving session from Redis", e);
            throw new RuntimeException("Session not found in database");
        }
    }
    
    private UserSession createNewSession() {
        // 새로운 세션을 생성하지 않고 예외를 발생시킵니다.
        throw new RuntimeException("Session not found in database");
    }

    @Transactional
    @CacheEvict(value = "sessions", key = "#sessionId")
    public void deleteSession(String sessionId) {
        userSessionRepository.deleteById(sessionId);
    }
    
    /**
     * 사용자를 온라인 상태로 표시합니다.
     */
    @Transactional
    public void setUserOnline(String userId) {
        // Redis에 온라인 상태 저장 (캐시)
        String key = ONLINE_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "online", ONLINE_EXPIRATION, TimeUnit.SECONDS);
        
        // RDB에 온라인 상태 저장
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        user.setIsOnline(true);
        user.setLastOnlineAt(LocalDateTime.now());
        userRepository.save(user);
        
        log.info("User {} is now online", userId);
    }
    
    /**
     * 사용자가 온라인 상태인지 확인합니다.
     */
    public boolean isUserOnline(String userId) {
        // 먼저 Redis에서 확인 (캐시)
        String key = ONLINE_PREFIX + userId;
        Boolean isOnline = redisTemplate.hasKey(key);
        
        if (isOnline != null && isOnline) {
            return true;
        }
        
        // Redis에 없으면 RDB에서 확인
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // RDB에서 온라인 상태가 true이고 마지막 온라인 시간이 5분 이내면 온라인으로 간주
        if (user.getIsOnline() && user.getLastOnlineAt() != null) {
            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
            return user.getLastOnlineAt().isAfter(fiveMinutesAgo);
        }
        
        return false;
    }
    
    /**
     * 오프라인 사용자에게 매칭 요청을 저장합니다.
     */
    public void savePendingMatch(String userId, String matchId) {
        String key = PENDING_MATCH_PREFIX + userId;
        redisTemplate.opsForList().rightPush(key, matchId);
        // 24시간 동안 보관
        redisTemplate.expire(key, SESSION_EXPIRATION, TimeUnit.SECONDS);
        log.info("Pending match saved for user: {}", userId);
    }
    
    /**
     * 사용자의 대기 중인 매칭 요청을 조회합니다.
     */
    public List<String> getPendingMatches(String userId) {
        String key = PENDING_MATCH_PREFIX + userId;
        List<String> matches = redisTemplate.opsForList().range(key, 0, -1);
        if (matches != null && !matches.isEmpty()) {
            // 조회 후 삭제
            redisTemplate.delete(key);
        }
        return matches;
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

    @Scheduled(cron = "0 0 * * * *") // 매시간 실행
    @Transactional
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<Session> expiredSessions = userSessionRepository.findExpiredSessions(now);
        
        for (Session session : expiredSessions) {
            String sessionKey = SESSION_PREFIX + session.getSessionId();
            String userKey = SESSION_PREFIX + session.getUser().getId();
            
            redisTemplate.delete(sessionKey);
            redisTemplate.delete(userKey);
        }
        
        userSessionRepository.deleteAll(expiredSessions);
    }

    /**
     * 사용자를 오프라인 상태로 표시합니다.
     */
    @Transactional
    public void setUserOffline(String userId) {
        // Redis에서 온라인 상태 삭제
        String key = ONLINE_PREFIX + userId;
        redisTemplate.delete(key);
        
        // RDB에 오프라인 상태 저장
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        user.setIsOnline(false);
        userRepository.save(user);
        
        log.info("User {} is now offline", userId);
    }

    /**
     * 5분 동안 활동이 없는 사용자를 오프라인 상태로 변경합니다.
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    @Transactional
    public void checkAndSetOfflineUsers() {
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
    }
} 