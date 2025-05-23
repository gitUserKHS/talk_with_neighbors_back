package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequireLogin
@Slf4j
public class ChatController extends BaseController {

    private final ChatService chatService;
    private final RedisSessionService redisSessionService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageRepository messageRepository;

    @Autowired
    public ChatController(ChatService chatService, 
                         RedisSessionService redisSessionService, 
                         UserService userService, 
                         SimpMessagingTemplate messagingTemplate,
                         MessageRepository messageRepository) {
        this.chatService = chatService;
        this.redisSessionService = redisSessionService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.messageRepository = messageRepository;
    }

    @PostMapping("/rooms")
    public ResponseEntity<ChatRoomDto> createRoom(
            @RequestBody CreateRoomRequest createRoomRequest,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoomDto roomDto = chatService.createRoom(
            createRoomRequest.getName(),
            createRoomRequest.getTypeEnum(),
            user.getId().toString(),
            createRoomRequest.getParticipantIds()
        );
        return ResponseEntity.ok(roomDto);
    }

    @GetMapping("/rooms")
    public ResponseEntity<Page<ChatRoomDto>> getRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatRoomDto> roomPage = chatService.getChatRoomsForUser(
            user.getId().toString(), pageable);
        return ResponseEntity.ok(roomPage);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoomDto> getRoom(
            @PathVariable String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoomDto dto = chatService.getRoomById(
            roomId, user.getId().toString());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.joinRoom(roomId, user.getId().toString());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoomDto roomDto = chatService.getRoomById(
            roomId, user.getId().toString());
        if (roomDto.getCreatorId() != null &&
            roomDto.getCreatorId().equals(user.getId().toString())) {
            chatService.deleteRoom(roomId);
        } else {
            chatService.leaveRoom(roomId, user.getId().toString());
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<Page<MessageDto>> getMessages(
            @PathVariable String roomId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        Pageable pageable = PageRequest.of(page, size);
        Page<MessageDto> messagesPage = chatService.getMessagesByRoomId(
            roomId, user.getId().toString(), pageable);
        return ResponseEntity.ok(messagesPage);
    }

    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessageDto> postMessage(
            @PathVariable String roomId,
            @RequestBody ChatMessageDto chatMessageDto,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        MessageDto savedDto = chatService.sendMessage(
            roomId, user.getId(), chatMessageDto.getContent());
        return ResponseEntity.ok(savedDto);
    }

    @PostMapping("/rooms/{roomId}/messages/{messageId}/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable String roomId,
            @PathVariable String messageId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.markMessageAsRead(
            messageId, user.getId().toString());
        return ResponseEntity.ok().build();
    }
}