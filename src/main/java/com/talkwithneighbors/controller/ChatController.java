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
import org.springframework.messaging.handler.annotation.Header;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import com.talkwithneighbors.exception.ChatException;

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
            createRoomRequest.getParticipantNicknames()
        );
        return ResponseEntity.ok(roomDto);
    }

    @GetMapping("/rooms")
    public ResponseEntity<Page<ChatRoomDto>> getRooms(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatRoomDto> roomPage = chatService.getChatRoomsForUser(
            user.getId().toString(), pageable);
        return ResponseEntity.ok(roomPage);
    }

    @GetMapping("/rooms/search/all")
    public ResponseEntity<Page<ChatRoomDto>> searchAllChatRooms(
            @RequestParam(name = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam(name = "type", required = false) String typeString,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoomType type = null;
        if (typeString != null && !typeString.isEmpty()) {
            try {
                type = ChatRoomType.valueOf(typeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid chat room type: {}", typeString);
                // 유효하지 않은 타입의 경우 null로 두어 전체 타입 검색 또는 예외 처리 가능
            }
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatRoomDto> roomPage = chatService.searchRooms(
                keyword, type, user.getId().toString(), pageable);
        return ResponseEntity.ok(roomPage);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoomDto> getRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoomDto dto = chatService.getRoomById(
            roomId, user.getId().toString());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.joinRoom(roomId, user.getId().toString());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable(name = "roomId") String roomId,
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
            @PathVariable(name = "roomId") String roomId,
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
            @PathVariable(name = "roomId") String roomId,
            @RequestBody ChatMessageDto chatMessageDto,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        MessageDto savedDto = chatService.sendMessage(
            roomId, user.getId(), chatMessageDto.getContent());
        return ResponseEntity.ok(savedDto);
    }

    @PostMapping("/rooms/{roomId}/messages/read")
    public ResponseEntity<Void> markAllMessagesInRoomAsRead(
            @PathVariable(name = "roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.markAllMessagesInRoomAsRead(
            roomId, user.getId().toString());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<Void> deleteRoom(@PathVariable(name = "roomId") String roomId, HttpServletRequest request) {
        log.info("[ChatController] deleteRoom request received for roomId: {}", roomId);
        
        try {
            User user = getCurrentUser(request);
            log.info("[ChatController] User {} attempting to delete roomId: {}", user.getId(), roomId);
            
            ChatRoomDto roomDto = chatService.getRoomById(roomId, user.getId().toString());

            if (roomDto == null || roomDto.getCreatorId() == null || !roomDto.getCreatorId().equals(user.getId().toString())) {
                log.warn("[ChatController] User {} does not have permission to delete roomId: {}", user.getId(), roomId);
                throw new ChatException("No permission to delete room.", HttpStatus.FORBIDDEN);
            }
            
            chatService.deleteRoom(roomId);
            log.info("[ChatController] Successfully deleted roomId: {} by user: {}", roomId, user.getId());
            return ResponseEntity.ok().build();
            
        } catch (ChatException e) {
            log.error("[ChatController] ChatException while deleting roomId {}: {}", roomId, e.getMessage());
            return ResponseEntity.status(e.getStatus()).build();
        } catch (Exception e) {
            log.error("[ChatController] Unexpected error while deleting roomId {}: {}", roomId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @MessageMapping("/chat.deleteRoom")
    public void deleteRoomViaWebSocket(@Payload Map<String, String> payload, 
                                      @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        try {
            String roomId = payload.get("roomId");
            String userIdString = (String) sessionAttributes.get("userId");
            
            log.info("[ChatController] WebSocket deleteRoom request: roomId={}, userId={}", roomId, userIdString);
            
            if (roomId == null || userIdString == null) {
                log.warn("[ChatController] Missing roomId or userId in WebSocket deleteRoom request");
                return;
            }
            
            ChatRoomDto roomDto = chatService.getRoomById(roomId, userIdString);
            
            if (roomDto == null || roomDto.getCreatorId() == null || !roomDto.getCreatorId().equals(userIdString)) {
                log.warn("[ChatController] User {} does not have permission to delete roomId: {} via WebSocket", userIdString, roomId);
                // 에러 알림을 해당 사용자에게 전송
                Map<String, Object> errorNotification = Map.of(
                    "type", "DELETE_ROOM_ERROR",
                    "roomId", roomId,
                    "message", "채팅방을 삭제할 권한이 없습니다."
                );
                messagingTemplate.convertAndSendToUser(userIdString, "/queue/chat-errors", errorNotification);
                return;
            }
            
            chatService.deleteRoom(roomId);
            log.info("[ChatController] Successfully deleted roomId: {} by user: {} via WebSocket", roomId, userIdString);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket deleteRoom: {}", e.getMessage(), e);
        }
    }

    @GetMapping("/rooms/{roomId}/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(@PathVariable(name = "roomId") String roomId, HttpServletRequest request) {
        log.info("[ChatController] getUnreadCount request received for roomId: {}", roomId);
        
        try {
            User user = getCurrentUser(request);
            long unreadCount = messageRepository.countUnreadMessages(roomId, user.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRoomId", roomId);
            response.put("unreadCount", unreadCount);
            
            log.info("[ChatController] Unread count for user {} in room {}: {}", user.getId(), roomId, unreadCount);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("[ChatController] Error getting unread count for roomId {}: {}", roomId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/unread-counts")
    public ResponseEntity<Map<String, Object>> getAllUnreadCounts(HttpServletRequest request) {
        log.info("[ChatController] getAllUnreadCounts request received");
        
        try {
            User user = getCurrentUser(request);
            
            // 사용자가 참여한 모든 채팅방의 읽지 않은 메시지 수 조회
            Page<ChatRoomDto> chatRooms = chatService.getChatRoomsForUser(user.getId().toString(), Pageable.unpaged());
            
            Map<String, Long> unreadCounts = new HashMap<>();
            for (ChatRoomDto room : chatRooms.getContent()) {
                long unreadCount = messageRepository.countUnreadMessages(room.getId(), user.getId());
                unreadCounts.put(room.getId(), unreadCount);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("unreadCounts", unreadCounts);
            
            log.info("[ChatController] Retrieved unread counts for user {}: {} rooms", user.getId(), unreadCounts.size());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("[ChatController] Error getting all unread counts: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @MessageMapping("/chat.sendMessage")
    public void sendMessageViaWebSocket(@Payload ChatMessageDto chatMessageDto, 
                                       @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        log.info("=== [ChatController] WebSocket sendMessage START ===");
        log.info("[ChatController] Received payload: {}", chatMessageDto);
        log.info("[ChatController] Session attributes: {}", sessionAttributes);
        
        try {
            String roomId = chatMessageDto.getRoomId();
            String userIdString = (String) sessionAttributes.get("userId");
            String content = chatMessageDto.getContent();
            
            log.info("[ChatController] WebSocket sendMessage request: roomId={}, userId={}, content='{}'", 
                     roomId, userIdString, content);
            
            if (roomId == null || userIdString == null || content == null || content.trim().isEmpty()) {
                log.warn("[ChatController] Invalid sendMessage request: roomId={}, userId={}, content='{}'", 
                         roomId, userIdString, content);
                return;
            }
            
            log.info("[ChatController] Validation passed, attempting to send message...");
            
            Long userId = Long.parseLong(userIdString);
            MessageDto savedDto = chatService.sendMessage(roomId, userId, content.trim());
            
            log.info("[ChatController] Successfully sent message via WebSocket: messageId={}, roomId={}, senderId={}", 
                     savedDto.getId(), roomId, userId);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket sendMessage: {}", e.getMessage(), e);
            
            // 에러 알림을 발신자에게 전송
            String userIdString = (String) sessionAttributes.get("userId");
            if (userIdString != null) {
                Map<String, Object> errorNotification = Map.of(
                    "type", "MESSAGE_SEND_ERROR",
                    "message", "메시지 전송에 실패했습니다: " + e.getMessage()
                );
                messagingTemplate.convertAndSendToUser(userIdString, "/queue/message-errors", errorNotification);
            }
        }
        
        log.info("=== [ChatController] WebSocket sendMessage END ===");
    }

    @MessageMapping("/chat.markAsRead")
    public void markMessageAsReadViaWebSocket(@Payload Map<String, String> payload, 
                                             @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        try {
            String messageId = payload.get("messageId");
            String userIdString = (String) sessionAttributes.get("userId");
            
            log.info("[ChatController] WebSocket markAsRead request: messageId={}, userId={}", messageId, userIdString);
            
            if (messageId == null || userIdString == null) {
                log.warn("[ChatController] Invalid markAsRead request: messageId={}, userId={}", messageId, userIdString);
                return;
            }
            
            chatService.markMessageAsRead(messageId, userIdString);
            
            log.info("[ChatController] Successfully marked message as read via WebSocket: messageId={}, userId={}", 
                     messageId, userIdString);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket markAsRead: {}", e.getMessage(), e);
        }
    }

    @MessageMapping("/chat.markAllAsRead")
    public void markAllMessagesAsReadViaWebSocket(@Payload Map<String, String> payload, 
                                                 @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        try {
            String roomId = payload.get("roomId");
            String userIdString = (String) sessionAttributes.get("userId");
            
            log.info("[ChatController] WebSocket markAllAsRead request: roomId={}, userId={}", roomId, userIdString);
            
            if (roomId == null || userIdString == null) {
                log.warn("[ChatController] Invalid markAllAsRead request: roomId={}, userId={}", roomId, userIdString);
                return;
            }
            
            chatService.markAllMessagesInRoomAsRead(roomId, userIdString);
            
            log.info("[ChatController] Successfully marked all messages as read via WebSocket: roomId={}, userId={}", 
                     roomId, userIdString);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket markAllAsRead: {}", e.getMessage(), e);
        }
    }

    @MessageMapping("/chat.enterRoom")
    public void enterRoom(@Payload Map<String, String> payload, 
                         @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        try {
            String roomId = payload.get("roomId");
            String userIdString = (String) sessionAttributes.get("userId");
            
            log.info("[ChatController] WebSocket enterRoom request: roomId={}, userId={}", roomId, userIdString);
            
            if (roomId == null || userIdString == null) {
                log.warn("[ChatController] Invalid enterRoom request: roomId={}, userId={}", roomId, userIdString);
                return;
            }
            
            // 사용자가 채팅방 참여자인지 확인
            ChatRoomDto roomDto = chatService.getRoomById(roomId, userIdString);
            if (roomDto == null) {
                log.warn("[ChatController] User {} does not have access to room: {}", userIdString, roomId);
                return;
            }
            
            // Redis에 사용자 현재 채팅방 상태 기록
            redisSessionService.setUserCurrentRoom(userIdString, roomId);
            
            // 채팅방의 모든 메시지를 읽음 처리
            chatService.markAllMessagesInRoomAsRead(roomId, userIdString);
            
            log.info("[ChatController] User {} successfully entered room: {}", userIdString, roomId);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket enterRoom: {}", e.getMessage(), e);
        }
    }

    @MessageMapping("/chat.leaveRoom")
    public void leaveRoom(@Payload Map<String, String> payload, 
                         @Header("simpSessionAttributes") Map<String, Object> sessionAttributes) {
        try {
            String roomId = payload.get("roomId");
            String userIdString = (String) sessionAttributes.get("userId");
            
            log.info("[ChatController] WebSocket leaveRoom request: roomId={}, userId={}", roomId, userIdString);
            
            if (userIdString == null) {
                log.warn("[ChatController] Invalid leaveRoom request: userId is null");
                return;
            }
            
            // Redis에서 사용자 현재 채팅방 상태 제거
            redisSessionService.clearUserCurrentRoom(userIdString);
            
            log.info("[ChatController] User {} successfully left room: {}", userIdString, roomId);
            
        } catch (Exception e) {
            log.error("[ChatController] Error in WebSocket leaveRoom: {}", e.getMessage(), e);
        }
    }
}