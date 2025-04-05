package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoom;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
public class ChatRoomDto {
    private String id;
    private List<String> participants;
    private String lastMessage;
    private String lastMessageTime;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public static ChatRoomDto fromEntity(ChatRoom chatRoom) {
        ChatRoomDto dto = new ChatRoomDto();
        dto.setId(chatRoom.getId().toString());
        dto.setParticipants(chatRoom.getParticipants().stream()
                .map(user -> user.getId().toString())
                .collect(Collectors.toList()));
        dto.setLastMessage(chatRoom.getLastMessage());
        if (chatRoom.getLastMessageTime() != null) {
            dto.setLastMessageTime(chatRoom.getLastMessageTime().format(formatter));
        }
        return dto;
    }
} 