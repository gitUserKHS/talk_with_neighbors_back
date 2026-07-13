package com.talkwithneighbors.service.media;

import java.nio.file.Path;

public record MediaProcessingRequest(
        Path input,
        Path outputDirectory,
        String baseName,
        MediaAssetKind type,
        String sourceExtension,
        boolean generateThumbnail,
        boolean preserveAnimation,
        int maxDimension
) {
}
