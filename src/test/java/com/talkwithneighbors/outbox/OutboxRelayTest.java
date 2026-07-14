package com.talkwithneighbors.outbox;

import com.talkwithneighbors.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    @Test
    void immediateDeliveryFailureDoesNotEscapeAfterTheOriginatingCommit() {
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, outboxEventProcessor);
        doThrow(new IllegalStateException("temporary failure"))
                .when(outboxEventProcessor).process("event-1");

        assertDoesNotThrow(() -> relay.deliverAfterCommit(new OutboxEventStored("event-1")));

        verify(outboxEventProcessor).process("event-1");
    }
}
