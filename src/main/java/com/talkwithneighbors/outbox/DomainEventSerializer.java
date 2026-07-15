package com.talkwithneighbors.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.domain.event.DomainEvent;
import com.talkwithneighbors.domain.event.MatchCompletedEvent;
import com.talkwithneighbors.domain.event.MeetupJoinedEvent;
import com.talkwithneighbors.domain.event.UserBlockedEvent;
import com.talkwithneighbors.domain.event.ContentReportedEvent;
import com.talkwithneighbors.domain.event.ChatRoomDeletedEvent;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DomainEventSerializer {
    private final ObjectMapper objectMapper;

    public DomainEvent deserialize(String eventType, String payload) throws JsonProcessingException {
        return switch (eventType) {
            case MatchCompletedEvent.TYPE -> objectMapper.readValue(payload, MatchCompletedEvent.class);
            case MeetupJoinedEvent.TYPE -> objectMapper.readValue(payload, MeetupJoinedEvent.class);
            case UserBlockedEvent.TYPE -> objectMapper.readValue(payload, UserBlockedEvent.class);
            case ContentReportedEvent.TYPE -> objectMapper.readValue(payload, ContentReportedEvent.class);
            case ChatRoomDeletedEvent.TYPE -> objectMapper.readValue(payload, ChatRoomDeletedEvent.class);
            case MediaFilesDeletedEvent.TYPE -> objectMapper.readValue(payload, MediaFilesDeletedEvent.class);
            default -> throw new IllegalArgumentException("Unsupported domain event type: " + eventType);
        };
    }
}
