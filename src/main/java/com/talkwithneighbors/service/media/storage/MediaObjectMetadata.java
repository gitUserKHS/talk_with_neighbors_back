package com.talkwithneighbors.service.media.storage;

import java.time.Instant;

public record MediaObjectMetadata(
        long contentLength,
        String contentType,
        String eTag,
        Instant lastModified
) {
}
