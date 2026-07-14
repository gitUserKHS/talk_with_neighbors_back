package com.talkwithneighbors.dto.publiccontent;

import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record PublicFeedPostDto(
        String id,
        String authorDisplayName,
        String imageUrl,
        List<PublicFeedMediaDto> media,
        String caption,
        List<String> interestTags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long likeCount,
        long commentCount,
        boolean demo
) {
    private static final String ANONYMOUS_AUTHOR = "이웃";

    public static PublicFeedPostDto fromEntity(FeedPost post, long likeCount, long commentCount) {
        if (!post.isPublicPreview()) {
            throw new IllegalArgumentException("Private feed posts cannot be mapped to a public DTO.");
        }
        List<PublicFeedMediaDto> media = new ArrayList<>();
        if (post.getMedia() != null) {
            for (int index = 0; index < post.getMedia().size(); index++) {
                media.add(PublicFeedMediaDto.fromEntity(post.getMedia().get(index), index));
            }
        }
        if (media.isEmpty() && post.getImageUrl() != null && !post.getImageUrl().isBlank()) {
            media.add(new PublicFeedMediaDto(post.getImageUrl(), FeedMediaType.IMAGE, 0));
        }

        return new PublicFeedPostDto(
                post.getId(),
                ANONYMOUS_AUTHOR,
                post.getImageUrl(),
                List.copyOf(media),
                post.getCaption(),
                post.getInterestTags() != null ? List.copyOf(post.getInterestTags()) : List.of(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                likeCount,
                commentCount,
                false
        );
    }
}
