package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;

// === 추가 import for debugging ===
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

// === 추가 import for subscription debugging ===
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import java.lang.reflect.Field;

/**
 * 이웃 매칭을 관리하는 서비스 클래스
 * 사용자 간의 매칭 생성, 수락, 거절 등의 기능을 제공합니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {

    // 알림 유형 상수 정의
    private static final String NOTIFICATION_TYPE_MATCH_OFFERED = "MATCH_OFFERED";
    private static final String NOTIFICATION_TYPE_MATCH_ACCEPTED_BY_OTHER = "MATCH_ACCEPTED_BY_OTHER";
    private static final String NOTIFICATION_TYPE_MATCH_REJECTED_BY_OTHER = "MATCH_REJECTED_BY_OTHER";
    private static final String NOTIFICATION_TYPE_MATCH_COMPLETED_AND_CHAT_CREATED = "MATCH_COMPLETED_AND_CHAT_CREATED";
    private static final String NOTIFICATION_TYPE_ERROR = "ERROR"; // 예시 에러 타입

    /**
     * 사용자 정보를 관리하는 리포지토리
     */
    private final UserRepository userRepository;

    /**
     * 매칭 정보를 관리하는 리포지토리
     */
    private final MatchRepository matchRepository;

    /**
     * 매칭 선호도 정보를 관리하는 리포지토리
     */
    private final MatchingPreferencesRepository matchingPreferencesRepository;

    /**
     * WebSocket 메시지를 전송하기 위한 템플릿
     */
    private final SimpMessagingTemplate messagingTemplate;

    private final RedisSessionService redisSessionService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final OfflineNotificationService offlineNotificationService;
    
    // === 디버깅을 위한 ApplicationContext 추가 ===
    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    @Transactional
    public void deleteExpiredMatchesOnStartup() {
        log.info("Deleting expired matches on startup...");
        LocalDateTime now = LocalDateTime.now();
        matchRepository.deleteByExpiresAtBefore(now);
        log.info("Finished deleting expired matches.");
    }

    /**
     * 사용자의 매칭 선호도를 저장합니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param userId 사용자 ID
     */
    @Transactional
    public void saveMatchingPreferences(MatchingPreferencesDto preferences, Long userId) {
        User user = getUserById(userId);

        // 프로필 완성도 검사
        if (!user.isProfileComplete()) {
            throw new MatchingException("프로필을 먼저 설정해야 매칭 환경설정을 저장할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        
        // 기존 설정이 있으면 업데이트, 없으면 새로 생성
        MatchingPreferences matchingPreferences = matchingPreferencesRepository
            .findByUserId(userId)
            .orElseGet(() -> {
                MatchingPreferences p = new MatchingPreferences();
                p.setUser(user);
                return p;
            });
        
        // 엔티티 필드 업데이트
        matchingPreferences.setMaxDistance(preferences.getMaxDistance());
        matchingPreferences.setMinAge(preferences.getAgeRange()[0]);
        matchingPreferences.setMaxAge(preferences.getAgeRange()[1]);
        matchingPreferences.setPreferredGender(preferences.getGender());
        matchingPreferences.setPreferredInterests(preferences.getInterests());
        matchingPreferencesRepository.save(matchingPreferences);
    }

    /**
     * 매칭을 시작하고 주변 사용자들에게 매칭 요청을 보냅니다.
     * 생성된 각 매칭에 대한 정보(상대방 프로필, matchId)를 요청자에게 반환합니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param userId 사용자 ID
     * @return 생성된 매칭들의 프로필 정보 목록 (상대방 정보와 matchId 포함)
     */
    @Transactional
    public List<MatchProfileDto> startMatching(MatchingPreferencesDto preferences, Long userId) {
        log.info("[startMatching] Request received for userId: {}", userId);
        User user = getUserById(userId);

        // 사용자의 위치 정보 로깅 추가
        log.info("[startMatching] User details - ID: {}, Username: {}, Latitude: {}, Longitude: {}, Address: {}", 
                 user.getId(), user.getUsername(), user.getLatitude(), user.getLongitude(), user.getAddress());

        if (user.getLatitude() == null || user.getLongitude() == null) {
            log.warn("[startMatching] User ID: {} has null latitude or longitude. Cannot proceed with location-based matching.", userId);
            return Collections.emptyList(); // 위치 정보 없으면 빈 목록 반환
        }

        // 프로필 완성도 검사
        if (!user.isProfileComplete()) {
            throw new MatchingException("프로필을 먼저 설정해야 매칭 기능을 이용할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        
        // 기존 매칭 중지
        stopMatching(userId);
        
        // 새로운 매칭 선호도 저장
        saveMatchingPreferences(preferences, userId);
        
        // 주변 사용자 검색 및 매칭 요청
        List<User> allNearbyUsers = userRepository.findNearbyUsers(
            user.getLatitude(), 
            user.getLongitude(), 
            preferences.getMaxDistance() // 사용자가 설정한 maxDistance 사용
        );

        // 자기 자신을 제외한 주변 사용자 목록 필터링
        final Long currentUserId = user.getId();
        
        // 이미 1:1 채팅방이 있는 사용자들 조회
        List<Long> existingChatPartnerIds = chatService.getUsersWithOneOnOneChatRooms(currentUserId);
        log.info("[startMatching] User {} already has 1:1 chat rooms with {} users: {}", currentUserId, existingChatPartnerIds.size(), existingChatPartnerIds);
        
        List<User> nearbyUsersToMatch = allNearbyUsers.stream()
                .filter(nearbyUser -> !nearbyUser.getId().equals(currentUserId)) // 자기 자신 제외
                .filter(nearbyUser -> !existingChatPartnerIds.contains(nearbyUser.getId())) // 이미 채팅방이 있는 사용자 제외
                .collect(Collectors.toList());

        if (nearbyUsersToMatch.isEmpty()) {
            if (existingChatPartnerIds.isEmpty()) {
                log.info("[startMatching] No other users found nearby for userId: {}. Returning empty list.", userId);
            } else {
                log.info("[startMatching] No new users to match for userId: {}. All nearby users ({}) already have existing chat rooms. Returning empty list.", 
                         userId, allNearbyUsers.size() - 1); // -1 for excluding self
            }
            return Collections.emptyList(); // 주변에 새로운 매칭 가능한 사용자가 없으면 빈 목록 반환
        }
        
        log.info("[startMatching] Found {} nearby users to match (after excluding {} existing chat partners) for userId: {}", 
                 nearbyUsersToMatch.size(), existingChatPartnerIds.size(), userId);
        
        // 배치용 매칭 엔티티 생성
        List<Match> matches = new ArrayList<>();
        for (User nearbyUser : nearbyUsersToMatch) { // 필터링된 목록 사용
            Match match = new Match();
            match.setUser1(user);
            match.setUser2(nearbyUser);
            match.setStatus(MatchStatus.PENDING);
            match.setCreatedAt(LocalDateTime.now());
            match.setExpiresAt(LocalDateTime.now().plusHours(24)); // 24시간 후 만료
            matches.add(match);
        }
        
        // 일괄 저장
        List<Match> savedMatches = matchRepository.saveAll(matches);
        
        // 요청자에게 반환할 DTO 리스트
        List<MatchProfileDto> createdMatchProfilesForRequestor = new ArrayList<>(); 

        for (Match match : savedMatches) { // savedMatches를 직접 순회
            User matchedUser = match.getUser2();
            if (matchedUser == null) {
                log.warn("[startMatching] Matched user (user2) is null for matchId: {}. Skipping.", match.getId());
                continue;
            }
            // user 변수는 매칭 요청자(user1)입니다.
            double distance = calculateDistance(user.getLatitude(), user.getLongitude(), matchedUser.getLatitude(), matchedUser.getLongitude());
            
            MatchProfileDto profileForUser1 = MatchProfileDto.fromUser(matchedUser, distance, match.getId());
            createdMatchProfilesForRequestor.add(profileForUser1);

            // user2에게 보낼 매칭 제안 알림
            MatchProfileDto profileForUser2 = MatchProfileDto.fromUser(user, distance, match.getId());
            WebSocketNotification<MatchProfileDto> notification = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_OFFERED, 
                profileForUser2,
                String.format("'%s'님에게서 새로운 매칭 요청이 도착했습니다.", user.getUsername())
            );
            
            if (redisSessionService.isUserOnline(matchedUser.getId().toString())) { 
                String destination = "/queue/match-notifications";
                String targetUserIdStr = matchedUser.getId().toString();
                log.info("[startMatching] Attempting to send MATCH_OFFERED to online user: {} (userIdStr: {}), destination: {}, payload: {}", 
                         matchedUser.getId(), targetUserIdStr, destination, notification);
                
                // === 상세한 메시지 전송 로깅 추가 ===
                try {
                    log.info("[startMatching] About to call messagingTemplate.convertAndSendToUser with target: '{}', destination: '{}'", 
                             targetUserIdStr, destination);
                    log.info("[startMatching] Full notification object to send: {}", notification);
                    log.info("[startMatching] Notification serialized to JSON: {}", objectMapper.writeValueAsString(notification));
                    
                    // === 메시지 전송 전 WebSocket 세션 상태 확인 ===
                    log.info("[startMatching] Checking WebSocket sessions before sending message...");
                    
                    // === SimpleBroker 구독 상태 디버깅 추가 ===
                    try {
                        String sessionSpecificDestination = "/queue/match-notifications-user" + 
                            java.util.Optional.ofNullable(messagingTemplate)
                                .map(template -> template.toString())
                                .orElse("unknown");
                        log.info("[startMatching] Expected session-specific destination would be: /queue/match-notifications-user[sessionId]");
                        log.info("[startMatching] About to call convertAndSendToUser - if no STOMP encoding logs follow, the message is not being sent to any active session");
                        
                        // === SimpleBroker 빈 조회 및 구독 상태 확인 ===
                        try {
                            SimpleBrokerMessageHandler brokerHandler = applicationContext.getBean(SimpleBrokerMessageHandler.class);
                            log.info("[startMatching] SimpleBrokerMessageHandler found: {}", brokerHandler);
                            log.info("[startMatching] Target user ID: {}, Expected destination: /queue/match-notifications-user[sessionId]", targetUserIdStr);
                        } catch (Exception brokerEx) {
                            log.warn("[startMatching] Could not access SimpleBroker for debugging: {}", brokerEx.getMessage());
                        }
                    } catch (Exception e) {
                        log.warn("[startMatching] Error while checking subscription info: {}", e.getMessage());
                    }
                    
                    messagingTemplate.convertAndSendToUser(targetUserIdStr, destination, notification);
                    
                    log.info("[startMatching] messagingTemplate.convertAndSendToUser completed successfully for user: {}", targetUserIdStr);
                    log.info("[startMatching] MATCH_OFFERED sent to online user: {} (userIdStr: {})", matchedUser.getId(), targetUserIdStr);
                    
                    // === 메시지 전송 후 확인 ===
                    log.info("[startMatching] Message sending operation completed. If no further TRACE logs appear from Spring messaging, the message may not have been delivered.");
                    
                    // === 대안적인 메시지 전송 시도 ===
                    log.info("[startMatching] Attempting alternative message delivery method...");
                    sendNotificationViaAlternativeMethod(targetUserIdStr, notification);
                    
                } catch (Exception e) {
                    log.error("[startMatching] CRITICAL ERROR: Failed to send MATCH_OFFERED to user: {}. Error: {}", targetUserIdStr, e.getMessage(), e);
                }
                // === 상세한 메시지 전송 로깅 끝 ===
            } else {
                log.info("[startMatching] Matched userId: {} (username: {}) is OFFLINE. Saving offline notification.", matchedUser.getId(), matchedUser.getUsername());
                
                try {
                    // 오프라인 알림 저장
                    String notificationData = objectMapper.writeValueAsString(profileForUser2);
                    offlineNotificationService.saveOfflineNotification(
                        matchedUser.getId(),
                        com.talkwithneighbors.entity.OfflineNotification.NotificationType.MATCH_REQUEST,
                        notificationData,
                        String.format("'%s'님에게서 새로운 매칭 요청이 도착했습니다.", user.getUsername()),
                        null,
                        10 // 매칭 요청은 높은 우선순위
                    );
                    log.info("[startMatching] Saved offline match notification for userId: {}", matchedUser.getId());
                } catch (Exception e) {
                    log.error("[startMatching] Failed to save offline match notification for userId {}: {}", matchedUser.getId(), e.getMessage(), e);
                }
                
                // 기존 Redis 저장 방식도 유지 (호환성)
                redisSessionService.savePendingMatch(matchedUser.getId().toString(), match.getId().toString());
            }
        }
        log.info("[startMatching] Created {} matches for userId: {}. Returning profiles to requestor.", createdMatchProfilesForRequestor.size(), userId);
        return createdMatchProfilesForRequestor;
    }

    /**
     * 사용자가 온라인 상태가 되었을 때 대기 중인 매칭 요청을 처리합니다.
     */
    @Transactional
    public void processPendingMatches(Long userId) {
        List<String> pendingMatchIds = redisSessionService.getPendingMatches(userId.toString());
        
        if (pendingMatchIds != null && !pendingMatchIds.isEmpty()) {
            log.info("[processPendingMatches] Found {} pending matches for userId: {}", pendingMatchIds.size(), userId);
            
            for (String matchIdFromRedis : pendingMatchIds) {
                Match match = matchRepository.findById(matchIdFromRedis)
                        .orElseGet(() -> {
                            log.warn("[processPendingMatches] Match not found with ID: {}. Skipping.", matchIdFromRedis);
                            return null; 
                        });

                if (match == null) {
                    log.warn("[processPendingMatches] Match not found for matchIdFromRedis: {}. Skipping.", matchIdFromRedis);
                    continue; 
                }
                
                if (match.getStatus() == MatchStatus.PENDING && 
                    !LocalDateTime.now().isAfter(match.getExpiresAt())) {
                    
                    User offeringUser = match.getUser1().getId().equals(userId) ? match.getUser2() : match.getUser1();
                    User receivingUser = match.getUser1().getId().equals(userId) ? match.getUser1() : match.getUser2();

                    if (offeringUser == null || receivingUser == null) {
                         log.warn("[processPendingMatches] Offering user or receiving user is null for matchId: {}. Skipping.", match.getId());
                         continue;
                    }

                    double distance = calculateDistance(
                        offeringUser.getLatitude(), offeringUser.getLongitude(),
                        receivingUser.getLatitude(), receivingUser.getLongitude()
                    );
                    MatchProfileDto profileForReceiver = MatchProfileDto.fromUser(offeringUser, distance, match.getId());
                    
                    WebSocketNotification<MatchProfileDto> notification = new WebSocketNotification<>(
                        NOTIFICATION_TYPE_MATCH_OFFERED,
                        profileForReceiver,
                        String.format("이전에 '%s'님에게서 매칭 요청이 도착했었습니다. 지금 확인해보세요!", offeringUser.getUsername())
                    );
                    
                    String destination = "/queue/match-notifications";
                    String targetUserIdStr = receivingUser.getId().toString();

                    log.info("[processPendingMatches] Attempting to send PENDING MATCH_OFFERED to user: {} (userIdStr: {}), destination: {}, payload: {}",
                             receivingUser.getId(), targetUserIdStr, destination, notification);
                    messagingTemplate.convertAndSendToUser(
                        targetUserIdStr,
                        destination,
                        notification
                    );
                    log.info("[processPendingMatches] Sent PENDING MATCH_OFFERED to user: {} (userIdStr: {})", receivingUser.getId(), targetUserIdStr);
                } else {
                    if (match.getStatus() != MatchStatus.EXPIRED) { 
                        log.info("[processPendingMatches] Match ID: {} for user: {} is no longer PENDING or has expired. Status: {}. Skipping.",
                                 match.getId(), userId, match.getStatus());
                    }
                }
            }
        } else {
            log.info("[processPendingMatches] No pending matches found for userId: {}", userId);
        }
    }

    /**
     * 매칭을 중지하고 대기 중인 매칭들을 만료 처리합니다.
     * 
     * @param userId 사용자 ID
     */
    @Transactional
    public void stopMatching(Long userId) {
        User user = getUserById(userId);
        // 사용자가 매칭을 중단하는 경우, 해당 사용자와 관련된 PENDING 상태의 매칭들을 REJECTED 상태로 변경합니다.
        int updatedCount = matchRepository.bulkExpireMatches(
            MatchStatus.REJECTED, // newStatus (메서드의 expiredStatus 파라미터에 해당)
            LocalDateTime.now(),  // respondedAt
            userId,               // userId
            MatchStatus.PENDING   // oldStatus (메서드의 pendingStatus 파라미터에 해당)
        );
        log.info("[stopMatching] Rejected {} pending matches for user {}", updatedCount, userId);

        // Remove any pending match notifications for this user from Redis by calling getPendingMatches
        List<String> clearedMatches = redisSessionService.getPendingMatches(userId.toString()); // 호출하여 조회 및 삭제
        if (clearedMatches != null && !clearedMatches.isEmpty()) {
            log.info("[stopMatching] Cleared {} pending match notifications from Redis for user {}", clearedMatches.size(), userId);
        } else {
            log.info("[stopMatching] No pending match notifications found in Redis to clear for user {}", userId);
        }
    }

    /**
     * 매칭 요청을 수락합니다.
     * 
     * @param matchId 매칭 ID
     * @param userId 사용자 ID
     * @throws MatchingException 매칭이 없거나 이미 처리된 경우
     */
    @Transactional
    public void acceptMatch(String matchId, Long userId) {
        log.info("[acceptMatch] Starting acceptMatch for matchId: {} by userId: {}", matchId, userId);
        
        try {
            Match match = matchRepository.findById(matchId)
                    .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
            log.info("[acceptMatch] Found match: {} with status: {}", matchId, match.getStatus());

            // 해당 매칭에 사용자가 포함되어 있는지 확인
            if (!match.getUser1().getId().equals(userId) && !match.getUser2().getId().equals(userId)) {
                log.warn("[acceptMatch] User {} does not have permission for match {}", userId, matchId);
                throw new MatchingException("해당 매칭에 대한 권한이 없습니다.", HttpStatus.FORBIDDEN);
            }
            log.info("[acceptMatch] User {} has permission for match {}", userId, matchId);

            if (match.getStatus() == MatchStatus.BOTH_ACCEPTED || 
                match.getStatus() == MatchStatus.REJECTED || 
                match.getStatus() == MatchStatus.EXPIRED) {
                log.warn("[acceptMatch] Match {} is already processed. Status: {}", matchId, match.getStatus());
                throw new MatchingException("이미 처리되었거나 만료된 매칭입니다.", HttpStatus.BAD_REQUEST);
            }

            if (LocalDateTime.now().isAfter(match.getExpiresAt())) {
                log.warn("[acceptMatch] Match {} is expired. ExpiresAt: {}", matchId, match.getExpiresAt());
                match.setStatus(MatchStatus.EXPIRED);
                matchRepository.save(match);
                throw new MatchingException("만료된 매칭입니다.", HttpStatus.BAD_REQUEST);
            }
            log.info("[acceptMatch] Match {} is valid and can be processed", matchId);

            User currentUser = getUserById(userId);
            User otherUser = match.getUser1().getId().equals(userId) ? match.getUser2() : match.getUser1();
            log.info("[acceptMatch] CurrentUser: {} ({}), OtherUser: {} ({})", 
                     currentUser.getId(), currentUser.getUsername(), 
                     otherUser.getId(), otherUser.getUsername());

            if (otherUser == null) {
                log.error("[acceptMatch] Other user is null for matchId: {}. This should not happen.", matchId);
                throw new MatchingException("매칭 상대방 정보를 찾을 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            MatchStatus previousStatus = match.getStatus();
            MatchStatus newStatus = null; 
            log.info("[acceptMatch] Previous status: {}, determining new status...", previousStatus);

            if (match.getUser1().getId().equals(userId)) { // 현재 사용자가 user1
                if (previousStatus == MatchStatus.PENDING) {
                    newStatus = MatchStatus.USER1_ACCEPTED;
                } else if (previousStatus == MatchStatus.USER2_ACCEPTED) {
                    newStatus = MatchStatus.BOTH_ACCEPTED;
                }
            } else { // 현재 사용자가 user2
                if (previousStatus == MatchStatus.PENDING) {
                    newStatus = MatchStatus.USER2_ACCEPTED;
                } else if (previousStatus == MatchStatus.USER1_ACCEPTED) {
                    newStatus = MatchStatus.BOTH_ACCEPTED;
                }
            }
            log.info("[acceptMatch] Determined new status: {}", newStatus);
     
            if (newStatus == null) {
                log.error("[acceptMatch] Invalid status transition from {} for matchId: {}", previousStatus, matchId);
                throw new MatchingException("유효하지 않은 매칭 상태 변경 요청입니다. 현재 상태: " + previousStatus, HttpStatus.BAD_REQUEST);
            }
            
            match.setStatus(newStatus);
            match.setRespondedAt(LocalDateTime.now());
            Match savedMatch = matchRepository.save(match);
            log.info("Match status updated to {} for matchId: {} by userId: {}", newStatus, matchId, userId);

            // 알림 로직
            if (savedMatch.getStatus() == MatchStatus.USER1_ACCEPTED || savedMatch.getStatus() == MatchStatus.USER2_ACCEPTED) {
                // 한쪽만 수락한 경우, 상대방에게 알림
                String targetOtherUserIdStr = otherUser.getId().toString();

                Map<String, Object> notificationData = Map.of(
                    "matchId", savedMatch.getId(),
                    "accepterUserId", currentUser.getId(),
                    "accepterUsername", currentUser.getUsername(),
                    "accepterProfileImage", currentUser.getProfileImage() != null ? currentUser.getProfileImage() : ""
                );

                WebSocketNotification<Map<String, Object>> notificationToOtherUser = new WebSocketNotification<>(
                    NOTIFICATION_TYPE_MATCH_ACCEPTED_BY_OTHER,
                    notificationData,
                    String.format("'%s'님이 매칭을 수락했습니다!", currentUser.getUsername())
                );
                
                log.info("Sending MATCH_ACCEPTED_BY_OTHER to user: {} (userIdStr: {}), payload: {}", 
                         otherUser.getId(), targetOtherUserIdStr, notificationToOtherUser);
                
                if (redisSessionService.isUserOnline(otherUser.getId().toString())) {
                    // === 상세한 메시지 전송 로깅 추가 ===
                    try {
                        log.info("[acceptMatch] About to call messagingTemplate.convertAndSendToUser with target: '{}', destination: '{}'", 
                                 targetOtherUserIdStr, "/queue/match-notifications");
                        log.info("[acceptMatch] Full notification object to send: {}", notificationToOtherUser);
                        log.info("[acceptMatch] Notification serialized to JSON: {}", objectMapper.writeValueAsString(notificationToOtherUser));
                        
                        messagingTemplate.convertAndSendToUser(targetOtherUserIdStr, "/queue/match-notifications", notificationToOtherUser);
                        
                        log.info("[acceptMatch] messagingTemplate.convertAndSendToUser completed successfully for user: {}", targetOtherUserIdStr);
                        log.info("Sent MATCH_ACCEPTED_BY_OTHER notification to user: {} for matchId: {}", otherUser.getUsername(), matchId);
                        
                        // === 대안적인 메시지 전송 시도 ===
                        log.info("[acceptMatch] Attempting alternative message delivery method...");
                        sendNotificationViaAlternativeMethod(targetOtherUserIdStr, notificationToOtherUser);
                        
                    } catch (Exception e) {
                        log.error("[acceptMatch] CRITICAL ERROR: Failed to send MATCH_ACCEPTED_BY_OTHER to user: {}. Error: {}", targetOtherUserIdStr, e.getMessage(), e);
                    }
                    // === 상세한 메시지 전송 로깅 끝 ===
                } else {
                    log.info("User {} (userIdStr: {}) is offline. Saving MATCH_ACCEPTED_BY_OTHER notification for when they come back online.", 
                             otherUser.getUsername(), targetOtherUserIdStr);
                    
                    try {
                        // 오프라인 알림 저장
                        String notificationDataJson = objectMapper.writeValueAsString(notificationData);
                        offlineNotificationService.saveOfflineNotification(
                            otherUser.getId(),
                            com.talkwithneighbors.entity.OfflineNotification.NotificationType.MATCH_ACCEPTED,
                            notificationDataJson,
                            String.format("'%s'님이 매칭을 수락했습니다!", currentUser.getUsername()),
                            null,
                            10 // 매칭 수락은 높은 우선순위
                        );
                        log.info("Saved offline match accepted notification for userId: {}", otherUser.getId());
                    } catch (Exception e) {
                        log.error("Failed to save offline match accepted notification for userId {}: {}", otherUser.getId(), e.getMessage(), e);
                    }
                }
            } else if (savedMatch.getStatus() == MatchStatus.BOTH_ACCEPTED) {
                // 양쪽 모두 수락한 경우, 두 사용자에게 알림
                String targetCurrentUserIdStr = currentUser.getId().toString();
                String targetOtherUserIdStr = otherUser.getId().toString();
                
                try {
                    // 기존 1:1 채팅방이 있는지 확인
                    ChatRoomDto existingChatRoom = chatService.findOneOnOneChatRoom(currentUser.getId(), otherUser.getId());
                    
                    ChatRoomDto chatRoomDto;
                    String navigateToChatPath;
                    
                    if (existingChatRoom != null) {
                        // 기존 채팅방 재활용
                        chatRoomDto = existingChatRoom;
                        log.info("Reusing existing chat room for match {}: roomId={}, roomName='{}'", matchId, chatRoomDto.getId(), chatRoomDto.getRoomName());
                    } else {
                        // 새 채팅방 생성
                        String roomName = String.format("%s님과 %s님의 대화", currentUser.getUsername(), otherUser.getUsername());
                        chatRoomDto = chatService.createRoom(
                            roomName,
                            ChatRoomType.ONE_ON_ONE,
                            currentUser.getId().toString(),
                            List.of(otherUser.getUsername())
                        );
                        log.info("New chat room created for match {}: roomId={}, roomName='{}'", matchId, chatRoomDto.getId(), chatRoomDto.getRoomName());
                    }
                    
                    navigateToChatPath = "/chat/" + chatRoomDto.getId();

                    WebSocketNotification<ChatRoomDto> chatNotification = new WebSocketNotification<>(
                        NOTIFICATION_TYPE_MATCH_COMPLETED_AND_CHAT_CREATED,
                        chatRoomDto,
                        "매칭이 성사되어 채팅방이 생성되었습니다!",
                        navigateToChatPath
                    );
                    messagingTemplate.convertAndSendToUser(targetCurrentUserIdStr, "/queue/match-notifications", chatNotification);
                    
                    messagingTemplate.convertAndSendToUser(targetOtherUserIdStr, "/queue/match-notifications", chatNotification);
                } catch (Exception e) {
                    log.error("Failed to create chat room for match {}: {}", matchId, e.getMessage(), e);
                    throw new MatchingException("매칭은 성사되었으나 채팅방 생성에 실패했습니다. 관리자에게 문의하세요.", HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        } catch (Exception e) {
            log.error("[acceptMatch] CRITICAL ERROR: Failed to process acceptMatch: {}", e.getMessage(), e);
            throw new MatchingException("매칭 처리 중 오류가 발생했습니다. 관리자에게 문의하세요.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 매칭 요청을 거절합니다.
     * 
     * @param matchId 매칭 ID
     * @param userId 사용자 ID
     * @throws MatchingException 매칭이 없거나 이미 처리된 경우
     */
    @Transactional
    public void rejectMatch(String matchId, Long userId) {
        log.info("[rejectMatch] Starting rejectMatch for matchId: {} by userId: {}", matchId, userId);
        
        try {
            Match match = matchRepository.findById(matchId)
                    .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
            log.info("[rejectMatch] Found match: {} with status: {}", matchId, match.getStatus());

            // 해당 매칭에 사용자가 포함되어 있는지 확인
            if (!match.getUser1().getId().equals(userId) && !match.getUser2().getId().equals(userId)) {
                log.warn("[rejectMatch] User {} does not have permission for match {}", userId, matchId);
                throw new MatchingException("해당 매칭에 대한 권한이 없습니다.", HttpStatus.FORBIDDEN);
            }
            log.info("[rejectMatch] User {} has permission for match {}", userId, matchId);

            if (match.getStatus() == MatchStatus.BOTH_ACCEPTED || 
                match.getStatus() == MatchStatus.REJECTED || 
                match.getStatus() == MatchStatus.EXPIRED) {
                log.warn("[rejectMatch] Match {} is already processed. Status: {}", matchId, match.getStatus());
                throw new MatchingException("이미 처리되었거나 만료된 매칭입니다.", HttpStatus.BAD_REQUEST);
            }
            
            if (LocalDateTime.now().isAfter(match.getExpiresAt())) {
                log.warn("[rejectMatch] Match {} is expired. ExpiresAt: {}", matchId, match.getExpiresAt());
                match.setStatus(MatchStatus.EXPIRED);
                matchRepository.save(match);
                throw new MatchingException("만료된 매칭입니다.", HttpStatus.BAD_REQUEST);
            }
            log.info("[rejectMatch] Match {} is valid and can be processed", matchId);

            match.setStatus(MatchStatus.REJECTED);
            match.setRespondedAt(LocalDateTime.now());
            Match savedMatch = matchRepository.save(match);
            log.info("Match rejected by userId: {}. MatchId: {}", userId, matchId);

            User currentUser = getUserById(userId);
            User otherUser = match.getUser1().getId().equals(userId) 
                    ? match.getUser2() : match.getUser1();
            log.info("[rejectMatch] CurrentUser: {} ({}), OtherUser: {} ({})", 
                     currentUser.getId(), currentUser.getUsername(), 
                     otherUser != null ? otherUser.getId() : "null", 
                     otherUser != null ? otherUser.getUsername() : "null");
            
            if (otherUser == null) {
                log.warn("[rejectMatch] Other user is null for matchId: {}. Cannot send rejection notification.", matchId);
                return; // 상대방이 없으면 알림을 보낼 수 없음
            }

            // 알림 페이로드에는 원래 사용자 이름 사용
            String targetOtherUserIdStr = otherUser.getId().toString();

            Map<String, Object> notificationData = Map.of(
                "matchId", savedMatch.getId(),
                "rejectorUserId", currentUser.getId(),
                "rejectorUsername", currentUser.getUsername(),
                "rejectorProfileImage", currentUser.getProfileImage() != null ? currentUser.getProfileImage() : ""
            );
            
            WebSocketNotification<Map<String, Object>> notificationToOtherUser = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_REJECTED_BY_OTHER,
                notificationData,
                String.format("'%s'님이 매칭을 거절했습니다.", currentUser.getUsername())
            );

            log.info("Sending MATCH_REJECTED_BY_OTHER to user: {} (userIdStr: {}), payload: {}", 
                     otherUser.getId(), targetOtherUserIdStr, notificationToOtherUser);

            if (redisSessionService.isUserOnline(otherUser.getId().toString())) {
                messagingTemplate.convertAndSendToUser(
                    targetOtherUserIdStr,
                    "/queue/match-notifications",
                    notificationToOtherUser
                );
                log.info("Sent MATCH_REJECTED_BY_OTHER notification to user: {} for matchId: {}", otherUser.getUsername(), savedMatch.getId());
            } else {
                log.info("User {} (userIdStr: {}) is offline. Saving MATCH_REJECTED_BY_OTHER notification for when they come back online.", 
                         otherUser.getUsername(), targetOtherUserIdStr);
                
                try {
                    // 오프라인 알림 저장
                    String notificationDataJson = objectMapper.writeValueAsString(notificationData);
                    offlineNotificationService.saveOfflineNotification(
                        otherUser.getId(),
                        com.talkwithneighbors.entity.OfflineNotification.NotificationType.MATCH_REJECTED,
                        notificationDataJson,
                        String.format("'%s'님이 매칭을 거절했습니다.", currentUser.getUsername()),
                        null,
                        8 // 매칭 거절은 중간 우선순위
                    );
                    log.info("Saved offline match rejected notification for userId: {}", otherUser.getId());
                } catch (Exception e) {
                    log.error("Failed to save offline match rejected notification for userId {}: {}", otherUser.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("[rejectMatch] CRITICAL ERROR: Failed to process rejectMatch: {}", e.getMessage(), e);
            throw new MatchingException("매칭 처리 중 오류가 발생했습니다. 관리자에게 문의하세요.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 주변 사용자들을 검색합니다.
     * 
     * @param latitude 위도
     * @param longitude 경도
     * @param radius 검색 반경 (킬로미터)
     * @param userId 현재 사용자 ID
     * @return 주변 사용자 프로필 목록
     */
    @Transactional(readOnly = true)
    public List<MatchProfileDto> searchNearbyUsers(Double latitude, Double longitude, Double radius, Long userId) {
        if (latitude == null || longitude == null || radius == null) {
            throw new IllegalArgumentException("Latitude, longitude, and radius must not be null.");
        }
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new MatchingException("현재 사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        List<User> nearbyUsers = userRepository.findNearbyUsers(latitude, longitude, radius);
        return nearbyUsers.stream()
            .filter(user -> !user.getId().equals(userId))
            .map(user -> MatchProfileDto.fromUser(user, calculateDistance(latitude, longitude, user.getLatitude(), user.getLongitude()), null))
            .collect(Collectors.toList());
    }

    /**
     * 사용자와 매칭 가능한 주변 사용자들을 찾습니다.
     * 
     * @param user 현재 사용자
     * @param preferences 매칭 선호도 정보
     * @return 매칭 가능한 사용자 목록
     */
    private List<User> findPotentialMatches(User user, MatchingPreferences preferences) {
        // 사용자의 매칭 선호도에 맞는 주변 사용자 검색
        List<User> nearbyUsers = userRepository.findNearbyUsers(
                user.getLatitude(),
                user.getLongitude(),
                preferences.getMaxDistance()
        );

        return nearbyUsers.stream()
                .filter(potentialMatch -> 
                    // 기본 필터링
                    !potentialMatch.getId().equals(user.getId()) &&
                    (preferences.getPreferredGender() == null || 
                     preferences.getPreferredGender().equals(potentialMatch.getGender())) &&
                    potentialMatch.getAge() >= preferences.getMinAge() &&
                    potentialMatch.getAge() <= preferences.getMaxAge() &&
                    (potentialMatch.getGender() == null || 
                     potentialMatch.getGender().equals(user.getGender())) &&
                    user.getAge() >= preferences.getMinAge() &&
                    user.getAge() <= preferences.getMaxAge() &&
                    // 관심사 매칭 점수 계산
                    calculateInterestScore(preferences, potentialMatch) >= 0.5
                )
                .sorted((u1, u2) -> {
                    double score1 = calculateInterestScore(preferences, u1);
                    double score2 = calculateInterestScore(preferences, u2);
                    return Double.compare(score2, score1); // 높은 점수순 정렬
                })
                .limit(10) // 상위 10명만 선택
                .collect(Collectors.toList());
    }

    /**
     * 두 사용자 간의 관심사 매칭 점수를 계산합니다.
     * Jaccard 유사도를 사용하여 계산합니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param user 사용자
     * @return 매칭 점수 (0.0 ~ 1.0)
     */
    private double calculateInterestScore(MatchingPreferences preferences, User user) {
        if (preferences.getPreferredInterests() == null || user.getInterests() == null) {
            return 0.0;
        }

        Set<String> interests1 = new HashSet<>(preferences.getPreferredInterests());
        Set<String> interests2 = new HashSet<>(user.getInterests());

        if (interests1.isEmpty() || interests2.isEmpty()) {
            return 0.0;
        }

        // Jaccard 유사도 계산
        Set<String> intersection = new HashSet<>(interests1);
        intersection.retainAll(interests2);
        Set<String> union = new HashSet<>(interests1);
        union.addAll(interests2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * ID로 사용자를 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 사용자 정보
     * @throws MatchingException 사용자를 찾을 수 없는 경우
     */
    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MatchingException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    /**
     * 두 지점 간의 거리를 계산합니다.
     * Haversine 공식을 사용하여 지구 표면의 거리를 계산합니다.
     * 
     * @param lat1 첫 번째 지점의 위도
     * @param lon1 첫 번째 지점의 경도
     * @param lat2 두 번째 지점의 위도
     * @param lon2 두 번째 지점의 경도
     * @return 두 지점 간의 거리 (킬로미터)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 지구의 반경 (km)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public List<MatchProfileDto> searchNearbyUsers(User user, double radius) {
        // 사용자의 현재 위치 저장
        user.setLastLocationUpdate(LocalDateTime.now());
        userRepository.save(user);

        // 주변 사용자 검색
        double lat = user.getLatitude();
        double lng = user.getLongitude();
        
        // 위도/경도 범위 계산 (약 111km = 1도)
        double latDelta = radius / 111.0;
        double lngDelta = radius / (111.0 * Math.cos(Math.toRadians(lat)));
        
        List<User> nearbyUsers = userRepository.findByLatitudeBetweenAndLongitudeBetween(
            lat - latDelta, lat + latDelta,
            lng - lngDelta, lng + lngDelta
        );

        // 현재 사용자 제외 및 거리 계산
        return nearbyUsers.stream()
            .filter(u -> !u.getId().equals(user.getId()))
            .map(u -> {
                double distance = calculateDistance(
                    lat, lng,
                    u.getLatitude(), u.getLongitude()
                );
                if (distance <= radius) {
                    return MatchProfileDto.fromUser(u, distance);
                }
                return null;
            })
            .filter(dto -> dto != null)
            .collect(Collectors.toList());
    }

    // === 대안적인 메시지 전송 메서드 ===
    private void sendNotificationViaAlternativeMethod(String targetUserIdStr, WebSocketNotification<?> notification) {
        try {
            // 직접 토픽으로 메시지 전송 (사용자 특정 토픽)
            String alternativeDestination = "/topic/match-event-" + targetUserIdStr;
            log.info("[sendNotificationViaAlternativeMethod] Sending to alternative destination: {}", alternativeDestination);
            log.info("[sendNotificationViaAlternativeMethod] Payload: {}", notification);
            
            messagingTemplate.convertAndSend(alternativeDestination, notification);
            log.info("[sendNotificationViaAlternativeMethod] Message sent successfully to {}", alternativeDestination);
            
        } catch (Exception e) {
            log.error("[sendNotificationViaAlternativeMethod] Failed to send via alternative method: {}", e.getMessage(), e);
        }
    }
} 