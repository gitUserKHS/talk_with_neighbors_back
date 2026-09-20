package com.talkwithneighbors.config;

import com.talkwithneighbors.service.OfflineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
     * {@code @Scheduled} 작업 전용 스케줄러.
     *
     * 이 빈이 없으면 Spring은 컨텍스트에 있는 TaskScheduler를 집어 쓰는데,
     * 예전에는 그것이 WebSocket 하트비트 풀이라 아웃박스 폴링과 정리 작업이 하트비트를 지연시켰다.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sched-");
        return scheduler;
    }
    
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
