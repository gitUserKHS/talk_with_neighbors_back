package com.talkwithneighbors.dto.feed;

import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class FeedPostDto {
    private String id;
    private Long authorId;
    private String authorUsername;
    private String authorProfileImage;
    private String imageUrl;
    private List<FeedMediaDto> media;
    private String caption;
    private List<String> interestTags;
    private boolean publicPreview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long likeCount;
    private long commentCount;
    private boolean likedByCurrentUser;
    private int compatibilityScore;
    private List<String> sharedInterests;
    private Double distanceKm;
    private String neighborhoodName;
    private List<String> recommendationReasons;

    public static FeedPostDto fromEntity(
            FeedPost post,
            long likeCount,
            long commentCount,
            boolean likedByCurrentUser,
            int compatibilityScore,
            List<String> sharedInterests
    ) {
        FeedPostDto dto = new FeedPostDto();
        User author = post.getAuthor();
        dto.setId(post.getId());
        dto.setAuthorId(author != null ? author.getId() : null);
        dto.setAuthorUsername(author != null ? author.getUsername() : null);
        dto.setAuthorProfileImage(author != null ? author.getProfileImage() : null);
        dto.setImageUrl(post.getImageUrl());
        List<FeedMediaDto> media = new ArrayList<>();
        if (post.getMedia() != null) {
            for (int index = 0; index < post.getMedia().size(); index++) {
                media.add(FeedMediaDto.fromEntity(post.getMedia().get(index), index));
            }
        }
        if (media.isEmpty() && post.getImageUrl() != null && !post.getImageUrl().isBlank()) {
            media.add(new FeedMediaDto(post.getImageUrl(), FeedMediaType.IMAGE, 0));
        }
        dto.setMedia(List.copyOf(media));
        dto.setCaption(post.getCaption());
        dto.setInterestTags(post.getInterestTags() != null ? List.copyOf(post.getInterestTags()) : List.of());
        dto.setPublicPreview(post.isPublicPreview());
        dto.setCreatedAt(post.getCreatedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        dto.setLikeCount(likeCount);
        dto.setCommentCount(commentCount);
        dto.setLikedByCurrentUser(likedByCurrentUser);
        dto.setCompatibilityScore(compatibilityScore);
        dto.setSharedInterests(sharedInterests != null ? List.copyOf(sharedInterests) : List.of());
        dto.setRecommendationReasons(List.of());
        return dto;
    }
}
