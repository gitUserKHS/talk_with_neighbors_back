package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateChatRoomRequest {
    private String name;
    private String title;
    private ChatRoomType type;
    private String description;
    private Integer maxMembers;
    private Integer maxParticipants;
    private ChatRoomStatus status;

    public String resolvedName() {
        return title != null ? title : name;
    }

    public Integer resolvedMaxParticipants() {
        return maxParticipants != null ? maxParticipants : maxMembers;
    }
}
