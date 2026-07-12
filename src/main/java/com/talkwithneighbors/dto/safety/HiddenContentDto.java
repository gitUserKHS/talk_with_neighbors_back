package com.talkwithneighbors.dto.safety;

import com.talkwithneighbors.entity.SafetyTargetType;

import java.time.LocalDateTime;

public record HiddenContentDto(
        Long id,
        SafetyTargetType targetType,
        String targetId,
        String title,
        String preview,
        String imageUrl,
        boolean available,
        LocalDateTime hiddenAt
) {}
