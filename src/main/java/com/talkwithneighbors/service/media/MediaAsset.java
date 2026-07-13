package com.talkwithneighbors.service.media;

import java.util.List;
import java.util.stream.Stream;

public record MediaAsset(
        String url,
        String thumbnailUrl,
        MediaAssetKind type,
        String contentType,
        String originalName,
        long sizeBytes,
        Integer width,
        Integer height,
        Double durationSeconds
) {
    public List<String> ownedUrls() {
        return Stream.of(url, thumbnailUrl)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
