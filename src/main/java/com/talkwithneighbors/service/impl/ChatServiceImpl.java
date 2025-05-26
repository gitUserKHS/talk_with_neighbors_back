package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public ChatRoomDto createRoom(String name, ChatRoomType type, String creatorIdString, List<String> participantNicknames) {
        Long creatorId = Long.parseLong(creatorIdString);
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ChatException("Creator not found with id: " + creatorId, HttpStatus.NOT_FOUND));

        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setType(type);
        chatRoom.setCreator(creator);
        chatRoom.getParticipants().add(creator); // 생성자는 항상 참여

        List<User> participantsFound = new ArrayList<>();
        if (participantNicknames != null && !participantNicknames.isEmpty()) {
            // 자기 자신(생성자)의 닉네임은 제외하고, 중복된 닉네임도 제거
            List<String> distinctOtherUsernames = participantNicknames.stream()
                                                              .filter(username -> !username.equalsIgnoreCase(creator.getUsername()))
                                                              .distinct()
                                                              .collect(Collectors.toList());
            if (!distinctOtherUsernames.isEmpty()) {
                participantsFound = userRepository.findAllByUsernameIn(distinctOtherUsernames);
                if (participantsFound.size() != distinctOtherUsernames.size()) {
                    // 요청한 닉네임 중 일부를 찾지 못한 경우
                    List<String> foundUsernames = participantsFound.stream().map(User::getUsername).collect(Collectors.toList());
                    List<String> notFoundUsernames = distinctOtherUsernames.stream()
                                                                          .filter(reqName -> foundUsernames.stream().noneMatch(foundName -> foundName.equalsIgnoreCase(reqName)))
                                                                          .collect(Collectors.toList());
                    log.warn("Could not find users for all requested usernames. Requested: {}, Found: {}, Not Found: {}", distinctOtherUsernames, foundUsernames, notFoundUsernames);
                    // 정책: 찾지 못한 사용자가 있으면 채팅방 생성 실패 처리
                    throw new ChatException("Could not find user(s): " + String.join(", ", notFoundUsernames) + ". Please check the usernames.", HttpStatus.BAD_REQUEST);
                }
            }
        }

        if (type == ChatRoomType.ONE_ON_ONE) {
            // 1:1 채팅은 생성자 외 정확히 1명의 다른 참여자가 필요
            if (participantsFound.size() != 1) {
                throw new ChatException("ONE_ON_ONE chat requires exactly one other participant (excluding yourself). Found " + participantsFound.size() + " other participants.", HttpStatus.BAD_REQUEST);
            }
            
            User otherParticipant = participantsFound.get(0);
            // 생성자와 다른 참여자가 동일 인물인지 한 번 더 확인 (닉네임 대소문자 등으로 필터링 우회 가능성 방지)
            if (otherParticipant.getId().equals(creator.getId())) {
                 throw new ChatException("ONE_ON_ONE chat cannot be created with oneself as the only other participant.", HttpStatus.BAD_REQUEST);
            }
            
            chatRoom.getParticipants().add(otherParticipant); // 다른 참여자 추가
            
            // 1:1 채팅방 이름: "유저명1, 유저명2" (참여자는 이미 2명으로 확정됨)
            List<String> chatParticipantNames = chatRoom.getParticipants().stream()
                                                        .map(User::getUsername)
                                                        .sorted(String::compareToIgnoreCase)
                                                        .collect(Collectors.toList());
            chatRoom.setName(String.join(", ", chatParticipantNames));

        } else { // GROUP chat
            if (name == null || name.trim().isEmpty()) {
                // 그룹 채팅은 이름이 필수 (변경 가능: 이름 없으면 참여자 기반 자동생성 등)
                throw new ChatException("Group chat name cannot be empty.", HttpStatus.BAD_REQUEST);
            }
            chatRoom.setName(name);
            if (!participantsFound.isEmpty()) { // 조회된 참여자가 있다면 추가
                participantsFound.forEach(p -> {
                    if (!chatRoom.getParticipants().contains(p)) { // 중복 추가 방지
                        chatRoom.getParticipants().add(p);
                    }
                });
            }
            // 그룹 채팅 최소/최대 인원 제한 등 추가 정책이 있다면 여기서 검증
        }
        
        ChatRoom savedChatRoom = chatRoomRepository.save(chatRoom);
        log.info("Chat room created: ID={}, Name='{}', Type={}, Creator={}, ParticipantsCount={}", 
                 savedChatRoom.getId(), savedChatRoom.getName(), savedChatRoom.getType(), 
                 savedChatRoom.getCreator().getUsername(), savedChatRoom.getParticipants().size());
        
        return ChatRoomDto.fromEntity(savedChatRoom, creator, messageRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> getChatRoomsForUser(String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        // 최근 활동 순으로 정렬된 채팅방 목록 조회 (카카오톡과 같은 방식)
        Page<ChatRoom> roomsPage = chatRoomRepository.findByParticipantsContainingOrderByLastMessageTimeDesc(user, pageable);
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, user, messageRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRoomDto getRoomById(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findByIdAndParticipantsContaining(roomId, currentUser)
                .orElseThrow(() -> new RuntimeException("Chat room not found or user not a participant"));
        return ChatRoomDto.fromEntity(chatRoom, currentUser, messageRepository);
    }

    @Override
    @Transactional
    public void joinRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        if (chatRoom.getParticipants().contains(user)) {
            log.info("User {} is already a participant in room {}", user.getId(), roomId);
            return;
        }
        chatRoom.getParticipants().add(user);
        chatRoomRepository.save(chatRoom);
        log.info("User {} successfully joined room {}", user.getId(), roomId);
    }

    @Override
    @Transactional
    public void leaveRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        if (chatRoom.getParticipants().remove(user)) {
            chatRoomRepository.save(chatRoom);
            log.info("User {} left room {}", user.getId(), roomId);
        } else {
            log.warn("User {} was not a participant in room {}. No action taken.", user.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public MessageDto sendMessage(String roomId, Long senderId, String content) {
        log.info("[SendMessage] Attempting to send message. RoomId: {}, SenderId: {}, Content: '{}'", roomId, senderId, content);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> {
                    log.error("[SendMessage] Chat room not found with id: {}", roomId);
                    return new ChatException("Chat room not found: " + roomId, HttpStatus.NOT_FOUND);
                });
        log.info("[SendMessage] Found chat room: ID={}, Name='{}'", room.getId(), room.getName());

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> {
                    log.error("[SendMessage] Sender not found with id: {}", senderId);
                    return new ChatException("User not found: " + senderId, HttpStatus.NOT_FOUND);
                });
        log.info("[SendMessage] Found sender: ID={}, Username='{}'", sender.getId(), sender.getUsername());
        
        // 메시지 발신자가 채팅방 참여자인지 확인 (선택적이지만 권장)
        if (room.getParticipants().stream().noneMatch(p -> p.getId().equals(senderId))) {
            log.warn("[SendMessage] Sender (ID: {}) is not a participant in room (ID: {}). Message will be saved but may not be intended.", senderId, roomId);
            // 필요시 여기서 예외를 던져 메시지 전송을 막을 수 있습니다.
            // throw new ChatException("Sender is not a participant of this chat room.", HttpStatus.FORBIDDEN);
        }

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        message.getReadByUsers().add(sender.getId()); // 발신자는 항상 읽음 처리
        log.info("[SendMessage] Prepared message object: ID={}, ChatRoomID={}, SenderID={}, Content='{}', CreatedAt={}", 
                 message.getId(), message.getChatRoom().getId(), message.getSender().getId(), message.getContent(), message.getCreatedAt());

        Message savedMessage;
        try {
            savedMessage = messageRepository.save(message);
            log.info("[SendMessage] Message saved to DB: ID={}, Content='{}'", savedMessage.getId(), savedMessage.getContent());
        } catch (Exception e) {
            log.error("[SendMessage] Failed to save message to DB. RoomId: {}, SenderId: {}", roomId, senderId, e);
            throw new ChatException("Failed to save message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        try {
            room.setLastMessage(savedMessage.getContent());
            room.setLastMessageTime(savedMessage.getCreatedAt());
            chatRoomRepository.save(room);
            log.info("[SendMessage] Updated chat room's last message: RoomID={}, LastMessage='{}'", room.getId(), savedMessage.getContent());
        } catch (Exception e) {
            log.error("[SendMessage] Failed to update chat room's last message. RoomID: {}", room.getId(), e);
            // 이 오류는 메시지 전송 자체를 실패시키지는 않음 (이미 메시지는 저장됨)
        }

        MessageDto messageDto = MessageDto.fromEntity(savedMessage, sender.getId());
        log.info("[SendMessage] Created MessageDto: ID={}, Content='{}', SenderId={}", messageDto.getId(), messageDto.getContent(), messageDto.getSenderId());

        // === 실시간 WebSocket 전송 개선 ===
        
        // 1. 채팅방 내 다른 참여자에게만 실시간 메시지 전송 (송신자 제외)
        String topicDestinationBase = "/queue/chat/room/"; // 사용자별 큐로 변경
        for (User participant : room.getParticipants()) {
            if (!participant.getId().equals(senderId)) { // 송신자 제외
                try {
                    String userSpecificDestination = topicDestinationBase + roomId; // 목적지는 동일하게 유지하되, convertAndSendToUser 사용
                    messagingTemplate.convertAndSendToUser(participant.getId().toString(), userSpecificDestination, messageDto);
                    log.info("[SendMessage] Successfully sent message to participant {} via WebSocket. Destination: '{}', MessageID: '{}'", 
                             participant.getId(), userSpecificDestination, messageDto.getId());
                } catch (Exception e) {
                    log.error("[SendMessage] Failed to send message to participant {} via WebSocket. Destination: '{}', MessageID: '{}'", 
                              participant.getId(), topicDestinationBase + roomId, messageDto.getId(), e);
                }
            }
        }
        
        // 2. 채팅방 밖에 있는 참여자들에게 새 메시지 알림 전송 (채팅방 목록용)
        try {
            notificationService.sendNewMessageNotification(savedMessage, room, senderId);
            log.info("[SendMessage] Successfully sent new message notification for messageID: '{}'", savedMessage.getId());
        } catch (Exception e) {
            log.error("[SendMessage] Failed to send new message notification for messageID: '{}': {}", savedMessage.getId(), e.getMessage(), e);
        }
        
        return messageDto;
    }

    @Override
    @Transactional
    public Page<MessageDto> getMessagesByRoomId(String roomId, String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ChatException("User not found with id: " + userId, HttpStatus.NOT_FOUND));

        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("Chat room not found with id: " + roomId, HttpStatus.NOT_FOUND));

        boolean isParticipant = chatRoom.getParticipants().stream()
                .anyMatch(participant -> participant.getId().equals(user.getId()));

        if (!isParticipant) {
            throw new ChatException("Access denied to chat room " + roomId, HttpStatus.FORBIDDEN);
        }

        Page<Message> messagesPage = messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId, pageable);
        
        List<Message> messagesToUpdate = new java.util.ArrayList<>();
        List<String> readMessageIds = new java.util.ArrayList<>();
        
        messagesPage.getContent().forEach(msg -> {
            if (!msg.getReadByUsers().contains(user.getId())) {
                msg.getReadByUsers().add(user.getId());
                messagesToUpdate.add(msg);
                readMessageIds.add(msg.getId());
            }
        });
        
        if (!messagesToUpdate.isEmpty()) {
            try {
                messageRepository.saveAll(messagesToUpdate);
                log.info("[GetMessages] Marked {} messages as read for user {} in room {}", 
                         messagesToUpdate.size(), user.getId(), roomId);
                
                // 읽음 상태 변경 알림 전송
                for (String messageId : readMessageIds) {
                    try {
                        notificationService.sendMessageReadStatusUpdate(messageId, roomId, user.getId());
                    } catch (Exception e) {
                        log.error("[GetMessages] Failed to send read status update for messageId {}: {}", 
                                  messageId, e.getMessage(), e);
                    }
                }
                
            } catch (Exception e) {
                log.error("[GetMessages] Failed to save read status updates for user {} in room {}: {}", 
                          user.getId(), roomId, e.getMessage(), e);
            }
        }

        return messagesPage.map(msg -> MessageDto.fromEntity(msg, user.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> searchRooms(String query, ChatRoomType type, String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        
        Page<ChatRoom> roomsPage;
        String trimmedQuery = (query != null) ? query.trim() : "";

        if (trimmedQuery.isEmpty()) {
            if (type == null) {
                roomsPage = chatRoomRepository.findAll(pageable);
            } else {
                roomsPage = chatRoomRepository.findByType(type, pageable);
            }
        } else {
            if (type == null) {
                roomsPage = chatRoomRepository.findByNameContainingIgnoreCaseOrIdContainingIgnoreCase(trimmedQuery, trimmedQuery, pageable);
            } else {
                roomsPage = chatRoomRepository.findByTypeAndNameContainingIgnoreCaseOrTypeAndIdContainingIgnoreCase(type, trimmedQuery, type, trimmedQuery, pageable);
            }
        }
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, currentUser, messageRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> getAllRooms(Pageable pageable) {
        Page<ChatRoom> roomsPage = chatRoomRepository.findAll(pageable);
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, null, messageRepository)); 
    }

    @Override
    @Transactional
    public void deleteRoom(String roomId) {
        log.info("[deleteRoom] Starting deletion process for roomId: {}", roomId);
        
        try {
            ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new ChatException("Chat room not found: " + roomId, HttpStatus.NOT_FOUND));
            
            log.info("[deleteRoom] Found chat room: {} with {} participants", roomId, chatRoom.getParticipants().size());
            
            // 참여자들에게 알림 보내기 (삭제 전에)
            List<Long> participantIds = chatRoom.getParticipants().stream()
                    .map(User::getId)
                    .collect(Collectors.toList());
            
            log.info("[deleteRoom] Notifying {} participants before deletion", participantIds.size());
            
            // 1. 먼저 메시지 읽음 상태 삭제
            try {
                messageRepository.deleteReadStatusByChatRoomId(roomId);
                log.info("[deleteRoom] Successfully deleted message read statuses for roomId: {}", roomId);
            } catch (Exception e) {
                log.warn("[deleteRoom] Failed to delete message read statuses for roomId: {} - {}", roomId, e.getMessage());
                // 읽음 상태 삭제 실패 시에도 계속 진행 (메시지 삭제 시 함께 삭제될 수 있음)
            }
            
            // 2. 그 다음 메시지 삭제
            try {
                messageRepository.deleteByChatRoomId(roomId);
                log.info("[deleteRoom] Successfully deleted messages for roomId: {}", roomId);
            } catch (Exception e) {
                log.error("[deleteRoom] Failed to delete messages for roomId: {} - {}", roomId, e.getMessage());
                throw new ChatException("메시지 삭제 중 오류가 발생했습니다: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
            
            // 3. 마지막으로 채팅방 삭제
            chatRoomRepository.delete(chatRoom);
            log.info("[deleteRoom] Successfully deleted chat room: {}", roomId);
            
            // 참여자들에게 WebSocket 알림 전송
            for (Long participantId : participantIds) {
                try {
                    Map<String, Object> deleteNotification = Map.of(
                        "type", "ROOM_DELETED",
                        "roomId", roomId,
                        "message", "채팅방이 삭제되었습니다."
                    );
                    
                    String destination = "/queue/chat-notifications";
                    messagingTemplate.convertAndSendToUser(
                        participantId.toString(), 
                        destination, 
                        deleteNotification
                    );
                    log.info("[deleteRoom] Sent room deletion notification to user: {}", participantId);
                    
                    // 대안적 메시지 전송도 시도
                    String alternativeDestination = "/topic/chat-event-" + participantId;
                    messagingTemplate.convertAndSend(alternativeDestination, deleteNotification);
                    log.info("[deleteRoom] Sent alternative room deletion notification to: {}", alternativeDestination);
                    
                } catch (Exception e) {
                    log.warn("[deleteRoom] Failed to send deletion notification to user {}: {}", participantId, e.getMessage());
                    // 알림 전송 실패는 전체 삭제 과정을 실패시키지 않음
                }
            }
            
            log.info("[deleteRoom] Successfully completed deletion process for roomId: {} (including read statuses)", roomId);
            
        } catch (ChatException e) {
            log.error("[deleteRoom] ChatException during deletion of roomId {}: {}", roomId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[deleteRoom] Unexpected error during deletion of roomId {}: {}", roomId, e.getMessage(), e);
            throw new ChatException("채팅방 삭제 중 오류가 발생했습니다: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public void markMessageAsRead(String messageId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found with id: " + userIdString, HttpStatus.NOT_FOUND));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ChatException("Message not found: " + messageId, HttpStatus.NOT_FOUND));

        if (message.getReadByUsers() == null) {
            message.setReadByUsers(new HashSet<>());
        }

        if (!message.getReadByUsers().contains(user.getId())) {
            message.getReadByUsers().add(user.getId());
            messageRepository.save(message);
            log.info("Marked message {} as read for user {}", messageId, user.getId());
            
            // 읽음 상태 변경 알림 전송
            try {
                notificationService.sendMessageReadStatusUpdate(messageId, message.getChatRoom().getId(), user.getId());
            } catch (Exception e) {
                log.error("Failed to send read status update for messageId {}: {}", messageId, e.getMessage(), e);
            }
        }
    }
    
    @Override
    @Transactional
    public void addUserToRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        
        if (!chatRoom.getParticipants().contains(user)) {
            chatRoom.getParticipants().add(user);
            chatRoomRepository.save(chatRoom);
            log.info("Added user {} to room {}", user.getId(), roomId);
        } else {
            log.info("User {} is already in room {}", user.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public void removeUserFromRoom(String roomId, String userIdString) {
        Long userIdToRemove = Long.parseLong(userIdString);
        User userToRemove = userRepository.findById(userIdToRemove)
                .orElseThrow(() -> new RuntimeException("User to remove not found with id: " + userIdToRemove));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));

        if (chatRoom.getParticipants().remove(userToRemove)) {
            chatRoomRepository.save(chatRoom);
            log.info("Removed user {} from room {}", userToRemove.getId(), roomId);
        } else {
            log.warn("User {} was not a participant in room {}. No action taken.", userToRemove.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public void markAllMessagesInRoomAsRead(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found with id: " + userIdString, HttpStatus.NOT_FOUND));

        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("Chat room not found with id: " + roomId, HttpStatus.NOT_FOUND));

        if (chatRoom.getParticipants().stream().noneMatch(p -> p.getId().equals(userId))) {
            log.warn("User {} attempted to mark messages as read in room {} but is not a participant.", userIdString, roomId);
            throw new ChatException("User " + userIdString + " is not a participant in chat room " + roomId + ".", HttpStatus.FORBIDDEN);
        }

        List<Message> allMessagesInRoom = messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId, Pageable.unpaged()).getContent();
        
        List<Message> messagesToUpdate = new ArrayList<>();
        List<String> readMessageIds = new ArrayList<>();
        
        for (Message message : allMessagesInRoom) {
            if (message.getReadByUsers() == null) {
                message.setReadByUsers(new HashSet<>());
            }
            if (message.getReadByUsers().add(userId)) {
                messagesToUpdate.add(message);
                readMessageIds.add(message.getId());
            }
        }

        if (!messagesToUpdate.isEmpty()) {
            try {
                messageRepository.saveAll(messagesToUpdate);
                log.info("Successfully marked {} messages in room {} as read for user {}.", messagesToUpdate.size(), roomId, userIdString);
                
                // 읽음 상태 변경 알림 전송
                for (String messageId : readMessageIds) {
                    try {
                        notificationService.sendMessageReadStatusUpdate(messageId, roomId, userId);
                    } catch (Exception e) {
                        log.error("Failed to send read status update for messageId {}: {}", messageId, e.getMessage(), e);
                    }
                }
                
            } catch (Exception e) {
                log.error("Error saving messages after marking them as read for room {} user {}: {}", roomId, userIdString, e.getMessage(), e);
                throw new ChatException("Failed to save updated message read statuses.", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            log.info("No new messages to mark as read in room {} for user {}.", roomId, userIdString);
        }
    }
    
    @Override
    @Transactional
    public ChatRoomDto updateRoom(String roomId, String name, ChatRoomType type) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        
        chatRoom.setName(name);
        chatRoom.setType(type);
        ChatRoom updatedRoom = chatRoomRepository.save(chatRoom);
        return ChatRoomDto.fromEntity(updatedRoom, updatedRoom.getCreator(), messageRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRoomDto findOneOnOneChatRoom(Long userId1, Long userId2) {
        log.info("[findOneOnOneChatRoom] Looking for existing 1:1 chat room between userId1: {} and userId2: {}", userId1, userId2);
        
        User user1 = userRepository.findById(userId1)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId1, HttpStatus.NOT_FOUND));
        User user2 = userRepository.findById(userId2)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId2, HttpStatus.NOT_FOUND));
        
        // 두 사용자가 모두 참여하는 1:1 채팅방 찾기
        List<ChatRoom> user1Rooms = chatRoomRepository.findByParticipantsContainingAndType(user1, ChatRoomType.ONE_ON_ONE);
        
        for (ChatRoom room : user1Rooms) {
            // 정확히 2명만 있고, 그 중 한 명이 user2인지 확인
            if (room.getParticipants().size() == 2 && room.getParticipants().contains(user2)) {
                log.info("[findOneOnOneChatRoom] Found existing 1:1 chat room: roomId={}, roomName='{}'", room.getId(), room.getName());
                return ChatRoomDto.fromEntity(room, user1, messageRepository);
            }
        }
        
        log.info("[findOneOnOneChatRoom] No existing 1:1 chat room found between userId1: {} and userId2: {}", userId1, userId2);
        return null;
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Long> getUsersWithOneOnOneChatRooms(Long userId) {
        log.info("[getUsersWithOneOnOneChatRooms] Finding all users with 1:1 chat rooms for userId: {}", userId);
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId, HttpStatus.NOT_FOUND));
        
        // 해당 사용자가 참여하는 모든 1:1 채팅방 조회
        List<ChatRoom> oneOnOneRooms = chatRoomRepository.findByParticipantsContainingAndType(user, ChatRoomType.ONE_ON_ONE);
        
        List<Long> otherUserIds = new ArrayList<>();
        for (ChatRoom room : oneOnOneRooms) {
            // 정확히 2명만 있는 1:1 채팅방에서 상대방 찾기
            if (room.getParticipants().size() == 2) {
                for (User participant : room.getParticipants()) {
                    if (!participant.getId().equals(userId)) {
                        otherUserIds.add(participant.getId());
                        break;
                    }
                }
            }
        }
        
        log.info("[getUsersWithOneOnOneChatRooms] Found {} users with existing 1:1 chat rooms for userId: {}", otherUserIds.size(), userId);
        return otherUserIds;
    }
} 