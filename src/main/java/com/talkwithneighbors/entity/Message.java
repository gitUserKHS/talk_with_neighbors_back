package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 채팅 메시지를 관리하는 엔티티 클래스
 * 사용자 간의 대화 내용을 저장합니다.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    /**
     * 메시지의 고유 식별자
     * UUID 형식으로 자동 생성됩니다.
     */
    @Id
    private String id;

    /**
     * 메시지가 속한 채팅방
     * 지연 로딩(LAZY) 방식으로 ChatRoom 엔티티와 연결됩니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    /**
     * 메시지를 보낸 사용자
     * 지연 로딩(LAZY) 방식으로 User 엔티티와 연결됩니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /**
     * 메시지 내용
     * TEXT 타입으로 저장됩니다.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 메시지 생성 시간
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    /**
     * 메시지 타입
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageType type;
    
    /**
     * 메시지 수정 시간
     */
    private LocalDateTime updatedAt;
    
    /**
     * 메시지 삭제 상태
     */
    private boolean isDeleted = false;
    
    /**
     * 메시지 읽음 상태
     * 메시지가 삭제되면 관련 읽음 상태도 자동으로 삭제됩니다.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "message_read_by",
        joinColumns = @JoinColumn(name = "message_id", referencedColumnName = "id"),
        foreignKey = @ForeignKey(name = "fk_message_read_by_message_id")
    )
    @Column(name = "user_id")
    private Set<Long> readByUsers = new HashSet<>();

    /**
     * 엔티티가 데이터베이스에 저장되기 전에 실행되는 메서드
     * 초기값들을 자동으로 설정합니다.
     */
    @PrePersist
    protected void onCreate() {
        // UUID가 없으면 새로 생성
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        // 생성 시간이 없으면 현재 시간으로 설정
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }
    
    /**
     * 엔티티가 데이터베이스에 업데이트되기 전에 실행되는 메서드
     * 수정 시간을 현재 시간으로 설정합니다.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum MessageType {
        ENTER,      // 입장
        LEAVE,      // 퇴장
        TEXT,       // 일반 메시지
        IMAGE,      // 이미지
        FILE,       // 파일
        SYSTEM      // 시스템 메시지
    }
}