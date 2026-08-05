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

    /**
     * 마지막으로 상태를 바꾼 운영자. 처리 이력을 남겨 누가 판단했는지 추적한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

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

    /**
     * 운영자의 검토 결과를 반영한다.
     * RESOLVED와 DISMISSED만 종결로 보고 resolvedAt을 남기며, 다시 검토 중으로 되돌리면 지운다.
     */
    public void review(User reviewer, ReportStatus nextStatus, String note, LocalDateTime now) {
        this.status = nextStatus;
        this.reviewedBy = reviewer;
        this.reviewNote = note == null || note.isBlank() ? null : note.trim();
        this.reviewedAt = now;
        this.resolvedAt = isTerminal(nextStatus) ? now : null;
    }

    public static boolean isTerminal(ReportStatus status) {
        return status == ReportStatus.RESOLVED || status == ReportStatus.DISMISSED;
    }
}
