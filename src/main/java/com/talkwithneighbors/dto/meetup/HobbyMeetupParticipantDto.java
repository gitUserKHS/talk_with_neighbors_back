package com.talkwithneighbors.dto.meetup;

public record HobbyMeetupParticipantDto(
        Long userId,
        String nickname,
        String profileImageUrl,
        boolean host
) {
}
