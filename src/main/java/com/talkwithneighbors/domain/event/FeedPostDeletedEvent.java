package com.talkwithneighbors.domain.event;

import java.util.List;

public record FeedPostDeletedEvent(String postId, List<String> mediaUrls) {
    public FeedPostDeletedEvent {
        mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
    }
}
