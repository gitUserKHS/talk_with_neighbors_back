package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.ChatRoomDeletedEvent;
import com.talkwithneighbors.domain.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DomainEventSerializerTest {
    @Test
    void deserializesDurableChatRoomDeletedEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ChatRoomDeletedEvent original = ChatRoomDeletedEvent.create("room-1", List.of(1L, 2L));
        DomainEventSerializer serializer = new DomainEventSerializer(objectMapper);

        DomainEvent restored = serializer.deserialize(
                original.eventType(), objectMapper.writeValueAsString(original));

        ChatRoomDeletedEvent event = assertInstanceOf(ChatRoomDeletedEvent.class, restored);
        assertEquals(original, event);
    }
}
