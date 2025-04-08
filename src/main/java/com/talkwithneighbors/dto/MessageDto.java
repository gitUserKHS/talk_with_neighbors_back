package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.Message.MessageType;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
public class MessageDto {
    private String id;
    private String roomId;
    private String senderId;
    private String senderName;
    private String content;
    private String createdAt;
    private String updatedAt;
    private MessageType type;
    private boolean isDeleted;
    private Set<String> readByUsers;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public static MessageDto fromEntity(Message message) {
        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setRoomId(message.getChatRoom().getId());
        dto.setSenderId(message.getSender().getId().toString());
        dto.setSenderName(message.getSender().getUsername());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt().format(formatter));
        dto.setUpdatedAt(message.getUpdatedAt().format(formatter));
        dto.setType(message.getType());
        dto.setDeleted(message.isDeleted());
        dto.setReadByUsers(message.getReadByUsers().stream()
                .map(Object::toString)
                .collect(Collectors.toSet()));
        return dto;
    }
}