package com.talkwithneighbors.service.media;

import java.nio.file.Path;

public record ProcessedMedia(
        Path mediaPath,
        Path thumbnailPath,
        String contentType,
        Integer width,
        Integer height,
        Double durationSeconds
) {
}
