package com.talkwithneighbors.dto.admin;

import com.talkwithneighbors.entity.ReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewReportRequest(
        @NotNull ReportStatus status,
        @Size(max = 1000) String note
) {
}
