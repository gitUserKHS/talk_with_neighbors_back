package com.talkwithneighbors.dto.feed;

import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
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
    private String caption;
    private List<String> interestTags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long likeCount;
    private long commentCount;
    private boolean likedByCurrentUser;
    private int compatibilityScore;
    private List<String> sharedInterests;

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
        dto.setCaption(post.getCaption());
        dto.setInterestTags(post.getInterestTags() != null ? post.getInterestTags() : List.of());
        dto.setCreatedAt(post.getCreatedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        dto.setLikeCount(likeCount);
        dto.setCommentCount(commentCount);
        dto.setLikedByCurrentUser(likedByCurrentUser);
        dto.setCompatibilityScore(compatibilityScore);
        dto.setSharedInterests(sharedInterests != null ? sharedInterests : List.of());
        return dto;
    }
}
