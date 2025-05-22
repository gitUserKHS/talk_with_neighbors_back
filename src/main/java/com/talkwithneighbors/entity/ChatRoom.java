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
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 채팅방을 관리하는 엔티티 클래스
 * 사용자 간의 대화 공간을 생성하고 관리합니다.
 */
@Entity
@Table(name = "chat_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatRoom {
    /**
     * 채팅방의 고유 식별자
     * UUID 형식으로 자동 생성됩니다.
     */
    @Id
    private String id;

    /**
     * 채팅방의 이름
     */
    @Column(nullable = false)
    private String name;

    /**
     * 채팅방의 타입
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatRoomType type;

    /**
     * 채팅방을 생성한 사용자
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    @JsonIgnore
    private User creator;

    /**
     * 채팅방에 참여하는 사용자 목록
     * 다대다(N:N) 관계로 설정되어 있으며,
     * chat_room_participants 테이블을 통해 관리됩니다.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "chat_room_participants",
        joinColumns = @JoinColumn(name = "chat_room_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @JsonIgnore
    private List<User> participants = new ArrayList<>();

    /**
     * 채팅방의 마지막 메시지 내용
     * TEXT 타입으로 저장됩니다.
     */
    @Column(name = "last_message", columnDefinition = "TEXT")
    private String lastMessage;

    /**
     * 마지막 메시지가 전송된 시간
     */
    @Column(name = "last_message_time")
    private LocalDateTime lastMessageTime;

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
        // 마지막 메시지 시간이 없으면 현재 시간으로 설정
        if (lastMessageTime == null) {
            lastMessageTime = LocalDateTime.now();
        }
    }
} 