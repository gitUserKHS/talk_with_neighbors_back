package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.DomainEvent;
import com.talkwithneighbors.entity.OutboxEvent;
import com.talkwithneighbors.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DomainEventPublisher {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        try {
            OutboxEvent outboxEvent = new OutboxEvent(
                    event.eventId(),
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    objectMapper.writeValueAsString(event),
                    event.occurredAt()
            );
            outboxEventRepository.save(outboxEvent);
            applicationEventPublisher.publishEvent(new OutboxEventStored(event.eventId()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize domain event " + event.eventType(), exception);
        }
    }
}
