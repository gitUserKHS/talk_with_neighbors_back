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
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@RequireLogin
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final RedisSessionService redisSessionService;
    private final UserService userService;
    private final SessionValidationService sessionValidationService;
    private final SimpMessagingTemplate messagingTemplate;

    private User getCurrentUser(UserSession userSession) {
        log.debug("Getting current user from session: {}", userSession);
        
        if (userSession == null) {
            log.error("UserSession is null");
            throw new RuntimeException("User session is null - user is not authenticated");
        }
        
        if (userSession.getUserId() == null) {
            log.error("UserSession userId is null for session: {}", userSession);
            throw new RuntimeException("User ID is null in session - user is not properly authenticated");
        }
        
        return userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> {
                    log.error("User not found for ID: {}", userSession.getUserId());
                    return new RuntimeException("User not found with ID: " + userSession.getUserId());
                });
    }

    @PostMapping("/rooms")
    public ResponseEntity<ChatRoom> createRoom(
            @RequestBody CreateRoomRequest createRoomRequest,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.createRoom(
            createRoomRequest.getName(), 
            user, 
            createRoomRequest.getTypeEnum(),
            createRoomRequest.getParticipantIds()
        ));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoom>> getRooms(@RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.getRoomsByUser(user));
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoom> getRoom(
            @PathVariable String roomId,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.getRoom(roomId, user));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable String roomId,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        chatService.joinRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable String roomId,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        chatService.leaveRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<Message>> getMessages(
            @PathVariable String roomId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
        User user = getCurrentUser(userSession);
        return ResponseEntity.ok(chatService.getMessages(roomId, page, size));
    }

    @PostMapping("/rooms/{roomId}/messages/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable String roomId,
            @RequestParam(name = "messageId") String messageId,
            @RequestHeader("X-Session-Id") String sessionId) {
        UserSession userSession = sessionValidationService.validateSession(sessionId);
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