package com.talkwithneighbors.dto.meetup;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
public class HobbyMeetupDto {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    private String roomId;
    private String title;
    private String description;
    private List<String> interestTags;
    private List<String> sharedInterests;
    private String location;
    private Integer maxParticipants;
    private Integer participantCount;
    private boolean joined;
    private boolean full;
    private String creatorUsername;
    private String lastMessage;
    private String lastMessageTime;
    private String scheduledAt;
    private Integer durationMinutes;
    private String registrationDeadline;
    private boolean waitlisted;
    private long waitlistCount;

    public static HobbyMeetupDto fromEntity(ChatRoom room, User currentUser) {
        HobbyMeetupDto dto = new HobbyMeetupDto();
        List<String> tags = room.getInterestTags() != null ? List.copyOf(room.getInterestTags()) : List.of();
        Set<String> currentInterests = currentUser != null && currentUser.getInterests() != null
                ? currentUser.getInterests().stream()
                        .filter(Objects::nonNull)
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet())
                : Set.of();
        int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;

        dto.setRoomId(room.getId());
        dto.setTitle(room.getName());
        dto.setDescription(room.getDescription());
        dto.setInterestTags(tags);
        dto.setSharedInterests(tags.stream()
                .filter(Objects::nonNull)
                .filter(tag -> currentInterests.contains(tag.trim().toLowerCase(Locale.ROOT)))
                .toList());
        dto.setLocation(room.getLocation());
        dto.setMaxParticipants(room.getMaxParticipants());
        dto.setParticipantCount(participantCount);
        dto.setJoined(currentUser != null && room.getParticipants() != null
                && room.getParticipants().stream().anyMatch(user -> user.getId().equals(currentUser.getId())));
        dto.setFull(room.getMaxParticipants() != null && participantCount >= room.getMaxParticipants());
        dto.setCreatorUsername(room.getCreator() != null ? room.getCreator().getUsername() : null);
        dto.setLastMessage(room.getLastMessage());
        if (room.getLastMessageTime() != null) {
            dto.setLastMessageTime(room.getLastMessageTime().format(DATE_TIME_FORMATTER));
        }
        if (room.getScheduledAt() != null) dto.setScheduledAt(room.getScheduledAt().format(DATE_TIME_FORMATTER));
        dto.setDurationMinutes(room.getDurationMinutes());
        if (room.getRegistrationDeadline() != null) dto.setRegistrationDeadline(room.getRegistrationDeadline().format(DATE_TIME_FORMATTER));
        return dto;
    }
}
