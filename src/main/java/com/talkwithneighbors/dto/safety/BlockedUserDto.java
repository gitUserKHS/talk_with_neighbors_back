package com.talkwithneighbors.dto.safety;

import com.talkwithneighbors.entity.UserBlock;
import java.time.LocalDateTime;

public record BlockedUserDto(Long userId, String username, String profileImage, LocalDateTime blockedAt) {
    public static BlockedUserDto from(UserBlock block) {
        return new BlockedUserDto(block.getBlocked().getId(), block.getBlocked().getUsername(),
                block.getBlocked().getProfileImage(), block.getCreatedAt());
    }
}
