package com.talkwithneighbors.dto.admin;

import com.talkwithneighbors.entity.ReportReason;
import com.talkwithneighbors.entity.ReportStatus;
import com.talkwithneighbors.entity.SafetyReport;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.entity.User;

import java.time.LocalDateTime;

/**
 * 검토 큐의 한 줄. 목록에서는 신고 대상 본문까지 싣지 않는다.
 */
public record AdminReportDto(
        String id,
        SafetyTargetType targetType,
        String targetId,
        ReportReason reason,
        String details,
        ReportStatus status,
        Long reporterId,
        String reporterUsername,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt,
        String reviewedByUsername,
        String reviewNote,
        LocalDateTime reviewedAt,
        long reportsOnSameTarget
) {
    public static AdminReportDto from(SafetyReport report, long reportsOnSameTarget) {
        User reporter = report.getReporter();
        User reviewer = report.getReviewedBy();
        return new AdminReportDto(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDetails(),
                report.getStatus(),
                reporter != null ? reporter.getId() : null,
                reporter != null ? reporter.getUsername() : null,
                report.getCreatedAt(),
                report.getResolvedAt(),
                reviewer != null ? reviewer.getUsername() : null,
                report.getReviewNote(),
                report.getReviewedAt(),
                reportsOnSameTarget
        );
    }
}
