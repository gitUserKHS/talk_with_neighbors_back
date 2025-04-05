package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.Message;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.format.DateTimeFormatter;

@Getter
@Setter
@NoArgsConstructor
public class MessageDto {
    private String id;
    private String roomId;
    private String senderId;
    private String content;
    private String createdAt;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public static MessageDto fromEntity(Message message) {
        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setRoomId(message.getChatRoom().getId());
        dto.setSenderId(message.getSender().getId().toString());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt().format(formatter));
        return dto;
    }
} 