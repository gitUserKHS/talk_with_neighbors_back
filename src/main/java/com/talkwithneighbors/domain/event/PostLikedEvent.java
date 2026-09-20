package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PostLikedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String postId,
        Long postAuthorId,
        Long likerId,
        String likerName
) implements DomainEvent {
    public static final String TYPE = "POST_LIKED";

    public static PostLikedEvent create(String postId, Long postAuthorId, Long likerId, String likerName) {
        return new PostLikedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                postId,
                postAuthorId,
                likerId,
                likerName
        );
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "FeedPost";
    }

    @Override
    public String aggregateId() {
        return postId;
    }
}
