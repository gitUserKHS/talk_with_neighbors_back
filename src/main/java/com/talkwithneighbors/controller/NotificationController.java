package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.UserOnlineStatusListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {
    
    private final OfflineNotificationService offlineNotificationService;
    private final UserOnlineStatusListener userOnlineStatusListener;
    
    /**
     * 수동으로 오프라인 알림을 전송하는 테스트 API
     */
    @PostMapping("/test/send-pending/{userId}")
    @ResponseBody
    public ResponseEntity<String> testSendPendingNotifications(@PathVariable Long userId) {
        log.info("=== Manual test: sending pending notifications for userId: {} ===", userId);
        
        try {
            // UserOnlineStatusListener를 통해 오프라인 알림 전송
            userOnlineStatusListener.onUserOnline(userId);
            
            return ResponseEntity.ok("오프라인 알림 전송 테스트 완료");
        } catch (Exception e) {
            log.error("Error in manual notification test for userId: {}", userId, e);
            return ResponseEntity.badRequest().body("오프라인 알림 전송 테스트 실패: " + e.getMessage());
        }
    }
    
    /**
     * 직접 OfflineNotificationService를 호출하는 테스트 API
     */
    @PostMapping("/test/send-direct/{userId}")
    @ResponseBody
    public ResponseEntity<String> testDirectSendNotifications(@PathVariable Long userId) {
        log.info("=== Direct test: sending pending notifications for userId: {} ===", userId);
        
        try {
            // OfflineNotificationService를 직접 호출
            offlineNotificationService.sendPendingNotifications(userId);
            
            return ResponseEntity.ok("직접 오프라인 알림 전송 테스트 완료");
        } catch (Exception e) {
            log.error("Error in direct notification test for userId: {}", userId, e);
            return ResponseEntity.badRequest().body("직접 오프라인 알림 전송 테스트 실패: " + e.getMessage());
        }
    }

    /**
     * 클라이언트가 STOMP 구독 완료 후 준비되었음을 알리는 메시지를 처리합니다.
     * @param principal 현재 인증된 사용자 정보
     */
    @MessageMapping("/client/ready")
    public void handleClientReady(Principal principal) {
        if (principal != null && principal.getName() != null) {
            String userIdString = principal.getName();
            log.info("=== [NotificationController] Client ready signal received for userId: {} ===", userIdString);
            try {
                Long userId = Long.parseLong(userIdString);
                offlineNotificationService.sendPendingNotifications(userId);
            } catch (NumberFormatException e) {
                log.error("[NotificationController] Invalid userId from Principal in client ready signal: {}", userIdString, e);
            } catch (Exception e) {
                log.error("[NotificationController] Error processing client ready signal for userId {}: {}", userIdString, e.getMessage(), e);
            }
        } else {
            log.warn("[NotificationController] Client ready signal received with no principal or userId.");
        }
    }
} 