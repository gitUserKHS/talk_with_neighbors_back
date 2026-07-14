package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable domain event emitted in the same transaction as a chat-room deletion. */
public record ChatRoomDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String roomId,
        List<Long> participantIds
) implements DomainEvent {
    public static final String TYPE = "CHAT_ROOM_DELETED";

    public ChatRoomDeletedEvent {
        participantIds = participantIds == null
                ? List.of()
                : participantIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    public static ChatRoomDeletedEvent create(String roomId, List<Long> participantIds) {
        return new ChatRoomDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                roomId,
                participantIds
        );
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "ChatRoom";
    }

    @Override
    public String aggregateId() {
        return roomId;
    }
}
