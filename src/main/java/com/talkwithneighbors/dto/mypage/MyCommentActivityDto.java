package com.talkwithneighbors.dto.mypage;

import java.time.LocalDateTime;

public record MyCommentActivityDto(
        String id,
        String postId,
        String postCaption,
        String content,
        LocalDateTime createdAt
) {}
