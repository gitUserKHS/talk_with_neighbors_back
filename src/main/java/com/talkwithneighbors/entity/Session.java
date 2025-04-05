package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 사용자 세션을 관리하는 엔티티 클래스
 * Redis와 연동하여 사용자의 로그인 상태를 관리합니다.
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    /**
     * 세션의 고유 식별자
     * Redis의 세션 ID와 동일한 값을 가집니다.
     */
    @Id
    private String sessionId;

    /**
     * 세션에 연결된 사용자
     * 지연 로딩(LAZY) 방식으로 User 엔티티와 연결됩니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 마지막 접근 시간
     * 사용자가 마지막으로 활동한 시간을 기록합니다.
     */
    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;

    /**
     * 세션 만료 시간
     * 이 시간이 지나면 세션이 만료됩니다.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 새로운 세션을 생성하는 정적 팩토리 메서드
     * 
     * @param sessionId Redis 세션 ID
     * @param user 세션에 연결할 사용자
     * @param expirationHours 세션 만료 시간 (시간 단위)
     * @return 생성된 Session 객체
     */
    public static Session create(String sessionId, User user, int expirationHours) {
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setUser(user);
        session.setLastAccessedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(expirationHours));
        return session;
    }

    /**
     * 마지막 접근 시간을 현재 시간으로 업데이트합니다.
     */
    public void updateLastAccessed() {
        this.lastAccessedAt = LocalDateTime.now();
    }

    /**
     * 세션이 만료되었는지 확인합니다.
     * 
     * @return 만료 여부 (true: 만료됨, false: 유효함)
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
} 