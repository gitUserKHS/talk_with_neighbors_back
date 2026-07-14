package com.talkwithneighbors.dto.publiccontent;

import com.talkwithneighbors.entity.ChatRoom;

import java.time.LocalDateTime;
import java.util.List;

public record PublicMeetupDto(
        String id,
        String title,
        String description,
        List<String> interestTags,
        Integer maxParticipants,
        int participantCount,
        boolean full,
        LocalDateTime scheduledAt,
        Integer durationMinutes,
        LocalDateTime registrationDeadline,
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
                room.getScheduledAt(),
                room.getDurationMinutes(),
                room.getRegistrationDeadline(),
                false
        );
    }
}
