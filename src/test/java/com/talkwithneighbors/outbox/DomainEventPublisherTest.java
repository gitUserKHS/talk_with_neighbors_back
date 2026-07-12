package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.MatchCompletedEvent;
import com.talkwithneighbors.entity.OutboxEvent;
import com.talkwithneighbors.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DomainEventPublisherTest {
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void storesEventBeforePublishingCommitSignal() {
        DomainEventPublisher publisher = new DomainEventPublisher(
                outboxEventRepository,
                new ObjectMapper().findAndRegisterModules(),
                applicationEventPublisher
        );
        MatchCompletedEvent event = MatchCompletedEvent.create("match-1", "room-1", 1L, 2L);

        publisher.publish(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertEquals(event.eventId(), captor.getValue().getId());
        assertEquals(MatchCompletedEvent.TYPE, captor.getValue().getEventType());
        verify(applicationEventPublisher).publishEvent(new OutboxEventStored(event.eventId()));
    }
}
