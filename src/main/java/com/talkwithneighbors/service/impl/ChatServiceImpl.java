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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
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

        Message savedMessage = messageRepository.save(message);
        
        // 메시지 브로드캐스트
        messagingTemplate.convertAndSend("/topic/chat/room/" + room.getId(), messageDto);
        
        return savedMessage;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getMessages(String roomId, int page, int size) {
        return messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId, 
                org.springframework.data.domain.PageRequest.of(page, size));
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
        // 랜덤 매칭 로직 구현
        return createRoom("Random Chat", user, ChatRoomType.GROUP, null);
    }

    @Override
    public void markMessageAsRead(String messageId, User user) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        message.getReadByUsers().add(user.getId());
        messageRepository.save(message);
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
} 