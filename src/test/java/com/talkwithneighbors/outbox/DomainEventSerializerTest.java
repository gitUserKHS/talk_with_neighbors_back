package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventSerializerTest {

    @Test
    void roundTripsDurableMediaDeletionEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DomainEventSerializer serializer = new DomainEventSerializer(objectMapper);
        MediaFilesDeletedEvent original = MediaFilesDeletedEvent.create(
                "FeedPost",
                "post-1",
                List.of("/uploads/feed/image.webp", "/uploads/feed/image-thumbnail.webp"));

        MediaFilesDeletedEvent restored = (MediaFilesDeletedEvent) serializer.deserialize(
                original.eventType(), objectMapper.writeValueAsString(original));

        assertThat(restored).isEqualTo(original);
    }
}
