package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.domain.event.PostCommentedEvent;
import com.talkwithneighbors.domain.event.PostLikedEvent;
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

    @Test
    void roundTripsPostCommentedEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DomainEventSerializer serializer = new DomainEventSerializer(objectMapper);
        PostCommentedEvent original = PostCommentedEvent.create(
                "post-1", "comment-1", 1L, 2L, "neighbor", "nice photo");

        PostCommentedEvent restored = (PostCommentedEvent) serializer.deserialize(
                original.eventType(), objectMapper.writeValueAsString(original));

        assertThat(restored).isEqualTo(original);
        assertThat(restored.aggregateType()).isEqualTo("FeedPost");
        assertThat(restored.aggregateId()).isEqualTo("post-1");
    }

    @Test
    void roundTripsPostLikedEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DomainEventSerializer serializer = new DomainEventSerializer(objectMapper);
        PostLikedEvent original = PostLikedEvent.create("post-1", 1L, 2L, "neighbor");

        PostLikedEvent restored = (PostLikedEvent) serializer.deserialize(
                original.eventType(), objectMapper.writeValueAsString(original));

        assertThat(restored).isEqualTo(original);
        assertThat(restored.aggregateType()).isEqualTo("FeedPost");
        assertThat(restored.aggregateId()).isEqualTo("post-1");
    }
}
