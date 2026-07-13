package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.dto.MessageDto;

import java.util.List;

/** Internal event handled only after the message transaction commits. */
public record ChatMessageCommittedEvent(
        MessageDto message,
        String roomId,
        Long senderId,
        List<Long> participantIds
) {
    public ChatMessageCommittedEvent {
        participantIds = List.copyOf(participantIds);
    }
}
