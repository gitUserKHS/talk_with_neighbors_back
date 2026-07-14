package com.talkwithneighbors.dto.feed;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CreateFeedPostRequest {
    private String imageUrl;
    private String caption;
    private List<String> interestTags;
    private boolean publicPreview = false;
}
