package com.talkwithneighbors.config;

import com.talkwithneighbors.service.OfflineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 스케줄링 관련 설정
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SchedulingConfig {
    
    private final OfflineNotificationService offlineNotificationService;
    
    /**
     * 만료된 오프라인 알림들을 정리합니다.
     * 매일 새벽 2시에 실행됩니다.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredOfflineNotifications() {
        log.info("[SchedulingConfig] Starting cleanup of expired offline notifications");
        try {
            offlineNotificationService.cleanupExpiredNotifications();
            log.info("[SchedulingConfig] Completed cleanup of expired offline notifications");
        } catch (Exception e) {
            log.error("[SchedulingConfig] Error during cleanup of expired offline notifications: {}", e.getMessage(), e);
        }
    }
} 