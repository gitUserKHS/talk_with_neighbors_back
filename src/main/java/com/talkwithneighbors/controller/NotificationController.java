package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.NotificationInboxService;
import com.talkwithneighbors.dto.notification.NotificationInboxDto;
import com.talkwithneighbors.security.UserSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
@Slf4j
public class NotificationController {
    private final OfflineNotificationService offlineNotificationService;
    private final NotificationInboxService notificationInboxService;

    @MessageMapping("/client/ready")
    public void handleClientReady(Principal principal) {
        if (principal == null || principal.getName() == null) {
            log.warn("Client ready signal received without an authenticated principal.");
            return;
        }

        try {
            Long userId = Long.parseLong(principal.getName());
            log.info("Client subscriptions are ready for user {}. Delivering pending notifications.", userId);
            offlineNotificationService.sendPendingNotifications(userId);
        } catch (NumberFormatException exception) {
            log.error("Invalid user id in client ready signal: {}", principal.getName(), exception);
        }
    }

    @GetMapping("/api/notifications")
    public ResponseEntity<Page<NotificationInboxDto>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            UserSession session) {
        return ResponseEntity.ok(notificationInboxService.getNotifications(session.getUserId(),
                PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)))));
    }

    @GetMapping("/api/notifications/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(UserSession session) {
        return ResponseEntity.ok(Map.of("count", notificationInboxService.unreadCount(session.getUserId())));
    }

    @PatchMapping("/api/notifications/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, UserSession session) {
        notificationInboxService.markRead(session.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/notifications/read-all")
    public ResponseEntity<Void> markAllRead(UserSession session) {
        notificationInboxService.markAllRead(session.getUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/notifications/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, UserSession session) {
        notificationInboxService.delete(session.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
