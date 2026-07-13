package com.talkwithneighbors.dto.matching;

import com.talkwithneighbors.entity.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecommendationFeedbackRequest(
        @NotNull RecommendationFeedback.Sentiment sentiment,
        @Size(max = 100) String reason
) {}
