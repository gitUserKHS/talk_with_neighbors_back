package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hidden_contents", uniqueConstraints = @UniqueConstraint(
        name = "uk_hidden_contents_user_target", columnNames = {"user_id", "target_type", "target_id"}))
@Getter
@NoArgsConstructor
public class HiddenContent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private SafetyTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public HiddenContent(User user, SafetyTargetType targetType, String targetId) {
        this.user = user;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
