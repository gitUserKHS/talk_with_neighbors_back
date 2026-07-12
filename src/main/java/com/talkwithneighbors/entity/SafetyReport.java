package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "safety_reports", uniqueConstraints = @UniqueConstraint(
        name = "uk_safety_reports_reporter_target", columnNames = {"reporter_id", "target_type", "target_id"}), indexes = {
        @Index(name = "idx_safety_reports_status_created", columnList = "status,created_at"),
        @Index(name = "idx_safety_reports_target", columnList = "target_type,target_id")
})
@Getter
@NoArgsConstructor
public class SafetyReport {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private SafetyTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ReportReason reason;

    @Column(length = 1000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public SafetyReport(User reporter, SafetyTargetType targetType, String targetId,
                        ReportReason reason, String details) {
        this.reporter = reporter;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.details = details;
        this.status = ReportStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (status == null) status = ReportStatus.PENDING;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
