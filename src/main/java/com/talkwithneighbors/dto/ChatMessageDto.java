package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.Message.MessageType;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@Data
public class ChatMessageDto {
    @JsonProperty("chatRoomId")
    private String roomId;
    private Long senderId;  // 사용자 ID
    private String content;
    private MessageType type;
    private LocalDateTime createdAt;
    private boolean isRead;
} 