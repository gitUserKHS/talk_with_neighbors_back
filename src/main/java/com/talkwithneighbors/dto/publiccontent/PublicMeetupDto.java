package com.talkwithneighbors.dto.publiccontent;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.service.MeetupTimePolicy;

import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
        String location,
        String locationAddress,
        Double latitude,
        Double longitude,
        String kakaoPlaceId,
        boolean official
) {
    public static PublicMeetupDto fromEntity(ChatRoom room) {
        int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;
        User creator = room.getCreator();
        boolean official = creator != null && creator.getAccountType() == UserAccountType.SYSTEM;
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
                official ? room.getLocation() : null,
                official ? room.getLocationAddress() : null,
                official ? room.getLatitude() : null,
                official ? room.getLongitude() : null,
                official ? room.getKakaoPlaceId() : null,
                official
        );
    }
}
