package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 오프라인 사용자의 미전송 알림을 저장하는 엔티티
 * 사용자가 온라인으로 돌아왔을 때 쌓인 알림들을 전송하기 위해 사용됩니다.
 */
@Entity
@Table(name = "offline_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OfflineNotification {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 알림을 받을 사용자 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 알림 타입 (NEW_MESSAGE, MATCH_REQUEST, MATCH_ACCEPTED 등)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType type;
    
    /**
     * 알림 내용 (JSON 형태)
     */
    @Column(name = "notification_data", columnDefinition = "TEXT", nullable = false)
    private String data;
    
    /**
     * 알림 메시지
     */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;
    
    /**
     * 관련 URL (선택사항)
     */
    @Column(name = "action_url")
    private String actionUrl;
    
    /**
     * 알림 생성 시간
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * 알림 만료 시간 (이 시간 이후에는 전송하지 않음)
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    /**
     * 우선순위 (높을수록 먼저 전송)
     */
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;
    
    /**
     * 전송 완료 여부
     */
    @Column(name = "is_sent", nullable = false)
    private Boolean isSent = false;
    
    /**
     * 전송 시도 횟수
     */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (expiresAt == null) {
            // 기본적으로 7일 후 만료
            expiresAt = createdAt.plusDays(7);
        }
    }
    
    /**
     * 알림 타입 열거형
     */
    public enum NotificationType {
        NEW_MESSAGE,           // 새 메시지
        MATCH_REQUEST,         // 매칭 요청
        MATCH_ACCEPTED,        // 매칭 수락
        MATCH_REJECTED,        // 매칭 거절
        CHAT_ROOM_LIST_UPDATE, // 채팅방 목록 업데이트
        UNREAD_COUNT_UPDATE,   // 읽지 않은 메시지 수 업데이트
        MESSAGE_READ_STATUS,   // 메시지 읽음 상태 업데이트
        ROOM_DELETED,          // 채팅방 삭제
        SYSTEM_NOTICE          // 시스템 공지
    }
} 