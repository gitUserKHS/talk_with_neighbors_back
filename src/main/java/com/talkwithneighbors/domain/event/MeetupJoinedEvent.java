package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record MeetupJoinedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String meetupId,
        String meetupTitle,
        Long joinedUserId,
        Long creatorId
) implements DomainEvent {
    public static final String TYPE = "MEETUP_JOINED";

    public static MeetupJoinedEvent create(String meetupId, String meetupTitle, Long joinedUserId, Long creatorId) {
        return new MeetupJoinedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                meetupId,
                meetupTitle,
                joinedUserId,
                creatorId
        );
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "Meetup";
    }

    @Override
    public String aggregateId() {
        return meetupId;
    }
}
