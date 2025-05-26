package com.talkwithneighbors.service;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.security.Principal;
import com.talkwithneighbors.repository.OfflineNotificationRepository;
import java.time.LocalDateTime;
import com.talkwithneighbors.entity.OfflineNotification;
import java.util.List;

/**
 * 사용자 온라인 상태 변경을 감지하는 리스너
 * 사용자가 온라인이 될 때 쌓인 오프라인 알림들을 전송합니다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class UserOnlineStatusListener {
    
    private final OfflineNotificationService offlineNotificationService;
    private final RedisSessionService redisSessionService;
    private final OfflineNotificationRepository offlineNotificationRepository;
    
    @EventListener
    @Transactional
    public void handleSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal userPrincipal = headerAccessor.getUser();

        if (userPrincipal != null && userPrincipal.getName() != null) {
            String userIdString = userPrincipal.getName();
            log.info("=== [UserOnlineStatusListener] 🚀 SessionConnectedEvent for userId: {}, simpSessionId: {} ===", 
                     userIdString, headerAccessor.getSessionId());
            try {
                Long userId = Long.parseLong(userIdString);
                
                // 먼저 사용자 상태를 확실히 온라인으로 설정 (setUserOnline 내부에서 onUserOnline이 호출될 수 있음)
                // 이 호출은 UserOnlineEvent를 발생시킬 수 있고, onUserOnline 메서드가 호출될 수 있습니다.
                // onUserOnline에서도 sendPendingNotifications를 호출하므로, 여기서는 중복 호출을 피하거나,
                // sendPendingNotifications가 멱등성을 가지도록 합니다 (현재는 isSent 플래그로 처리 중).
                redisSessionService.setUserOnline(userIdString);
                
                // 클라이언트의 STOMP 구독 완료를 기다리지 않고, 
                // 클라이언트가 "/app/client/ready" 메시지를 보내면 그때 알림을 전송하도록 변경합니다.
                // 따라서 아래 로직은 제거합니다.
                // log.info("[UserOnlineStatusListener] Waiting for STOMP subscriptions to complete for user {}...", userId);
                // try {
                //     Thread.sleep(2000); // 지연 시간을 500ms에서 2000ms로 늘립니다.
                // } catch (InterruptedException ie) {
                //     Thread.currentThread().interrupt();
                //     log.warn("[UserOnlineStatusListener] Delay interrupted while waiting for STOMP subscription for userId: {}", userId);
                // }

                // log.info("[UserOnlineStatusListener] Attempting to send pending notifications for user {} after session connect and delay.", userId);
                // offlineNotificationService.sendPendingNotifications(userId);

            } catch (NumberFormatException e) {
                log.error("[UserOnlineStatusListener] Invalid userId in Principal: {}", userIdString, e);
            } catch (Exception e) {
                log.error("[UserOnlineStatusListener] Error in handleSessionConnected for userId {}: {}", userIdString, e.getMessage(), e);
            }
        } else {
            log.warn("[UserOnlineStatusListener] SessionConnectedEvent with no user principal or userId.");
        }
    }
    
    /**
     * 사용자가 온라인 상태가 될 때 호출됩니다.
     * 쌓인 오프라인 알림들을 전송합니다.
     */
    @Transactional
    public void onUserOnline(Long userId) {
        log.info("=== [UserOnlineStatusListener] 🎯 onUserOnline 호출됨! userId: {} ===", userId);
        
        try {
            // offlineNotificationService.sendPendingNotifications 호출 전에 현재 보류 중인 알림 수 확인
            List<OfflineNotification> pendingNotifications = offlineNotificationRepository.findPendingNotificationsByUserId(userId, LocalDateTime.now());
            log.info("[UserOnlineStatusListener] (onUserOnline) UserID: {} - DB에서 조회된 보류 중 알림 (is_sent=false, not expired): {} 건", userId, pendingNotifications.size());
            for (OfflineNotification notif : pendingNotifications) {
                log.info("[UserOnlineStatusListener] (onUserOnline) Pending Notification ID: {}, Type: {}, isSent: {}, ExpiresAt: {}", notif.getId(), notif.getType(), notif.getIsSent(), notif.getExpiresAt());
            }

            log.info("[UserOnlineStatusListener] userId: {}의 오프라인 알림 전송 처리 시작 (onUserOnline 통해)", userId);
            offlineNotificationService.sendPendingNotifications(userId);
            log.info("[UserOnlineStatusListener] ✅ userId: {}의 오프라인 알림 전송 처리 완료 (onUserOnline 통해)", userId);
            
        } catch (Exception e) {
            log.error("[UserOnlineStatusListener] ❌ onUserOnline 처리 중 오류 for userId {}: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * 사용자가 오프라인 상태가 될 때 호출됩니다.
     * 현재는 특별한 처리가 없지만, 향후 확장 가능합니다.
     */
    @Transactional
    public void onUserOffline(Long userId) {
        log.info("=== [UserOnlineStatusListener] 🔌 onUserOffline 호출됨! userId: {} ===", userId);
        // 필요시 오프라인 관련 추가 로직
    }
} 