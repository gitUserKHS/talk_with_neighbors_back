package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional
    public ChatRoom createRoom(String name, User creator, ChatRoomType type, List<Long> participantIds) {
        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setName(name);
        chatRoom.setType(type);
        chatRoom.setCreator(creator);
        chatRoom.getParticipants().add(creator);

        if (participantIds != null && !participantIds.isEmpty()) {
            List<User> participants = userRepository.findAllById(participantIds);
            chatRoom.getParticipants().addAll(participants);
        }

        return chatRoomRepository.save(chatRoom);
    }

    @Override
    public List<ChatRoom> getRoomsByUser(User user) {
        return chatRoomRepository.findByParticipantsContaining(user);
    }

    @Override
    public ChatRoom getRoom(String roomId, User user) {
        return chatRoomRepository.findByIdAndParticipantsContaining(roomId, user)
                .orElseThrow(() -> new RuntimeException("Chat room not found or user not a participant"));
    }

    @Override
    @Transactional
    public void joinRoom(String roomId, User user) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        chatRoom.getParticipants().add(user);
        chatRoomRepository.save(chatRoom);
    }

    @Override
    @Transactional
    public void leaveRoom(String roomId, User user) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        chatRoom.getParticipants().remove(user);
        chatRoomRepository.save(chatRoom);
    }

    @Override
    public Message sendMessage(ChatMessageDto messageDto) {
        log.debug("Preparing to save message: {}", messageDto.getContent());
        ChatRoom room = chatRoomRepository.findById(messageDto.getRoomId())
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        User sender = userRepository.findById(messageDto.getSenderId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent(messageDto.getContent());
        message.setType(messageDto.getType());
        message.setCreatedAt(LocalDateTime.now());
        message.getReadByUsers().add(sender.getId());

        Message savedMessage = messageRepository.saveAndFlush(message);
        log.info("Saved message with id {} in room {}", savedMessage.getId(), room.getId());

        // 메시지 브로드캐스트 (ChatController에서 처리하므로 주석 처리 또는 삭제)
        // messagingTemplate.convertAndSend("/topic/chat/room/" + room.getId(), messageDto);

        return savedMessage;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getMessages(String roomId, int page, int size) {
        List<Message> messages = messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId,
                org.springframework.data.domain.PageRequest.of(page, size));
        // initialize sender and readByUsers to avoid LazyInitializationException
        messages.forEach(msg -> {
            msg.getSender().getId();
            msg.getReadByUsers().size();
        });
        return messages;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatRoom> getRooms(User user) {
        return chatRoomRepository.findByParticipantsContaining(user);
    }

    @Override
    public ChatRoom findOrCreateOneToOneRoom(User user1, User user2) {
        return chatRoomRepository.findByParticipantsContainingAndParticipantsContaining(user1, user2)
                .stream()
                .filter(room -> room.getParticipants().size() == 2)
                .findFirst()
                .orElseGet(() -> createRoom("1:1 Chat", user1, ChatRoomType.ONE_ON_ONE, List.of(user2.getId())));
    }

    @Override
    public ChatRoom createRandomMatchingRoom(User user) {
        // TODO: 랜덤 매칭 로직 구현 (예: 대기 중인 사용자 찾기 또는 새 방 생성)
        // 임시로 새 그룹 채팅방 생성
        String roomName = "Random Match Room - " + UUID.randomUUID().toString().substring(0, 8);
        return createRoom(roomName, user, ChatRoomType.GROUP, new ArrayList<>());
    }

    @Override
    @Transactional
    public void markMessageAsRead(String messageId, User user) {
        if (user == null || user.getId() == null) {
            log.warn("[ChatService] markMessageAsRead: User or User ID is null. Cannot mark message as read.");
            return;
        }
        log.info("[ChatService] markMessageAsRead: Attempting to mark messageId: {} as read for userId: {}", messageId, user.getId());
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> {
                    log.error("[ChatService] markMessageAsRead: Message not found with id: {}", messageId);
                    return new RuntimeException("Message not found: " + messageId);
                });
        
        log.debug("[ChatService] markMessageAsRead: Message {} found. Current readByUsers: {}", messageId, message.getReadByUsers());
        boolean alreadyRead = message.getReadByUsers().contains(user.getId());
        if (alreadyRead) {
            log.info("[ChatService] markMessageAsRead: Message {} already marked as read by userId: {}", messageId, user.getId());
        } else {
            message.getReadByUsers().add(user.getId());
            messageRepository.save(message);
            log.info("[ChatService] markMessageAsRead: Successfully marked message {} as read for userId: {}. Updated readByUsers: {}", messageId, user.getId(), message.getReadByUsers());
        }
    }

    @Override
    @Transactional
    public void markAllMessagesAsRead(String roomId, User user) {
        if (user == null || user.getId() == null) {
            log.warn("[ChatService] markAllMessagesAsRead: User or User ID is null. Cannot mark messages as read for room: {}", roomId);
            return;
        }
        log.info("[ChatService] markAllMessagesAsRead: Attempting to mark all messages in roomId: {} as read for userId: {}", roomId, user.getId());
        List<Message> unreadMessages = messageRepository.findUnreadMessages(roomId, user.getId());
        
        if (unreadMessages.isEmpty()) {
            log.info("[ChatService] markAllMessagesAsRead: No unread messages found for userId: {} in roomId: {}", user.getId(), roomId);
            return;
        }

        log.info("[ChatService] markAllMessagesAsRead: Found {} unread messages for userId: {} in roomId: {}. Marking them as read.", unreadMessages.size(), user.getId(), roomId);
        for (Message msg : unreadMessages) {
            log.debug("[ChatService] markAllMessagesAsRead: Marking messageId: {} for userId: {}. Current readByUsers: {}", msg.getId(), user.getId(), msg.getReadByUsers());
            msg.getReadByUsers().add(user.getId());
        }
        messageRepository.saveAll(unreadMessages);
        log.info("[ChatService] markAllMessagesAsRead: Successfully marked {} messages as read for userId: {} in roomId: {}", unreadMessages.size(), user.getId(), roomId);
    }

    @Override
    public List<ChatRoom> searchGroupRooms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return chatRoomRepository.findByType(ChatRoomType.GROUP);
        }

        String trimmedKeyword = keyword.trim().toLowerCase();
        return chatRoomRepository.findByTypeAndNameContainingIgnoreCaseOrTypeAndIdContainingIgnoreCase(
            ChatRoomType.GROUP, trimmedKeyword, ChatRoomType.GROUP, trimmedKeyword);
    }

    @Override
    @Transactional
    public boolean deleteRoom(String roomId, User user) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        // 방장 권한 확인
        if (!chatRoom.getCreator().getId().equals(user.getId())) {
            throw new RuntimeException("Only the creator can delete the room");
        }

        // 방에 있는 모든 메시지 삭제
        messageRepository.deleteByChatRoomId(roomId);

        // 채팅방 삭제
        chatRoomRepository.delete(chatRoom);

        return true;
    }

    @Override
    public List<ChatRoom> searchRooms(String keyword, ChatRoomType type) {
        // 키워드가 없으면 타입에 맞는 모든 채팅방 반환
        if (keyword == null || keyword.trim().isEmpty()) {
            if (type == null) {
                return chatRoomRepository.findAll();
            } else {
                return chatRoomRepository.findByType(type);
            }
        }

        String trimmedKeyword = keyword.trim().toLowerCase();

        // 타입이 지정된 경우 타입에 맞는 채팅방만 검색
        if (type != null) {
            return chatRoomRepository.findByTypeAndNameContainingIgnoreCaseOrTypeAndIdContainingIgnoreCase(
                type, trimmedKeyword, type, trimmedKeyword);
        }

        // 타입이 지정되지 않은 경우 모든 채팅방 검색
        return chatRoomRepository.findByNameContainingIgnoreCaseOrIdContainingIgnoreCase(
            trimmedKeyword, trimmedKeyword);
    }

    @Override
    public List<User> getParticipants(String roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found with id: " + roomId));
        return new ArrayList<>(room.getParticipants());
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getParticipantCount(String roomId) {
        // chatRoomRepository에 추가한 count 쿼리를 직접 사용합니다.
        Integer count = chatRoomRepository.getParticipantCount(roomId);
        return count != null ? count : 0;
    }
}