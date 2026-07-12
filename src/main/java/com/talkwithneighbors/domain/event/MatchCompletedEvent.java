package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record MatchCompletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String matchId,
        String chatRoomId,
        Long user1Id,
        Long user2Id
) implements DomainEvent {
    public static final String TYPE = "MATCH_COMPLETED";

    public static MatchCompletedEvent create(String matchId, String chatRoomId, Long user1Id, Long user2Id) {
        return new MatchCompletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                matchId,
                chatRoomId,
                user1Id,
                user2Id
        );
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "Match";
    }

    @Override
    public String aggregateId() {
        return matchId;
    }
}
