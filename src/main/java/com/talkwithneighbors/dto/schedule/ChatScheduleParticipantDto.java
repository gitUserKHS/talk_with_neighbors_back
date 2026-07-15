package com.talkwithneighbors.dto.schedule;

import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;

public record ChatScheduleParticipantDto(
        Long userId,
        String nickname,
        String profileImage,
        ChatScheduleRsvpStatus status,
        boolean host
) {
}
