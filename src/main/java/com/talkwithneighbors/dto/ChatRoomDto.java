package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.MessageRepository;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.format.DateTimeFormatter;
// Slf4j import 추가
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
@NoArgsConstructor
public class ChatRoomDto {
    // 클래스 레벨에 Logger 인스턴스 생성
    private static final Logger log = LoggerFactory.getLogger(ChatRoomDto.class);

    private String id;
    private String roomName;
    private ChatRoomType type;
    private String creatorId;
    // private List<String> participants; // 채팅방 목록에서는 불필요하므로 주석 처리 또는 삭제
    private String lastMessage;
    private String lastMessageTime;
    private Integer unreadCount;
    private Integer participantCount; // 참가자 수 필드

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    // fromEntity 메서드가 MessageRepository, User, participantCount 정보를 받도록 수정
    public static ChatRoomDto fromEntity(ChatRoom chatRoom, User currentUser, MessageRepository messageRepository, Integer participantCount) {
        ChatRoomDto dto = new ChatRoomDto();
        dto.setId(chatRoom.getId().toString());
        dto.setRoomName(chatRoom.getName());
        dto.setType(chatRoom.getType());
        if (chatRoom.getCreator() != null) {
            dto.setCreatorId(chatRoom.getCreator().getId().toString());
        }
        
        // participants 필드는 LAZY 로딩이고, 여기서는 사용하지 않음
        
        dto.setLastMessage(chatRoom.getLastMessage());
        if (chatRoom.getLastMessageTime() != null) {
            dto.setLastMessageTime(chatRoom.getLastMessageTime().format(formatter));
        }

        // 현재 사용자를 기준으로 안 읽은 메시지 수 계산
        if (currentUser != null && messageRepository != null && chatRoom.getId() != null && currentUser.getId() != null) {
            try {
                log.info("[ChatRoomDto] Calculating unreadCount for room: {}, currentUser ID: {}", chatRoom.getId(), currentUser.getId());
                long count = messageRepository.countUnreadMessages(chatRoom.getId(), currentUser.getId());
                log.info("[ChatRoomDto] Calculated unreadCount: {} for room: {}, currentUser ID: {}", count, chatRoom.getId(), currentUser.getId());
                dto.setUnreadCount((int) count);
            } catch (Exception e) {
                log.error("[ChatRoomDto] Error calculating unreadCount for room: {}, currentUser ID: {}. Error: {}", chatRoom.getId(), currentUser.getId(), e.getMessage(), e);
                dto.setUnreadCount(0); // 오류 발생 시 0으로 설정
            }
        } else {
            // 어떤 조건 때문에 이쪽으로 빠졌는지 확인하기 위한 로그
            log.warn("[ChatRoomDto] Skipped unreadCount calculation due to missing data. RoomId: {}, CurrentUser isNull: {}, CurrentUserId isNull: {}, Repo isNull: {}",
                    chatRoom.getId(), 
                    currentUser == null, 
                    (currentUser != null ? currentUser.getId() == null : "N/A (currentUser is null)"), // currentUser.getId()가 null인 경우도 고려
                    messageRepository == null);
            dto.setUnreadCount(0); 
        }
        
        dto.setParticipantCount(participantCount != null ? participantCount : 0);
        // 최종 DTO 값 로깅 추가
        log.info("[ChatRoomDto] Final DTO for room: {} - unreadCount: {}, participantCount: {}", dto.getId(), dto.getUnreadCount(), dto.getParticipantCount());


        return dto;
    }

    // User, MessageRepository, participantCount 없이 호출되는 fromEntity(ChatRoom chatRoom) 메서드는 삭제했습니다.
    // 만약 다른 곳에서 이 시그니처로 호출하는 부분이 있다면 컴파일 오류가 발생할 것이며,
    // 그 부분을 찾아서 새로운 fromEntity(ChatRoom, User, MessageRepository, Integer) 시그니처에 맞게 수정해야 합니다.
}