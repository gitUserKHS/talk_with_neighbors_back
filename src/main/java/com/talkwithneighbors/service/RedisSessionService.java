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

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    public void saveSession(String sessionId, UserSession userSession) {
        try {
            String key = SESSION_PREFIX + sessionId;
            String value = objectMapper.writeValueAsString(userSession);
            redisTemplate.opsForValue().set(key, value, SESSION_EXPIRATION, TimeUnit.SECONDS);
            
            // RDB에도 세션 정보 저장
            LocalDateTime now = LocalDateTime.now();
            Session session = new Session();
            session.setSessionId(sessionId);
            session.setUser(userRepository.findById(userSession.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found")));
            session.setExpiresAt(now.plusSeconds(SESSION_EXPIRATION));
            session.setLastAccessedAt(now);  // 마지막 접근 시간 설정
            
            userSessionRepository.save(session);
            
            // 사용자를 온라인 상태로 표시
            setUserOnline(userSession.getUserId().toString());
            
            log.info("Session saved successfully for user: {}", userSession.getUsername());
        } catch (Exception e) {
            log.error("Error saving session to Redis", e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    public UserSession getSession(String sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            String value = redisTemplate.opsForValue().get(key);
            
            if (value == null) {
                log.info("Cache miss for session: {}, attempting to load from RDB", sessionId);
                // RDB에서 세션 정보 조회
                Session session = userSessionRepository.findBySessionId(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found in database"));
                
                // RDB에서 사용자 정보 조회
                User user = userRepository.findById(session.getUser().getId())
                        .orElseThrow(() -> new RuntimeException("User not found in database"));
                
                // 새로운 세션 객체 생성
                UserSession userSession = UserSession.of(
                    user.getId(),
                    user.getUsername(),
                    user.getEmail()
                );
                
                // Redis 캐시에 저장 (다음 요청을 위해)
                String sessionValue = objectMapper.writeValueAsString(userSession);
                redisTemplate.opsForValue().set(key, sessionValue, SESSION_EXPIRATION, TimeUnit.SECONDS);
                
                // 사용자를 온라인 상태로 표시
                setUserOnline(userSession.getUserId().toString());
                
                log.info("Session recreated from RDB and cached in Redis: {}", sessionId);
                return userSession;
            }

            UserSession userSession = objectMapper.readValue(value, UserSession.class);
            
            // Redis에 세션이 있으면 만료 시간 갱신
            redisTemplate.expire(key, SESSION_EXPIRATION, TimeUnit.SECONDS);
            
            // 사용자를 온라인 상태로 표시
            setUserOnline(userSession.getUserId().toString());
            
            return userSession;
        } catch (Exception e) {
            log.error("Error retrieving session from Redis", e);
            throw new RuntimeException("Failed to retrieve session", e);
        }
    }

    @Transactional
    public void deleteSession(String sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            redisTemplate.delete(key);
            
            // RDB에서도 세션 삭제
            userSessionRepository.deleteBySessionId(sessionId);
            
            log.info("Session deleted successfully: {}", sessionId);
        } catch (Exception e) {
            log.error("Error deleting session from Redis", e);
            throw new RuntimeException("Failed to delete session", e);
        }
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
} 