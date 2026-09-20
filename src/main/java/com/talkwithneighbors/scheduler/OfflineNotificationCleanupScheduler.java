package com.talkwithneighbors.scheduler;

import com.talkwithneighbors.service.OfflineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 오프라인 알림을 정리하는 스케줄러 클래스
 *
 * 이 작업은 알림 서비스를 필요로 하고, 알림 서비스는 WebSocket 브로커를 필요로 한다.
 * 브로커는 기동하면서 TaskScheduler 빈을 찾으므로, 이 의존성이 스케줄러 풀을 정의하는
 * {@link com.talkwithneighbors.config.SchedulingConfig}에 있으면 순환 참조가 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfflineNotificationCleanupScheduler {

    /**
     * 오프라인 알림을 보관하고 정리하는 서비스
     */
    private final OfflineNotificationService offlineNotificationService;

    /**
     * 만료된 오프라인 알림들을 정리합니다.
     * 매일 새벽 2시에 실행됩니다.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredOfflineNotifications() {
        log.info("[OfflineNotificationCleanupScheduler] Starting cleanup of expired offline notifications");
        try {
            offlineNotificationService.cleanupExpiredNotifications();
            log.info("[OfflineNotificationCleanupScheduler] Completed cleanup of expired offline notifications");
        } catch (Exception e) {
            log.error("[OfflineNotificationCleanupScheduler] Error during cleanup of expired offline notifications: {}", e.getMessage(), e);
        }
    }
}
