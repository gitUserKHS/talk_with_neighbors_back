package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.dto.MessageDto;

import java.util.List;

public record ChatScheduleCardChangedEvent(
        MessageDto message,
        String roomId,
        List<Long> participantIds
) {
    public ChatScheduleCardChangedEvent {
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
    }
}
