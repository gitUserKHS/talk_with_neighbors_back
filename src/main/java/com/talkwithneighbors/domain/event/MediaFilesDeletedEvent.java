package com.talkwithneighbors.domain.event;

import java.util.List;

public record MediaFilesDeletedEvent(List<String> mediaUrls) {
    public MediaFilesDeletedEvent {
        mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
    }
}
