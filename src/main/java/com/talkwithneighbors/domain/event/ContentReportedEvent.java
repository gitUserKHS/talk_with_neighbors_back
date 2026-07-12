package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.entity.ReportReason;
import com.talkwithneighbors.entity.SafetyTargetType;
import java.time.LocalDateTime;
import java.util.UUID;

public record ContentReportedEvent(String eventId, LocalDateTime occurredAt, String reportId,
                                   Long reporterId, SafetyTargetType targetType, String targetId,
                                   ReportReason reason) implements DomainEvent {
    public static final String TYPE = "CONTENT_REPORTED";
    public static ContentReportedEvent create(String reportId, Long reporterId, SafetyTargetType targetType,
                                              String targetId, ReportReason reason) {
        return new ContentReportedEvent(UUID.randomUUID().toString(), LocalDateTime.now(), reportId,
                reporterId, targetType, targetId, reason);
    }
    public String eventType() { return TYPE; }
    public String aggregateType() { return "SafetyReport"; }
    public String aggregateId() { return reportId; }
}
