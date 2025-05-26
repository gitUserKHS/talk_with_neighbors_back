package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.OfflineNotification;

/**
 * 오프라인 사용자 알림 관리 서비스
 * 사용자가 오프라인일 때 알림을 저장하고, 온라인이 될 때 전송하는 기능을 제공합니다.
 */
public interface OfflineNotificationService {
    
    /**
     * 오프라인 사용자에게 알림을 저장합니다.
     * 
     * @param userId 사용자 ID
     * @param type 알림 타입
     * @param data 알림 데이터 (JSON 형태)
     * @param message 알림 메시지
     * @param actionUrl 관련 URL (선택사항)
     * @param priority 우선순위 (높을수록 먼저 전송)
     */
    void saveOfflineNotification(Long userId, 
                                OfflineNotification.NotificationType type, 
                                String data, 
                                String message, 
                                String actionUrl, 
                                Integer priority);
    
    /**
     * 사용자가 온라인이 될 때 쌓인 알림들을 전송합니다.
     * 
     * @param userId 온라인이 된 사용자 ID
     */
    void sendPendingNotifications(Long userId);
    
    /**
     * 사용자의 미전송 알림 개수를 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 미전송 알림 개수
     */
    long getPendingNotificationCount(Long userId);
    
    /**
     * 만료된 알림들을 정리합니다.
     * 스케줄러에서 주기적으로 호출됩니다.
     */
    void cleanupExpiredNotifications();
    
    /**
     * 특정 사용자의 모든 미전송 알림을 전송 완료로 표시합니다.
     * 
     * @param userId 사용자 ID
     */
    void markAllAsSent(Long userId);
} 