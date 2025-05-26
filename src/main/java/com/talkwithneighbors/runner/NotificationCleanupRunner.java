package com.talkwithneighbors.runner;

import com.talkwithneighbors.service.OfflineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupRunner implements ApplicationRunner {

    private final OfflineNotificationService offlineNotificationService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("[NotificationCleanupRunner] Application started, cleaning up expired and sent notifications...");
        try {
            offlineNotificationService.cleanupExpiredNotifications();
            log.info("[NotificationCleanupRunner] Successfully cleaned up notifications at startup.");
        } catch (Exception e) {
            log.error("[NotificationCleanupRunner] Error during startup notification cleanup: {}", e.getMessage(), e);
        }
    }
} 