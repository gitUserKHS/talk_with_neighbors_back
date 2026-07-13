package com.talkwithneighbors.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.repository.OfflineNotificationRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.EnumSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class OfflineNotificationServiceImpl implements OfflineNotificationService {
    
    private final OfflineNotificationRepository offlineNotificationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry simpUserRegistry;
    private final ObjectMapper objectMapper;
    private final RedisSessionService redisSessionService;
    private final com.talkwithneighbors.repository.UserRepository userRepository;
    
    @Override
    @Transactional
    public OfflineNotification saveOfflineNotification(Long userId, 
                                       OfflineNotification.NotificationType type, 
                                       String data, 
                                       String message, 
                                       String actionUrl, 
                                       Integer priority) {
        if (!notificationEnabled(userId, type)) {
            log.debug("Notification disabled by user preference. userId={}, type={}", userId, type);
            return null;
        }
        log.info("=== [OfflineNotificationService] saveOfflineNotification START ===");
        log.info("[OfflineNotificationService] userId: {}, type: {}, priority: {}", userId, type, priority);
        log.info("[OfflineNotificationService] message: {}", message);
        log.info("[OfflineNotificationService] data: {}", data);
        
        try {
            // 중복 알림 확인 (같은 타입, 같은 데이터의 알림이 이미 있는지)
            log.info("[OfflineNotificationService] Checking for duplicate notifications...");
            List<OfflineNotification> duplicates = offlineNotificationRepository
                    .findDuplicateNotifications(userId, type, data);
            
            if (!duplicates.isEmpty()) {
                log.info("[OfflineNotificationService] ⚠️ Duplicate notification found for userId: {}, type: {}. Count: {}. Skipping save.", userId, type, duplicates.size());
                return duplicates.get(0);
            }
            log.info("[OfflineNotificationService] ✅ No duplicates found. Proceeding with save...");
            
            OfflineNotification notification = new OfflineNotification();
            notification.setUserId(userId);
            notification.setType(type);
            notification.setData(data);
            notification.setMessage(message);
            notification.setActionUrl(actionUrl);
            notification.setPriority(priority != null ? priority : getDefaultPriority(type));
            
            log.info("[OfflineNotificationService] 💾 Attempting to save notification entity...");
            OfflineNotification savedNotification = offlineNotificationRepository.save(notification);
            log.info("[OfflineNotificationService] 🎉 Successfully saved notification entity with ID: {}", savedNotification.getId());
            
            log.info("[OfflineNotificationService] ✅ Successfully saved offline notification: id={}, userId={}, type={}", 
                     savedNotification.getId(), userId, type);
            return savedNotification;
        } catch (Exception e) {
            log.error("[OfflineNotificationService] ❌ CRITICAL ERROR: Failed to save offline notification for userId: {}, type: {}: {}", 
                      userId, type, e.getMessage(), e);
            throw e; // 예외를 다시 던져서 상위에서 확인할 수 있도록
        }
    }

    private boolean notificationEnabled(Long userId, OfflineNotification.NotificationType type) {
        if (userRepository == null) return true;
        return userRepository.findById(userId).map(user -> switch (type) {
            case MATCH_REQUEST, MATCH_ACCEPTED, MATCH_REJECTED -> !Boolean.FALSE.equals(user.getMatchNotificationsEnabled());
            case NEW_MESSAGE, CHAT_ROOM_LIST_UPDATE, UNREAD_COUNT_UPDATE, MESSAGE_READ_STATUS, ROOM_DELETED ->
                    !Boolean.FALSE.equals(user.getChatNotificationsEnabled());
            case MEETUP_REMINDER, MEETUP_WAITLIST_PROMOTED -> !Boolean.FALSE.equals(user.getMeetupNotificationsEnabled());
            case SYSTEM_NOTICE -> true;
        }).orElse(true);
    }
    
    @Override
    @Async
    @Transactional
    public void sendPendingNotifications(Long userId) {
        log.info("[OfflineNotificationService] Sending pending notifications for userId: {}", userId);
        
        // 사용자가 실제로 온라인인지 확인
        if (!redisSessionService.isUserOnline(userId.toString())) {
            log.warn("[OfflineNotificationService] User {} is not online, skipping notification delivery", userId);
            return;
        }
        
        try {
            // 만료되지 않은 미전송 알림들을 우선순위 순으로 조회
            List<OfflineNotification> pendingNotifications = offlineNotificationRepository
                    .findPendingNotificationsByUserId(userId, LocalDateTime.now());
            
            log.info("[OfflineNotificationService] Found {} pending notifications for userId: {}", 
                     pendingNotifications.size(), userId);
            
            int successCount = 0;
            int errorCount = 0;
            
            // 전송할 알림 타입 정의
            Set<OfflineNotification.NotificationType> allowedTypes =
                    EnumSet.allOf(OfflineNotification.NotificationType.class);
            
            for (OfflineNotification notification : pendingNotifications) {
                // 허용된 타입의 알림인지 확인
                if (!allowedTypes.contains(notification.getType())) {
                    log.info("[OfflineNotificationService] Skipping notification id: {} of type: {} as it's not in the allowed list for pending delivery.", notification.getId(), notification.getType());
                    continue;
                }

                try {
                    String destination = getWebSocketDestination(notification.getType());
                    // 추가된 로그: 전송 직전의 destination 및 메시지 타입 확인
                    log.info("[OfflineNotificationService] Preparing to send notification id: {}, type: {}, to destination: '{}' for userId: {}", 
                             notification.getId(), notification.getType(), destination, userId);

                    // WebSocket을 통해 알림 전송
                    Map<String, Object> message = new HashMap<>();
                    message.put("id", notification.getId());
                    message.put("type", notification.getType().name());
                    message.put("data", objectMapper.readValue(notification.getData(), Map.class));
                    message.put("message", notification.getMessage());
                    message.put("actionUrl", notification.getActionUrl());
                    message.put("createdAt", notification.getCreatedAt());
                    message.put("priority", notification.getPriority());
                    
                    // 실제 전송 시도
                    boolean sendSuccess = false;
                    
                    // 디버깅을 위해 WebSocket 세션 체크 임시 비활성화 주석 처리
                    // log.info("[OfflineNotificationService] 🚀 Forcing message send for debugging purposes (bypassing session check)");
                    
                    // 먼저 WebSocket 세션이 활성 상태인지 확인
                    if (!hasActiveWebSocketSession(userId.toString())) {
                        log.warn("[OfflineNotificationService] No active WebSocket session for user {}, skipping notification id: {}", 
                                 userId, notification.getId());
                        errorCount++;
                        continue;
                    }
                    
                    try {
                        log.info("[OfflineNotificationService] 📤 Attempting to send message:");
                        log.info("  - Target userId: {}", userId.toString());
                        log.info("  - Destination: {}", destination);
                        log.info("  - Message type: {}", notification.getType());
                        log.info("  - Message content: {}", message);
                        
                        messagingTemplate.convertAndSendToUser(
                            userId.toString(), 
                            destination, 
                            message
                        );
                        
                        sendSuccess = true;
                        log.info("[OfflineNotificationService] ✅ WebSocket message sent successfully to userId: {}, destination: {}, type: {}", 
                                  userId, destination, notification.getType());
                        
                    } catch (Exception e) {
                        log.error("[OfflineNotificationService] ❌ Failed to send WebSocket message - userId: {}, destination: {}, error: {}", 
                                  userId, destination, e.getMessage(), e);
                        sendSuccess = false;
                    }
                    
                    if (sendSuccess) {
                        // 알림 전송 성공 시 isSent = true로 업데이트
                        try {
                            markNotificationAsSent(notification.getId());
                            successCount++;
                            log.info("[OfflineNotificationService] Successfully sent and marked notification as sent: id={}, type={}",
                                notification.getId(), notification.getType());
                        } catch (Exception e) {
                            log.error("[OfflineNotificationService] ⚠️ Failed to mark notification as sent: id={}, error: {}. Keeping for retry.",
                                notification.getId(), e.getMessage(), e);
                            // isSent 업데이트에 실패하더라도 일단 successCount는 증가시키고 로그를 남김
                            // (이미 전송은 되었으므로)
                            // 다만, 이 경우 cleanup 시 삭제되지 않고 재시도될 수 있음
                            successCount++; 
                            // 만약 markNotificationAsSent 실패 시 재시도 횟수를 늘리고 싶다면 아래 로직 추가
                            // offlineNotificationRepository.incrementRetryCount(notification.getId());
                            // errorCount++; // 이전에 이 부분이 있었으나, 이미 successCount를 증가시켰으므로 주석 유지 또는 로직 재검토 필요
                        }
                    } else {
                        // 알림 전송 실패 시 재시도 횟수 증가
                        try {
                            offlineNotificationRepository.incrementRetryCount(notification.getId());
                            log.warn("[OfflineNotificationService] Failed to send notification id: {}. Incremented retry count.", 
                                     notification.getId());
                        } catch (Exception e) {
                            log.error("[OfflineNotificationService] ⚠️ Failed to increment retry count for notification id: {}: {}",
                                notification.getId(), e.getMessage(), e);
                        }
                        errorCount++;
                    }
                    
                } catch (Exception e) {
                    log.error("[OfflineNotificationService] Error processing notification id: {}", 
                              notification.getId(), e);
                    errorCount++;
                }
            }
            
            log.info("[OfflineNotificationService] Notification sending completed for userId: {}. Success: {}, Error: {}", 
                     userId, successCount, errorCount);
            
            // 전송 완료 요약 알림 (성공한 경우에만)
            if (successCount > 0) {
                sendNotificationSummary(userId, successCount);
            }
            
        } catch (Exception e) {
            log.error("[OfflineNotificationService] Error sending pending notifications for userId: {}", userId, e);
        }
    }
    
    /**
     * 사용자에게 활성 WebSocket 세션이 있는지 확인
     */
    private boolean hasActiveWebSocketSession(String userId) {
        try {
            log.info("[OfflineNotificationService] 🔍 Checking WebSocket session for userId: {}", userId);

            // SimpUserRegistry를 사용하여 실제 연결된 사용자 확인
            org.springframework.messaging.simp.user.SimpUser simpUser = simpUserRegistry.getUser(userId);
            boolean hasActiveSession = simpUser != null && !simpUser.getSessions().isEmpty();

            // 추가 상세 정보 로깅
            if (simpUserRegistry != null) {
                int totalUsers = simpUserRegistry.getUserCount();
                log.info("[OfflineNotificationService] Total connected users in SimpUserRegistry: {}", totalUsers);
                simpUserRegistry.getUsers().forEach(user ->
                    log.info("[OfflineNotificationService] Registered user in SimpUserRegistry: {}, Sessions: {}", user.getName(), user.getSessions())
                );

                if (hasActiveSession) {
                    log.info("[OfflineNotificationService] ✅ Active SimpUser found for userId: {}. User details: {}", userId, simpUser);
                    simpUser.getSessions().forEach(session -> {
                        log.info("[OfflineNotificationService]   - Active session ID: {}, Subscriptions: {}", session.getId(), session.getSubscriptions());
                    });
                } else {
                    if (simpUser == null) {
                        log.warn("[OfflineNotificationService] ❌ No SimpUser found in SimpUserRegistry for userId: {}", userId);
                    } else {
                        log.warn("[OfflineNotificationService] ❌ SimpUser found for userId: {} but has NO active sessions.", userId);
                    }
                }
            } else {
                log.error("[OfflineNotificationService] ❌ SimpUserRegistry is null!");
                return false; // SimpUserRegistry가 null이면 세션 확인 불가
            }
            
            return hasActiveSession;
        } catch (Exception e) {
            log.error("[OfflineNotificationService] Error checking WebSocket session for user: {}", userId, e);
            return false;
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public long getPendingNotificationCount(Long userId) {
        return offlineNotificationRepository.countPendingNotificationsByUserId(userId, LocalDateTime.now());
    }
    
    @Override
    @Transactional
    public void cleanupExpiredNotifications() {
        log.info("[OfflineNotificationService] Starting cleanup of expired notifications");
        
        try {
            // 별도 트랜잭션으로 정리 작업 수행
            int deletedCount = performCleanup();
            log.info("[OfflineNotificationService] Cleanup completed. Deleted {} expired/sent notifications", deletedCount);
            
        } catch (Exception e) {
            log.error("[OfflineNotificationService] Error during cleanup: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void markAllAsSent(Long userId) {
        try {
            // 별도 트랜잭션으로 업데이트 수행
            int updatedCount = performMarkAllAsSent(userId);
            log.info("[OfflineNotificationService] Marked {} notifications as sent for userId: {}", updatedCount, userId);
            
        } catch (Exception e) {
            log.error("[OfflineNotificationService] Error marking notifications as sent for userId: {}: {}", 
                      userId, e.getMessage(), e);
        }
    }
    
    /**
     * 별도 트랜잭션으로 만료된 알림 정리 수행
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int performCleanup() {
        return offlineNotificationRepository.deleteExpiredAndSentNotifications(LocalDateTime.now());
    }
    
    /**
     * 별도 트랜잭션으로 모든 알림을 전송 완료로 표시
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int performMarkAllAsSent(Long userId) {
        return offlineNotificationRepository.markAllAsSentByUserId(userId);
    }
    
    /**
     * 알림 타입에 따른 WebSocket destination을 반환합니다.
     */
    private String getWebSocketDestination(OfflineNotification.NotificationType type) {
        String destinationPath = switch (type) {
            case NEW_MESSAGE, ROOM_DELETED -> "/queue/chat-notifications";
            case MATCH_REQUEST, MATCH_ACCEPTED, MATCH_REJECTED -> "/queue/match-notifications";
            case CHAT_ROOM_LIST_UPDATE, UNREAD_COUNT_UPDATE, MESSAGE_READ_STATUS -> "/queue/chat-updates";
            case SYSTEM_NOTICE, MEETUP_REMINDER, MEETUP_WAITLIST_PROMOTED -> "/queue/system-notifications";
            // 기본값이 없으면 컴파일 에러가 발생할 수 있으므로, 모든 NotificationType에 대한 case를 다루거나 default를 추가해야 합니다.
            // 만약 새로운 타입이 추가될 경우, 여기에 case를 추가해야 합니다.
            // default -> "/queue/notifications"; // 예를 들어 기본값
        };
        log.info("[OfflineNotificationService] getWebSocketDestination for type {}: returning path '{}'", type, destinationPath); // 로그 추가
        return destinationPath;
    }
    
    /**
     * 알림 전송 완료 요약을 사용자에게 보냅니다.
     */
    private void sendNotificationSummary(Long userId, int sentCount) {
        try {
            Map<String, Object> summaryData = Map.of(
                    "sentCount", sentCount,
                    "message", String.format("오프라인 중에 %d개의 알림이 있었습니다.", sentCount)
            );
            
            WebSocketNotification<Map<String, Object>> summary = new WebSocketNotification<>(
                    "NOTIFICATION_SUMMARY",
                    summaryData,
                    String.format("오프라인 중에 %d개의 알림이 있었습니다.", sentCount),
                    null
            );
            
            messagingTemplate.convertAndSendToUser(
                    userId.toString(),
                    "/queue/system-notifications",
                    summary
            );
            
        } catch (Exception e) {
            log.warn("[OfflineNotificationService] Failed to send notification summary: {}", e.getMessage());
        }
    }
    
    /**
     * 알림 타입에 따른 기본 우선순위를 반환합니다.
     */
    private Integer getDefaultPriority(OfflineNotification.NotificationType type) {
        return switch (type) {
            case MATCH_REQUEST, MATCH_ACCEPTED, MATCH_REJECTED -> 10; // 매칭 관련은 높은 우선순위
            case NEW_MESSAGE -> 5; // 새 메시지는 중간 우선순위
            case CHAT_ROOM_LIST_UPDATE, UNREAD_COUNT_UPDATE -> 3; // 목록 업데이트는 낮은 우선순위
            case MESSAGE_READ_STATUS -> 1; // 읽음 상태는 가장 낮은 우선순위
            case ROOM_DELETED -> 8; // 방 삭제는 중요
            case SYSTEM_NOTICE -> 7; // 시스템 공지
            case MEETUP_REMINDER, MEETUP_WAITLIST_PROMOTED -> 9;
        };
    }
    
    /**
     * 별도 트랜잭션으로 알림을 전송 완료로 표시 (읽기 전용 모드 방지)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNotificationAsSent(Long notificationId) {
        try {
            offlineNotificationRepository.markAsSent(notificationId, LocalDateTime.now());
            log.debug("[OfflineNotificationService] Successfully marked notification {} as sent in separate transaction", notificationId);
        } catch (Exception e) {
            log.error("[OfflineNotificationService] Failed to mark notification {} as sent in separate transaction: {}", 
                      notificationId, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    @Transactional
    public void markAsDelivered(Long notificationId) {
        if (notificationId != null) {
            offlineNotificationRepository.markAsSent(notificationId, LocalDateTime.now());
        }
    }
} 
