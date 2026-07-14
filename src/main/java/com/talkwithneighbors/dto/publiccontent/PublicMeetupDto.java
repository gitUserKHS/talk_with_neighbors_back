package com.talkwithneighbors.dto.publiccontent;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.service.MeetupTimePolicy;

import java.time.OffsetDateTime;
import java.util.List;

public record PublicMeetupDto(
        String id,
        String title,
        String description,
        List<String> interestTags,
        Integer maxParticipants,
        int participantCount,
        boolean full,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime scheduledAt,
        Integer durationMinutes,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime registrationDeadline,
        boolean demo
) {
    public static PublicMeetupDto fromEntity(ChatRoom room) {
        int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;
        return new PublicMeetupDto(
                room.getId(),
                room.getName(),
                room.getDescription(),
                room.getInterestTags() != null ? List.copyOf(room.getInterestTags()) : List.of(),
                room.getMaxParticipants(),
                participantCount,
                room.getMaxParticipants() != null && participantCount >= room.getMaxParticipants(),
                MeetupTimePolicy.toUtcOffset(room.getScheduledAt(), room.getMeetupTimeBasis()),
                room.getDurationMinutes(),
                MeetupTimePolicy.toUtcOffset(room.getRegistrationDeadline(), room.getMeetupTimeBasis()),
                false
        );
    }
}
