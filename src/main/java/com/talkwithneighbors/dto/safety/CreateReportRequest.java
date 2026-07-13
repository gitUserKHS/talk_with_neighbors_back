package com.talkwithneighbors.dto.safety;

import com.talkwithneighbors.entity.ReportReason;
import com.talkwithneighbors.entity.SafetyTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReportRequest(
        @NotNull SafetyTargetType targetType,
        @NotBlank @Size(max = 100) String targetId,
        @NotNull ReportReason reason,
        @Size(max = 1000) String details,
        boolean hideContent
) {}
