package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.dto.MessageDto;

import java.util.List;

/** Internal event delivered to every participant after an edit or deletion commits. */
public record ChatMessageChangedEvent(
        MessageDto message,
        String roomId,
        String lastMessage,
        String lastMessageTime,
        String lastSenderName,
        List<Long> participantIds
) {
    public ChatMessageChangedEvent {
        participantIds = List.copyOf(participantIds);
    }
}
