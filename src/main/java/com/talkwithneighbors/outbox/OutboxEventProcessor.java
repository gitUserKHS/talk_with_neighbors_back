package com.talkwithneighbors.outbox;

import com.talkwithneighbors.domain.event.DomainEvent;
import com.talkwithneighbors.entity.OutboxEvent;
import com.talkwithneighbors.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventProcessor {
    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventSerializer domainEventSerializer;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(String eventId) {
        OutboxEvent outboxEvent = outboxEventRepository.findByIdForUpdate(eventId).orElse(null);
        if (outboxEvent == null || outboxEvent.getPublishedAt() != null) {
            return;
        }

        try {
            DomainEvent event = domainEventSerializer.deserialize(
                    outboxEvent.getEventType(),
                    outboxEvent.getPayload()
            );
            applicationEventPublisher.publishEvent(event);
            outboxEvent.markPublished(LocalDateTime.now());
        } catch (Exception exception) {
            outboxEvent.registerFailure(exception.getMessage());
            log.warn("Outbox event delivery failed. eventId={}, type={}, retryCount={}",
                    outboxEvent.getId(), outboxEvent.getEventType(), outboxEvent.getRetryCount(), exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long cleanupPublishedEvents(int retentionDays) {
        return outboxEventRepository.deleteByPublishedAtBefore(
                LocalDateTime.now().minusDays(retentionDays)
        );
    }
}
