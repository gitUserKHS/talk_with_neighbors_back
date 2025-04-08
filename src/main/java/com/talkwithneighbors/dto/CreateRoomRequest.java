package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoomType;
import lombok.Data;
import java.util.List;

@Data
public class CreateRoomRequest {
    private String name;
    private ChatRoomType type;
    private List<Long> participantIds;
} 