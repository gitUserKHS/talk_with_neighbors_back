package com.talkwithneighbors.dto.mypage;

public record UpdateUserPreferencesRequest(
        Boolean profileDiscoverable,
        Boolean showNeighborhood,
        Boolean matchNotificationsEnabled,
        Boolean chatNotificationsEnabled,
        Boolean meetupNotificationsEnabled
) {}
