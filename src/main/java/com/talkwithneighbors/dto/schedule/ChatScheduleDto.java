package com.talkwithneighbors.dto.schedule;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleRsvp;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

public record ChatScheduleDto(
        String id,
        String roomId,
        Long creatorId,
        String title,
        String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime startsAt,
        Integer durationMinutes,
        String timeZone,
        String location,
        String locationAddress,
        Double latitude,
        Double longitude,
        String kakaoPlaceId,
        ChatScheduleStatus status,
        long version,
        ChatScheduleRsvpStatus currentUserStatus,
        ChatScheduleSummaryDto summary,
        List<ChatScheduleParticipantDto> participants,
        boolean canEdit,
        boolean canCancel,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant cancelledAt
) {
    public static ChatScheduleDto fromEntity(ChatSchedule schedule, Long currentUserId) {
        Long creatorId = schedule.getCreator().getId();
        List<ChatScheduleRsvp> rsvps = schedule.getRsvps() == null
                ? List.of()
                : schedule.getRsvps();
        List<ChatScheduleParticipantDto> participants = rsvps.stream()
                .sorted(Comparator
                        .comparing((ChatScheduleRsvp rsvp) -> !rsvp.getUser().getId().equals(creatorId))
                        .thenComparing(rsvp -> rsvp.getStatus() != ChatScheduleRsvpStatus.ATTENDING)
                        .thenComparing(rsvp -> rsvp.getUser().getUsername(), String.CASE_INSENSITIVE_ORDER))
                .map(rsvp -> new ChatScheduleParticipantDto(
                        rsvp.getUser().getId(),
                        rsvp.getUser().getUsername(),
                        rsvp.getUser().getProfileImage(),
                        rsvp.getStatus(),
                        rsvp.getUser().getId().equals(creatorId)))
                .toList();
        long attending = participants.stream()
                .filter(participant -> participant.status() == ChatScheduleRsvpStatus.ATTENDING)
                .count();
        long notAttending = participants.size() - attending;
        ChatScheduleRsvpStatus currentStatus = rsvps.stream()
                .filter(rsvp -> currentUserId != null && rsvp.getUser().getId().equals(currentUserId))
                .map(ChatScheduleRsvp::getStatus)
                .findFirst()
                .orElse(null);
        boolean owner = currentUserId != null && creatorId.equals(currentUserId);
        boolean editable = owner
                && schedule.getStatus() == ChatScheduleStatus.SCHEDULED
                && schedule.getStartsAt().isAfter(Instant.now());
        ZoneId zone = ZoneId.of(schedule.getTimeZone());

        return new ChatScheduleDto(
                schedule.getId(),
                schedule.getRoom().getId(),
                creatorId,
                schedule.getTitle(),
                schedule.getDescription(),
                schedule.getStartsAt().atZone(zone).toOffsetDateTime(),
                schedule.getDurationMinutes(),
                schedule.getTimeZone(),
                schedule.getLocation(),
                schedule.getLocationAddress(),
                schedule.getLatitude(),
                schedule.getLongitude(),
                schedule.getKakaoPlaceId(),
                schedule.getStatus(),
                schedule.getVersion(),
                currentStatus,
                new ChatScheduleSummaryDto(attending, notAttending, participants.size()),
                List.copyOf(participants),
                editable,
                editable,
                schedule.getCreatedAt(),
                schedule.getUpdatedAt(),
                schedule.getCancelledAt());
    }
}
