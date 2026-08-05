package com.talkwithneighbors.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 브라우저 하나가 등록한 푸시 구독.
 *
 * 한 사용자가 여러 기기와 브라우저를 쓸 수 있으므로 사용자당 여러 건을 허용하고,
 * endpoint는 브라우저가 발급한 고유 주소이므로 유일 제약을 건다.
 */
@Entity
@Table(
        name = "web_push_subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_web_push_endpoint", columnNames = "endpoint"),
        indexes = @Index(name = "idx_web_push_user", columnList = "user_id")
)
@Getter
@NoArgsConstructor
public class WebPushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 푸시 서비스 주소. 브라우저·벤더마다 길이가 달라 넉넉히 잡는다. */
    @Column(nullable = false, length = 512)
    private String endpoint;

    @Column(name = "p256dh_key", nullable = false, length = 255)
    private String p256dh;

    @Column(name = "auth_key", nullable = false, length = 255)
    private String auth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WebPushSubscription(Long userId, String endpoint, String p256dh, String auth) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 같은 endpoint를 다시 구독하면 키만 갱신한다. 브라우저가 키를 재발급하는 경우가 있다. */
    public void refreshKeys(String p256dh, String auth) {
        this.p256dh = p256dh;
        this.auth = auth;
    }
}
