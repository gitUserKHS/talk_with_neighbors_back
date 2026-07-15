package com.talkwithneighbors.outbox;

import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.entity.OutboxEvent;
import com.talkwithneighbors.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
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
    void publishesStoredMediaDeletionEventAndMarksItDelivered() throws Exception {
        MediaFilesDeletedEvent domainEvent = MediaFilesDeletedEvent.create(
                "FeedPost", "post-1", List.of("/uploads/feed/image.webp"));
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

    @Test
    void mediaDeletionListenerFailureKeepsEventPendingForRetry() throws Exception {
        MediaFilesDeletedEvent domainEvent = MediaFilesDeletedEvent.create(
                "FeedPost", "post-1", List.of("/uploads/feed/image.webp"));
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
        doThrow(new RuntimeException("temporary storage failure"))
                .when(applicationEventPublisher).publishEvent(domainEvent);
        OutboxEventProcessor processor = new OutboxEventProcessor(
                outboxEventRepository,
                domainEventSerializer,
                applicationEventPublisher
        );

        processor.process(domainEvent.eventId());

        assertNull(storedEvent.getPublishedAt());
        assertEquals(1, storedEvent.getRetryCount());
    }
}
