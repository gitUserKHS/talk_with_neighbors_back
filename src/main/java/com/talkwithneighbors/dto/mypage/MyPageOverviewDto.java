package com.talkwithneighbors.dto.mypage;

public record MyPageOverviewDto(
        int profileCompletion,
        long postCount,
        long commentCount,
        long likedPostCount,
        long createdMeetupCount,
        long joinedMeetupCount
) {}
