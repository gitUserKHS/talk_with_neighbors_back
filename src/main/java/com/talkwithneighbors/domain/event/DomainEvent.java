package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;

public interface DomainEvent {
    String eventId();
    String eventType();
    String aggregateType();
    String aggregateId();
    LocalDateTime occurredAt();
}
