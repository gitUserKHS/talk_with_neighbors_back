package com.talkwithneighbors.domain.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Durable, idempotent request to delete media objects after a DB mutation. */
public record MediaFilesDeletedEvent(
        String eventId,
        LocalDateTime occurredAt,
        String aggregateType,
        String aggregateId,
        List<String> mediaUrls
) implements DomainEvent {
    public static final String TYPE = "MEDIA_FILES_DELETED";

    public MediaFilesDeletedEvent {
        mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
    }

    public static MediaFilesDeletedEvent create(
            String aggregateType,
            String aggregateId,
            List<String> mediaUrls
    ) {
        return new MediaFilesDeletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                aggregateType,
                aggregateId,
                mediaUrls);
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
