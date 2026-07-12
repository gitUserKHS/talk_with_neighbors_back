package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.OfflineNotificationService;
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
}
