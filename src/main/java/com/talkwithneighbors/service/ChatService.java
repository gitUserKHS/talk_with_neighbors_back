package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.chat.CreateRoomRequestDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ChatRoomDto> getUserChatRooms(String userId) {
        return chatRoomRepository.findByParticipantsId(Long.parseLong(userId))
                .stream()
                .map(ChatRoomDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChatRoomDto getRoom(String roomId, String userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!chatRoom.getParticipants().stream().anyMatch(user -> user.getId().toString().equals(userId))) {
            throw new ChatException("채팅방에 접근 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }

        return ChatRoomDto.fromEntity(chatRoom);
    }

    @Transactional
    public ChatRoomDto createRoom(CreateRoomRequestDto request, String userId) {
        List<User> participants = request.getParticipants().stream()
                .map(id -> userRepository.findById(Long.parseLong(id))
                        .orElseThrow(() -> new ChatException("사용자를 찾을 수 없습니다: " + id, HttpStatus.NOT_FOUND)))
                .collect(Collectors.toList());

        // 자신도 참여자 목록에 추가
        User creator = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new ChatException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        participants.add(creator);

        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setParticipants(participants.stream().collect(Collectors.toSet()));

        return ChatRoomDto.fromEntity(chatRoomRepository.save(chatRoom));
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(String roomId, String userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!chatRoom.getParticipants().stream().anyMatch(user -> user.getId().toString().equals(userId))) {
            throw new ChatException("채팅방에 접근 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }

        return messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId)
                .stream()
                .map(MessageDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public MessageDto sendMessage(String roomId, String content, String userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        User sender = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new ChatException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!chatRoom.getParticipants().contains(sender)) {
            throw new ChatException("채팅방에 접근 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }

        Message message = new Message();
        message.setChatRoom(chatRoom);
        message.setSender(sender);
        message.setContent(content);

        // 채팅방의 마지막 메시지 정보 업데이트
        chatRoom.setLastMessage(content);
        chatRoom.setLastMessageTime(message.getCreatedAt());

        return MessageDto.fromEntity(messageRepository.save(message));
    }

    @Transactional
    public void deleteRoom(String roomId, String userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!chatRoom.getParticipants().stream().anyMatch(user -> user.getId().toString().equals(userId))) {
            throw new ChatException("채팅방에 접근 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }

        messageRepository.deleteByChatRoomId(roomId);
        chatRoomRepository.delete(chatRoom);
    }

    @Transactional
    public void leaveRoom(String roomId, String userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new ChatException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!chatRoom.getParticipants().remove(user)) {
            throw new ChatException("채팅방에 속해있지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        // 참여자가 없으면 채팅방 삭제
        if (chatRoom.getParticipants().isEmpty()) {
            messageRepository.deleteByChatRoomId(roomId);
            chatRoomRepository.delete(chatRoom);
        } else {
            chatRoomRepository.save(chatRoom);
        }
    }
} 