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
import java.util.HashSet;
import java.util.List;
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

    @Override
    @Transactional
    public ChatRoomDto createRoom(String name, ChatRoomType type, String creatorIdString, List<Long> participantIds) {
        Long creatorId = Long.parseLong(creatorIdString);
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new RuntimeException("Creator not found with id: " + creatorId));

        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setName(name);
        chatRoom.setType(type);
        chatRoom.setCreator(creator);
        chatRoom.getParticipants().add(creator);

        if (participantIds != null && !participantIds.isEmpty()) {
            List<User> participants = userRepository.findAllById(participantIds);
            chatRoom.getParticipants().addAll(participants);
        }
        ChatRoom savedChatRoom = chatRoomRepository.save(chatRoom);
        return ChatRoomDto.fromEntity(savedChatRoom, creator, messageRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> getChatRoomsForUser(String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        Page<ChatRoom> roomsPage = chatRoomRepository.findByParticipantsContaining(user, pageable);
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
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("User not found: " + senderId));

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        message.getReadByUsers().add(sender.getId());

        Message savedMessage = messageRepository.save(message);
        
        room.setLastMessage(savedMessage.getContent());
        room.setLastMessageTime(savedMessage.getCreatedAt());
        chatRoomRepository.save(room);

        MessageDto messageDto = MessageDto.fromEntity(savedMessage, sender.getId());

        messagingTemplate.convertAndSend("/topic/chat/room/" + roomId, messageDto);
        log.info("Sent message id {} to /topic/chat/room/{}", savedMessage.getId(), roomId);
        return messageDto;
    }

    @Override
    @Transactional(readOnly = true)
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
        messagesPage.getContent().forEach(msg -> {
            if (!msg.getReadByUsers().contains(user.getId())) {
                msg.getReadByUsers().add(user.getId());
                messagesToUpdate.add(msg);
            }
        });
        if (!messagesToUpdate.isEmpty()) {
            messageRepository.saveAll(messagesToUpdate);
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
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        messageRepository.deleteByChatRoomId(roomId);
        chatRoomRepository.delete(chatRoom);
        log.info("Deleted chat room with id: {}", roomId);
    }

    @Override
    @Transactional
    public void markMessageAsRead(String messageId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));

        if (!message.getReadByUsers().contains(user.getId())) {
        message.getReadByUsers().add(user.getId());
        messageRepository.save(message);
            log.info("Marked message {} as read for user {}", messageId, user.getId());
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
    public ChatRoomDto updateRoom(String roomId, String name, ChatRoomType type) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        
        chatRoom.setName(name);
        chatRoom.setType(type);
        ChatRoom updatedRoom = chatRoomRepository.save(chatRoom);
        return ChatRoomDto.fromEntity(updatedRoom, updatedRoom.getCreator(), messageRepository);
    }
} 