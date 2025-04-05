package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 이웃 매칭 정보를 관리하는 엔티티 클래스
 * 두 사용자 간의 매칭 상태와 시간 정보를 저장합니다.
 */
@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
public class Match {
    /**
     * 매칭의 고유 식별자
     * UUID 형식으로 자동 생성됩니다.
     */
    @Id
    private String id;

    /**
     * 매칭의 첫 번째 사용자
     * 지연 로딩(LAZY) 방식으로 User 엔티티와 연결됩니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user1_id")
    private User user1;

    /**
     * 매칭의 두 번째 사용자
     * 지연 로딩(LAZY) 방식으로 User 엔티티와 연결됩니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user2_id")
    private User user2;

    /**
     * 매칭의 현재 상태
     * PENDING(대기중), ACCEPTED(수락됨), REJECTED(거절됨) 등의 상태를 가질 수 있습니다.
     */
    @Enumerated(EnumType.STRING)
    private MatchStatus status;

    /**
     * 매칭이 생성된 시간
     */
    private LocalDateTime createdAt;

    /**
     * 매칭이 만료되는 시간
     * 생성 시간으로부터 24시간 후로 자동 설정됩니다.
     */
    private LocalDateTime expiresAt;

    /**
     * 매칭에 대한 응답이 있었던 시간
     * 수락 또는 거절 시점을 기록합니다.
     */
    private LocalDateTime respondedAt;

    /**
     * 엔티티가 데이터베이스에 저장되기 전에 실행되는 메서드
     * 초기값들을 자동으로 설정합니다.
     */
    @PrePersist
    protected void onCreate() {
        // UUID가 없으면 새로 생성
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        // 생성 시간이 없으면 현재 시간으로 설정
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // 만료 시간이 없으면 생성 시간으로부터 24시간 후로 설정
        if (expiresAt == null) {
            expiresAt = createdAt.plusHours(24);
        }
        // 상태가 없으면 PENDING으로 설정
        if (status == null) {
            status = MatchStatus.PENDING;
        }
    }
} 