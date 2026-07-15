package com.talkwithneighbors.dto.schedule;

import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateChatScheduleRsvpRequest(
        @NotNull(message = "참석 여부를 선택해 주세요.") ChatScheduleRsvpStatus status
) {
}
