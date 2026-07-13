package com.talkwithneighbors.dto.feed;

import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class PostCommentDto {
    private String id;
    private Long authorId;
    private String authorUsername;
    private String authorProfileImage;
    private String content;
    private LocalDateTime createdAt;

    public static PostCommentDto fromEntity(PostComment comment) {
        PostCommentDto dto = new PostCommentDto();
        User author = comment.getAuthor();
        dto.setId(comment.getId());
        dto.setAuthorId(author != null ? author.getId() : null);
        dto.setAuthorUsername(author != null ? author.getUsername() : null);
        dto.setAuthorProfileImage(author != null ? author.getProfileImage() : null);
        dto.setContent(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        return dto;
    }
}
