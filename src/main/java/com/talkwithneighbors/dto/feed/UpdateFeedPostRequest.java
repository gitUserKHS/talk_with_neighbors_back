package com.talkwithneighbors.dto.feed;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Mutable feed metadata. Uploaded media stays immutable after publication so an
 * edit cannot orphan or accidentally expose stored media objects.
 */
public record UpdateFeedPostRequest(
        @Size(max = 2000, message = "캡션은 2,000자 이하로 입력해 줘.")
        String caption,
        @JsonAlias("tags")
        @Size(max = 10, message = "관심사 태그는 최대 10개까지 입력할 수 있어.")
        List<@Size(max = 30, message = "관심사 태그는 각각 30자 이하로 입력해 줘.") String> interestTags,
        Boolean publicPreview
) {
}
