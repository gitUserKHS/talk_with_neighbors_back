package com.talkwithneighbors.dto.mypage;

public record UserPreferencesDto(
        boolean profileDiscoverable,
        boolean showNeighborhood,
        boolean matchNotificationsEnabled,
        boolean chatNotificationsEnabled,
        boolean meetupNotificationsEnabled
) {}
