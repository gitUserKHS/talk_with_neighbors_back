package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.MessageRepository;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
@NoArgsConstructor
public class ChatRoomDto {
    private static final Logger log = LoggerFactory.getLogger(ChatRoomDto.class);

    private String id;
    private String roomName;
    private ChatRoomType type;
    private String creatorId;
    private Set<Long> participantIds;
    private String lastMessage;
    private String lastMessageTime;
    private Integer unreadCount;
    private Integer participantCount; // 이 필드는 유지

    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    // participantCount 파라미터를 제거했습니다.
    public static ChatRoomDto fromEntity(ChatRoom chatRoom, User currentUser, MessageRepository messageRepository) {
        ChatRoomDto dto = new ChatRoomDto();
        // ChatRoom의 ID는 이미 String이므로 .toString()이 필요 없을 수 있습니다.
        // 만약 Long 등 다른 타입이라면 .toString()을 사용해야 합니다.
        // 현재 ChatRoom 엔티티 정의에 따르면 id는 String입니다.
        dto.setId(chatRoom.getId()); 
        dto.setRoomName(chatRoom.getName());
        dto.setType(chatRoom.getType());

        if (chatRoom.getCreator() != null && chatRoom.getCreator().getId() != null) {
            // User의 ID는 Long이므로 .toString()이 필요합니다.
            dto.setCreatorId(chatRoom.getCreator().getId().toString());
        } else {
            dto.setCreatorId(null); // 혹은 적절한 기본값
        }
        
        if (chatRoom.getParticipants() != null) {
            try {
                // 참여자 ID 목록 설정
                dto.setParticipantIds(chatRoom.getParticipants().stream()
                                          .map(User::getId) // User의 ID는 Long
                                          .collect(Collectors.toSet()));
                // 참여자 수를 실제 참여자 목록의 크기로 설정
                dto.setParticipantCount(chatRoom.getParticipants().size()); 
            } catch (org.hibernate.LazyInitializationException e) {
                log.warn("[ChatRoomDto] LazyInitializationException for room: {}. Participants might not have been fetched. Setting count to 0 and ids to empty.", chatRoom.getId(), e);
                dto.setParticipantIds(Set.of());
                dto.setParticipantCount(0);
            }
        } else {
            log.warn("[ChatRoomDto] Participants collection is null for room: {}. Setting count to 0 and ids to empty.", chatRoom.getId());
            dto.setParticipantIds(Set.of());
            dto.setParticipantCount(0);
        }
        
        dto.setLastMessage(chatRoom.getLastMessage());
        if (chatRoom.getLastMessageTime() != null) {
            dto.setLastMessageTime(chatRoom.getLastMessageTime().format(formatter));
        }

        // 안 읽은 메시지 수 계산
        if (currentUser != null && messageRepository != null && chatRoom.getId() != null && currentUser.getId() != null) {
            try {
                // MessageRepository의 countUnreadMessages 메서드가 (String, Long)을 받는지 확인 필요
                long count = messageRepository.countUnreadMessages(chatRoom.getId(), currentUser.getId()); 
                dto.setUnreadCount((int) count);
            } catch (Exception e) {
                log.error("[ChatRoomDto] Error calculating unreadCount for room: {}, user: {}. Error: {}", chatRoom.getId(), currentUser.getId(), e.getMessage(), e);
                dto.setUnreadCount(0);
            }
        } else {
             log.warn("[ChatRoomDto] Skipped unreadCount calculation due to missing data. RoomId: {}, CurrentUserIsNull: {}, CurrentUserIdIsNull: {}, MessageRepositoryIsNull: {}",
                    chatRoom.getId(), 
                    currentUser == null, 
                    (currentUser != null ? currentUser.getId() == null : "N/A (currentUser is null)"),
                    messageRepository == null);
            dto.setUnreadCount(0); 
        }
        
        log.info("[ChatRoomDto] Created DTO for room: {} - participantIds: {}, unread: {}, participantCount: {}", dto.getId(), dto.getParticipantIds(), dto.getUnreadCount(), dto.getParticipantCount());

        return dto;
    }
}