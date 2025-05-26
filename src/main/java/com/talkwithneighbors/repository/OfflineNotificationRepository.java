package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.OfflineNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 오프라인 알림을 관리하는 리포지토리 인터페이스
 */
@Repository
public interface OfflineNotificationRepository extends JpaRepository<OfflineNotification, Long> {
    
    /**
     * 특정 사용자의 미전송 알림 목록을 우선순위 및 생성시간 순으로 조회
     * @param userId 사용자 ID
     * @return 미전송 알림 목록
     */
    @Query("SELECT on FROM OfflineNotification on WHERE on.userId = :userId AND on.isSent = false AND on.expiresAt > :now ORDER BY on.priority DESC, on.createdAt ASC")
    List<OfflineNotification> findPendingNotificationsByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    
    /**
     * 특정 사용자의 미전송 알림 개수 조회
     * @param userId 사용자 ID
     * @return 미전송 알림 개수
     */
    @Query("SELECT COUNT(on) FROM OfflineNotification on WHERE on.userId = :userId AND on.isSent = false AND on.expiresAt > :now")
    long countPendingNotificationsByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    
    /**
     * 만료된 알림들을 삭제
     * @param now 현재 시간
     * @return 삭제된 레코드 수
     */
    @Modifying
    @Query("DELETE FROM OfflineNotification on WHERE on.expiresAt <= :now OR on.isSent = true")
    int deleteExpiredAndSentNotifications(@Param("now") LocalDateTime now);
    
    /**
     * 특정 사용자의 모든 미전송 알림을 전송 완료로 표시
     * @param userId 사용자 ID
     * @return 업데이트된 레코드 수
     */
    @Modifying
    @Query("UPDATE OfflineNotification on SET on.isSent = true WHERE on.userId = :userId AND on.isSent = false")
    int markAllAsSentByUserId(@Param("userId") Long userId);
    
    /**
     * 특정 알림을 전송 완료로 표시
     * @param notificationId 알림 ID
     * @return 업데이트된 레코드 수
     */
    @Modifying
    @Query("UPDATE OfflineNotification on SET on.isSent = true WHERE on.id = :id")
    int markAsSent(@Param("id") Long notificationId);
    
    /**
     * 재시도 횟수 증가
     * @param notificationId 알림 ID
     * @return 업데이트된 레코드 수
     */
    @Modifying
    @Query("UPDATE OfflineNotification on SET on.retryCount = on.retryCount + 1 WHERE on.id = :id")
    int incrementRetryCount(@Param("id") Long notificationId);
    
    /**
     * 특정 사용자의 특정 타입 알림 조회 (중복 방지용)
     * @param userId 사용자 ID
     * @param type 알림 타입
     * @param data 알림 데이터 (JSON)
     * @return 해당 조건의 알림 목록
     */
    @Query("SELECT on FROM OfflineNotification on WHERE on.userId = :userId AND on.type = :type AND on.data = :data AND on.isSent = false")
    List<OfflineNotification> findDuplicateNotifications(@Param("userId") Long userId, 
                                                          @Param("type") OfflineNotification.NotificationType type, 
                                                          @Param("data") String data);
} 