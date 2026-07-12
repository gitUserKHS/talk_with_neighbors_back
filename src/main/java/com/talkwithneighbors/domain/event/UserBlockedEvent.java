package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserBlockedEvent(String eventId, LocalDateTime occurredAt, Long blockerId, Long blockedId)
        implements DomainEvent {
    public static final String TYPE = "USER_BLOCKED";
    public static UserBlockedEvent create(Long blockerId, Long blockedId) {
        return new UserBlockedEvent(UUID.randomUUID().toString(), LocalDateTime.now(), blockerId, blockedId);
    }
    public String eventType() { return TYPE; }
    public String aggregateType() { return "UserBlock"; }
    public String aggregateId() { return blockerId + ":" + blockedId; }
}
