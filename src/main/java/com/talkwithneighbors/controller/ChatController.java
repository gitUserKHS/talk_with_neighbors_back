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
        ChatRoom chatRoom = chatService.createRoom(
            createRoomRequest.getName(), 
            user, 
            createRoomRequest.getTypeEnum(),
            createRoomRequest.getParticipantIds()
        );
        log.info("[ChatController] Calling fromEntity for new room: {}, currentUser ID: {}", chatRoom.getId(), (user != null ? user.getId() : "null"));
        Integer participantCount = chatService.getParticipantCount(chatRoom.getId());
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom, user, messageRepository, participantCount));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoomDto>> getRooms(HttpServletRequest request) {
        User user = getCurrentUser(request);
        List<ChatRoom> rooms = chatService.getRoomsByUser(user);
        List<ChatRoomDto> roomDtos = rooms.stream()
            .map(room -> {
                log.info("[ChatController] Calling fromEntity in getRooms for room: {}, currentUser ID: {}", room.getId(), (user != null ? user.getId() : "null"));
                Integer participantCount = chatService.getParticipantCount(room.getId());
                return ChatRoomDto.fromEntity(room, user, messageRepository, participantCount);
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(roomDtos);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoomDto> getRoom(
            @PathVariable("roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoom chatRoom = chatService.getRoom(roomId, user);
        log.info("[ChatController] Calling fromEntity in getRoom for room: {}, currentUser ID: {}", chatRoom.getId(), (user != null ? user.getId() : "null"));
        Integer participantCount = chatService.getParticipantCount(chatRoom.getId());
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom, user, messageRepository, participantCount));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable("roomId") String roomId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.joinRoom(roomId, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable("roomId") String roomId,
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
            @PathVariable("roomId") String roomId,
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

    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessageDto> postMessage(
            @PathVariable("roomId") String roomId,
            @RequestBody ChatMessageDto chatMessageDto,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        // ensure path-based roomId and authenticated sender
        chatMessageDto.setRoomId(roomId);
        chatMessageDto.setSenderId(user.getId());
        Message saved = chatService.sendMessage(chatMessageDto);
        MessageDto dto = MessageDto.fromEntity(saved);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/rooms/{roomId}/messages/{messageId}/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable("roomId") String roomId,
            @PathVariable("messageId") String messageId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        chatService.markMessageAsRead(messageId, user);
        return ResponseEntity.ok().build();
    }

    // Legacy endpoint: read message via query param
    @PostMapping("/rooms/{roomId}/messages/read")
    public ResponseEntity<Void> markMessageAsReadParam(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "messageId", required = false) String messageId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (messageId == null) {
            chatService.markAllMessagesAsRead(roomId, user);
            return ResponseEntity.ok().build();
        }
        return markMessageAsRead(roomId, messageId, request);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageDto message, java.security.Principal principal) {
        log.debug("Received chat message: {} from STOMP principal: {}", message, (principal != null ? principal.getName() : "null"));
        
        // 현재 메시지를 보내는 사용자가 STOMP principal과 일치하는지 확인 (선택적 검증)
        if (principal != null) {
            // User senderDetails = userService.getUserById(message.getSenderId()); // ChatMessageDto에 senderId가 없을 수 있음
            User senderDetails = userService.getUserByUsername(principal.getName()); // Principal의 이름으로 사용자 조회
            if (senderDetails == null) { // 사용자를 찾을 수 없는 경우
                 log.warn("Sender details not found for STOMP principal name {}. Ignoring message.", principal.getName());
                 return; // 또는 적절한 예외 처리
            }
            // ChatMessageDto에 senderId를 설정하거나, senderUsername을 설정 (Principal 기반으로)
            message.setSenderId(senderDetails.getId()); 
            // message.setSenderUsername(senderDetails.getUsername()); // DTO에 필드 필요시

            log.info("Message sender {} (ID: {}) matches STOMP principal {}", senderDetails.getUsername(), senderDetails.getId(), principal.getName());
        } else {
            // principal이 null인 경우, 익명 사용자이거나 인증 설정 문제일 수 있음
            // 이 경우 메시지 발신자 정보를 어떻게 처리할지 정책 결정 필요
            // 예: 익명 사용자의 메시지를 허용하지 않거나, 기본 발신자 정보 사용
            log.warn("STOMP principal is null. Message might be from an unauthenticated user or due to configuration issues. SenderId in DTO: {}", message.getSenderId());
            // return; // 인증되지 않은 사용자의 메시지를 거부하는 경우
        }


        Message savedMessage = chatService.sendMessage(message);

        if (savedMessage != null && savedMessage.getChatRoom() != null) {
            String roomId = savedMessage.getChatRoom().getId();
            // List<User> participants = chatService.getParticipants(roomId); // 개별 발송이 아니므로 이제 필요 없음
            log.info("Broadcasting message to room {}", roomId);

            MessageDto messageDto = MessageDto.fromEntity(savedMessage);

            // 해당 채팅방의 토픽으로 메시지 발행
            String destination = "/topic/chat/room/" + roomId;
            try {
                messagingTemplate.convertAndSend(destination, messageDto);
                log.info("Successfully broadcasted message to {} for room {}: {}", destination, roomId, messageDto.getId());
            } catch (Exception e) {
                log.error("Error broadcasting message to {}: {}", destination, e.getMessage(), e);
            }
        } else {
            log.error("Failed to broadcast message: savedMessage or room is null. Original message: {}", message);
        }
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
            .map(room -> {
                log.info("[ChatController] Calling fromEntity in searchGroupRooms for room: {}, currentUser ID: {}", room.getId(), (user != null ? user.getId() : "null"));
                Integer participantCount = chatService.getParticipantCount(room.getId());
                return ChatRoomDto.fromEntity(room, user, messageRepository, participantCount);
            })
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
            .map(room -> {
                log.info("[ChatController] Calling fromEntity in searchRooms for room: {}, currentUser ID: {}", room.getId(), (user != null ? user.getId() : "null"));
                Integer participantCount = chatService.getParticipantCount(room.getId());
                return ChatRoomDto.fromEntity(room, user, messageRepository, participantCount);
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(roomDtos);
    }
    
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<Map<String, Object>> deleteRoom(
            @PathVariable("roomId") String roomId,
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

    // 1:1 채팅룸 생성 또는 조회 엔드포인트 추가
    @PostMapping("/rooms/one-to-one/{otherUserId}")
    public ResponseEntity<ChatRoomDto> oneToOneRoom(
            @PathVariable Long otherUserId,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        User otherUser = userService.getUserById(otherUserId);
        ChatRoom chatRoom = chatService.findOrCreateOneToOneRoom(user, otherUser);
        log.info("[ChatController] Calling fromEntity in oneToOneRoom for room: {}, currentUser ID: {}", chatRoom.getId(), (user != null ? user.getId() : "null"));
        Integer participantCount = chatService.getParticipantCount(chatRoom.getId());
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom, user, messageRepository, participantCount));
    }

    // 랜덤 매칭 채팅룸 생성 엔드포인트 추가
    @PostMapping("/rooms/random-match")
    public ResponseEntity<ChatRoomDto> randomMatchRoom(HttpServletRequest request) {
        User user = getCurrentUser(request);
        ChatRoom chatRoom = chatService.createRandomMatchingRoom(user);
        log.info("[ChatController] Calling fromEntity in randomMatchRoom for room: {}, currentUser ID: {}", chatRoom.getId(), (user != null ? user.getId() : "null"));
        Integer participantCount = chatService.getParticipantCount(chatRoom.getId());
        return ResponseEntity.ok(ChatRoomDto.fromEntity(chatRoom, user, messageRepository, participantCount));
    }
}