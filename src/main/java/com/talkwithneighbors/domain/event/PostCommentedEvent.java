package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PostCommentedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String postId,
        String commentId,
        Long postAuthorId,
        Long commenterId,
        String commenterName,
        String snippet
) implements DomainEvent {
    public static final String TYPE = "POST_COMMENTED";

    public static PostCommentedEvent create(
            String postId,
            String commentId,
            Long postAuthorId,
            Long commenterId,
            String commenterName,
            String snippet
    ) {
        return new PostCommentedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                postId,
                commentId,
                postAuthorId,
                commenterId,
                commenterName,
                snippet
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
