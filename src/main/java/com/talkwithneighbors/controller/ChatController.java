package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.RedisSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@RequireLogin
public class ChatController {

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final RedisSessionService redisSessionService;

    private User getCurrentUser(UserSession userSession) {
        if (userSession == null || userSession.getUserId() == null) {
            throw new RuntimeException("User not authenticated");
        }
        return userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/rooms")
    public ResponseEntity<ChatRoom> createRoom(
            @RequestBody CreateRoomRequest createRoomRequest,
            UserSession userSession) {
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.createRoom(createRoomRequest.getName(), user, createRoomRequest.getType(), createRoomRequest.getParticipantIds()));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoom>> getRooms(UserSession userSession) {
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.getRoomsByUser(user));
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoom> getRoom(@PathVariable String roomId, UserSession userSession) {
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.getRoom(roomId, user));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(@PathVariable String roomId, UserSession userSession) {
        User user = getCurrentUser(userSession);
        chatService.joinRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable String roomId, UserSession userSession) {
        User user = getCurrentUser(userSession);
        chatService.leaveRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<Message>> getMessages(
            @PathVariable String roomId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            UserSession userSession) {
        return ResponseEntity.ok(chatService.getMessages(roomId, page, size));
    }

    @PostMapping("/rooms/{roomId}/messages/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable String roomId,
            @RequestParam(name = "messageId") String messageId,
            UserSession userSession) {
        User user = getCurrentUser(userSession);
        chatService.markMessageAsRead(messageId, user);
        return ResponseEntity.ok().build();
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageDto message) {
        chatService.sendMessage(message);
    }

    @MessageMapping("/chat.join")
    public void addUser(
            @Payload ChatMessageDto message,
            SimpMessageHeaderAccessor headerAccessor) {
        headerAccessor.getSessionAttributes().put("userId", message.getSenderId());
    }

    @GetMapping("/rooms/search")
    public ResponseEntity<List<ChatRoom>> searchGroupRooms(
            @RequestParam(name = "keyword", required = false) String keyword,
            UserSession userSession) {
        return ResponseEntity.ok(chatService.searchGroupRooms(keyword));
    }
}