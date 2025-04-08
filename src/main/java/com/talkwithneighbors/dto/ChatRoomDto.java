package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
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
    private String roomName;
    private ChatRoomType type;
    private String creatorId;
    private List<String> participants;
    private String lastMessage;
    private String lastMessageTime;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public static ChatRoomDto fromEntity(ChatRoom chatRoom) {
        ChatRoomDto dto = new ChatRoomDto();
        dto.setId(chatRoom.getId().toString());
        dto.setRoomName(chatRoom.getName());
        dto.setType(chatRoom.getType());
        if (chatRoom.getCreator() != null) {
            dto.setCreatorId(chatRoom.getCreator().getId().toString());
        }
        
        try {
            dto.setParticipants(chatRoom.getParticipants().stream()
                    .map(user -> user.getId().toString())
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            // 지연 로딩 예외 발생 시 빈 리스트 설정
            dto.setParticipants(List.of());
        }
        
        dto.setLastMessage(chatRoom.getLastMessage());
        if (chatRoom.getLastMessageTime() != null) {
            dto.setLastMessageTime(chatRoom.getLastMessageTime().format(formatter));
        }
        return dto;
    }
} 