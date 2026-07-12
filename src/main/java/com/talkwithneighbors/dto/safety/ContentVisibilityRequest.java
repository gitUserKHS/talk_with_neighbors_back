package com.talkwithneighbors.dto.safety;

import com.talkwithneighbors.entity.SafetyTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContentVisibilityRequest(
        @NotNull SafetyTargetType targetType,
        @NotBlank @Size(max = 100) String targetId
) {}
