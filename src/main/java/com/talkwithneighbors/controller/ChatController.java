package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.UserService;
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

    @Autowired
    public ChatController(ChatService chatService, 
                         RedisSessionService redisSessionService, 
                         UserService userService, 
                         SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.redisSessionService = redisSessionService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/rooms")
    public ResponseEntity<ChatRoomDto> createRoom(
            @RequestBody CreateRoomRequest createRoomRequest,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoom chatRoom = chatService.createRoom(
            createRoomRequest.getName(), 
            user, 
            createRoomRequest.getTypeEnum(),
            createRoomRequest.getParticipantIds()
        );
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoomDto>> getRooms(HttpServletRequest request) {
        User user = getCurrentUser(request);
        List<ChatRoom> rooms = chatService.getRoomsByUser(user);
        List<ChatRoomDto> roomDtos = rooms.stream()
            .map(ChatRoomDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(roomDtos);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoomDto> getRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoom chatRoom = chatService.getRoom(roomId, user);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.joinRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        
        // 채팅방 정보 가져오기
        ChatRoom chatRoom = chatService.getRoom(roomId, user);
        
        // 나가는 사용자가 방장(생성자)인지 확인
        if (chatRoom.getCreator() != null && chatRoom.getCreator().getId().equals(user.getId())) {
            log.info("방장이 나가므로 채팅방 {} 삭제", roomId);
            chatService.deleteRoom(roomId, user);
        } else {
            // 방장이 아닌 경우 일반적인 퇴장 처리
            chatService.leaveRoom(roomId, user);
        }
        
        return ResponseEntity.ok().build();
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<MessageDto>> getMessages(
            @PathVariable(name = "roomId") String roomId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        List<Message> messages = chatService.getMessages(roomId, page, size);
        List<MessageDto> messageDtos = messages.stream()
            .map(MessageDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(messageDtos);
    }

    @PostMapping("/rooms/{roomId}/messages/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable(name = "roomId") String roomId,
            @RequestParam(name = "messageId") String messageId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.markMessageAsRead(messageId, user);
        return ResponseEntity.ok().build();
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageDto message) {
        log.debug("Received chat message: {}", message);
        chatService.sendMessage(message);
    }

    @MessageMapping("/chat.join")
    public void addUser(@Payload ChatMessageDto message) {
        log.debug("User joined chat: {}", message.getSenderId());
        // 세션 ID 처리는 클라이언트에서 직접 수행하도록 변경
    }

    @GetMapping("/rooms/search")
    public ResponseEntity<List<ChatRoomDto>> searchGroupRooms(
            @RequestParam(name = "keyword", required = false) String keyword,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        List<ChatRoom> rooms = chatService.searchGroupRooms(keyword);
        List<ChatRoomDto> roomDtos = rooms.stream()
            .map(ChatRoomDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(roomDtos);
    }
    
    @GetMapping("/rooms/search/all")
    public ResponseEntity<List<ChatRoomDto>> searchRooms(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "type", required = false) String type,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        
        ChatRoomType roomType = null;
        if (type != null && !type.isEmpty()) {
            try {
                roomType = ChatRoomType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid room type: {}", type);
            }
        }
        
        List<ChatRoom> rooms = chatService.searchRooms(keyword, roomType);
        List<ChatRoomDto> roomDtos = rooms.stream()
            .map(ChatRoomDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(roomDtos);
    }
    
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<Map<String, Object>> deleteRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            boolean result = chatService.deleteRoom(roomId, user);
            response.put("success", result);
            response.put("message", "채팅방이 성공적으로 삭제되었습니다.");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
    }
}