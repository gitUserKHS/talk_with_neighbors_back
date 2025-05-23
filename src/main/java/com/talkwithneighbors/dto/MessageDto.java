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
    private Set<Long> readByUsers;
    private boolean readByCurrentUser;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public static MessageDto fromEntity(Message message, Long currentUserId) {
        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setRoomId(message.getChatRoom().getId());
        dto.setSenderId(message.getSender().getId().toString());
        dto.setSenderName(message.getSender().getUsername());
        dto.setContent(message.getContent());
        if (message.getCreatedAt() != null) {
            dto.setCreatedAt(message.getCreatedAt().format(formatter));
        }
        if (message.getUpdatedAt() != null) {
            dto.setUpdatedAt(message.getUpdatedAt().format(formatter));
        }
        dto.setType(message.getType());
        dto.setDeleted(message.isDeleted());
        
        Set<Long> readByUserIds = message.getReadByUsers();
        dto.setReadByUsers(readByUserIds);
        
        if (currentUserId != null && readByUserIds != null) {
            dto.setReadByCurrentUser(readByUserIds.contains(currentUserId));
        } else {
            dto.setReadByCurrentUser(false);
        }
        
        return dto;
    }

    public static MessageDto fromEntity(Message message) {
        return fromEntity(message, null);
    }
}