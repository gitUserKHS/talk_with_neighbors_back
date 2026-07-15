package com.talkwithneighbors.dto.schedule;

public record ChatScheduleSummaryDto(
        long attendingCount,
        long notAttendingCount,
        long totalResponses
) {
}
