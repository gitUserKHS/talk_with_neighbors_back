package com.talkwithneighbors.dto.safety;

import com.talkwithneighbors.entity.*;
import java.time.LocalDateTime;

public record SafetyReportDto(String id, SafetyTargetType targetType, String targetId,
                              ReportReason reason, String details, ReportStatus status,
                              LocalDateTime createdAt) {
    public static SafetyReportDto from(SafetyReport report) {
        return new SafetyReportDto(report.getId(), report.getTargetType(), report.getTargetId(),
                report.getReason(), report.getDetails(), report.getStatus(), report.getCreatedAt());
    }
}
