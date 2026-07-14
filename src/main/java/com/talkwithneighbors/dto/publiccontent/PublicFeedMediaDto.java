package com.talkwithneighbors.dto.publiccontent;

import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPostMedia;

public record PublicFeedMediaDto(
        String url,
        FeedMediaType type,
        int sortOrder,
        String thumbnailUrl,
        String contentType,
        Long sizeBytes,
        Integer width,
        Integer height,
        Double durationSeconds
) {
    public PublicFeedMediaDto(String url, FeedMediaType type, int sortOrder) {
        this(url, type, sortOrder, null, null, null, null, null, null);
    }

    public static PublicFeedMediaDto fromEntity(FeedPostMedia media, int sortOrder) {
        return new PublicFeedMediaDto(
                media.getUrl(),
                media.getType(),
                sortOrder,
                media.getThumbnailUrl(),
                media.getContentType(),
                media.getSizeBytes(),
                media.getWidth(),
                media.getHeight(),
                media.getDurationSeconds()
        );
    }
}
