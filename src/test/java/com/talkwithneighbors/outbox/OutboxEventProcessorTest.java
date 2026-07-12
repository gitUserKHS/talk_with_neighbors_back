package com.talkwithneighbors.outbox;

import com.talkwithneighbors.domain.event.MatchCompletedEvent;
import com.talkwithneighbors.entity.OutboxEvent;
import com.talkwithneighbors.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private DomainEventSerializer domainEventSerializer;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void publishesStoredEventAndMarksItDelivered() throws Exception {
        MatchCompletedEvent domainEvent = MatchCompletedEvent.create("match-1", "room-1", 1L, 2L);
        OutboxEvent storedEvent = new OutboxEvent(
                domainEvent.eventId(),
                domainEvent.eventType(),
                domainEvent.aggregateType(),
                domainEvent.aggregateId(),
                "{}",
                LocalDateTime.now()
        );
        when(outboxEventRepository.findByIdForUpdate(domainEvent.eventId()))
                .thenReturn(Optional.of(storedEvent));
        when(domainEventSerializer.deserialize(domainEvent.eventType(), "{}"))
                .thenReturn(domainEvent);
        OutboxEventProcessor processor = new OutboxEventProcessor(
                outboxEventRepository,
                domainEventSerializer,
                applicationEventPublisher
        );

        processor.process(domainEvent.eventId());

        verify(applicationEventPublisher).publishEvent(domainEvent);
        assertNotNull(storedEvent.getPublishedAt());
    }
}
