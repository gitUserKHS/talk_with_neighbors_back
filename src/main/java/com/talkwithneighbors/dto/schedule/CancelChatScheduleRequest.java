package com.talkwithneighbors.dto.schedule;

import jakarta.validation.constraints.NotNull;

public record CancelChatScheduleRequest(
        @NotNull(message = "일정 버전이 필요해요.") Long version
) {
}
