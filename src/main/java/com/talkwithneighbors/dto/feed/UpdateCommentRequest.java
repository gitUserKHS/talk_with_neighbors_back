package com.talkwithneighbors.dto.feed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommentRequest(
        @NotBlank(message = "댓글 내용을 입력해 줘.")
        @Size(max = 1000, message = "댓글은 1,000자 이하로 입력해 줘.")
        String content
) {
}
