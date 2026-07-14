package com.talkwithneighbors.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.service.NotificationService;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final RedisSessionService redisSessionService;
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;
    
    @Override
    public void sendNewMessageNotification(Message message, ChatRoom chatRoom, Long senderId) {
        log.info("=== [NotificationService] sendNewMessageNotification START ===");
        log.info("[NotificationService] MessageId: {}, ChatRoomId: {}, SenderId: {}", 
                 message.getId(), chatRoom.getId(), senderId);
        log.info("[NotificationService] ChatRoom participants count: {}", chatRoom.getParticipants().size());
        
        // 채팅방의 모든 참여자 처리
        for (User participant : chatRoom.getParticipants()) {
            Long participantId = participant.getId();
            boolean isUserOnline = redisSessionService.isUserOnline(participantId.toString());
            boolean isUserInRoom = redisSessionService.isUserInRoom(participantId.toString(), chatRoom.getId());
            
            log.info("[NotificationService] Processing participant: {}, Online: {}, InRoom: {}", 
                     participantId, isUserOnline, isUserInRoom);
            
            try {
                // 1. 모든 참여자에게 채팅방 목록 순서 업데이트 (온라인/오프라인 관계없이)
                // 사용자의 요청에 따라 새 메시지가 왔다는 이유로 CHAT_ROOM_LIST_UPDATE 알림은 보내지 않도록 주석 처리합니다.
                // handleChatRoomListUpdate(participantId, chatRoom.getId(), isUserOnline);
                
                // 2. 발신자가 아니면서 채팅방 밖에 있는 사용자에게만 새 메시지 알림 처리
                if (!participantId.equals(senderId) && !isUserInRoom) {
                    handleNewMessageNotification(participantId, message, chatRoom, isUserOnline);
                    
                    // 3. 읽지 않은 메시지 수 업데이트 처리
                    handleUnreadCountUpdate(participantId, chatRoom.getId(), isUserOnline);
                } else if (participantId.equals(senderId)) {
                    log.info("[NotificationService] User {} is sender, only sending chat room list update", participantId);
                } else {
                    log.info("[NotificationService] User {} is currently in room {}, only sending chat room list update", 
                             participantId, chatRoom.getId());
                }
                    
            } catch (Exception e) {
                log.error("[NotificationService] ❌ Failed to send updates to user {}: {}", participantId, e.getMessage(), e);
            }
        }
        
        log.info("=== [NotificationService] sendNewMessageNotification END ===");
    }
    
    /**
     * 채팅방 목록 업데이트 처리 (온라인/오프라인 대응)
     */
    private void handleChatRoomListUpdate(Long userId, String chatRoomId, boolean isUserOnline) {
        Map<String, Object> updateData = new HashMap<>();
        updateData.put("chatRoomId", chatRoomId);
        updateData.put("action", "UPDATE_ORDER");
        updateData.put("sortBy", "lastMessageTime");
        updateData.put("sortOrder", "desc");
        updateData.put("timestamp", System.currentTimeMillis());
        
        if (isUserOnline) {
            // 온라인 사용자에게는 즉시 전송
            sendChatRoomListUpdate(userId, chatRoomId);
        } else {
            // 오프라인 사용자에게는 저장
            try {
                String dataJson = objectMapper.writeValueAsString(updateData);
                offlineNotificationService.saveOfflineNotification(
                    userId,
                    OfflineNotification.NotificationType.CHAT_ROOM_LIST_UPDATE,
                    dataJson,
                    "채팅방 목록이 업데이트되었습니다.",
                    null,
                    3 // 낮은 우선순위
                );
                log.info("[NotificationService] Saved offline chat room list update for user: {}", userId);
            } catch (JsonProcessingException e) {
                log.error("[NotificationService] Failed to save offline chat room list update: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * 새 메시지 알림 처리 (온라인/오프라인 대응)
     */
    private void handleNewMessageNotification(Long userId, Message message, ChatRoom chatRoom, boolean isUserOnline) {
        log.info("=== [NotificationService] handleNewMessageNotification START ===");
        log.info("[NotificationService] userId: {}, isUserOnline: {}, messageId: {}", userId, isUserOnline, message.getId());
        
        Map<String, Object> notificationData = new HashMap<>();
        notificationData.put("chatRoomId", chatRoom.getId());
        notificationData.put("chatRoomName", chatRoom.getName());
        notificationData.put("senderName", message.getSender().getUsername());
        notificationData.put("messagePreview", truncateMessage(message.getContent()));
        notificationData.put("messageId", message.getId());
        notificationData.put("createdAt", message.getCreatedAt().toString());
        
        String notificationMessage = String.format("%s님이 메시지를 보냈습니다: %s", 
                                                  message.getSender().getUsername(), 
                                                  truncateMessage(message.getContent()));

        OfflineNotification inboxNotification = null;
        try {
            inboxNotification = offlineNotificationService.saveOfflineNotification(
                    userId,
                    OfflineNotification.NotificationType.NEW_MESSAGE,
                    objectMapper.writeValueAsString(notificationData),
                    notificationMessage,
                    "/chat/" + chatRoom.getId(),
                    5
            );
        } catch (Exception exception) {
            log.error("[NotificationService] Failed to persist notification inbox item: {}", exception.getMessage(), exception);
        }
        
        if (isUserOnline) {
            // 온라인 사용자에게는 즉시 전송
            log.info("[NotificationService] Sending notification to user {} (outside room)", userId);
            
            WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "NEW_MESSAGE",
                notificationData,
                notificationMessage,
                "/chat/" + chatRoom.getId()
            );
            
            String destination = "/queue/chat-notifications";
            messagingTemplate.convertAndSendToUser(
                userId.toString(), 
                destination, 
                notification
            );
            if (inboxNotification != null) {
                offlineNotificationService.markAsDelivered(inboxNotification.getId());
            }
            
            log.info("[NotificationService] ✅ Successfully sent notification to user {}", userId);
        } else {
            // 오프라인 사용자에게는 저장
            log.info("[NotificationService] 🔄 User {} is OFFLINE. Attempting to save offline notification...", userId);
            try {
                String dataJson = objectMapper.writeValueAsString(notificationData);
                offlineNotificationService.saveOfflineNotification(
                    userId,
                    OfflineNotification.NotificationType.NEW_MESSAGE,
                    dataJson,
                    notificationMessage,
                    "/chat/" + chatRoom.getId(),
                    5 // 중간 우선순위
                );
                log.info("[NotificationService] ✅ Successfully saved offline new message notification for user: {}", userId);
            } catch (JsonProcessingException e) {
                log.error("[NotificationService] ❌ JSON processing failed: {}", e.getMessage(), e);
            } catch (Exception e) {
                log.error("[NotificationService] ❌ Failed to save offline new message notification: {}", e.getMessage(), e);
            }
        }
        log.info("=== [NotificationService] handleNewMessageNotification END ===");
    }
    
    /**
     * 읽지 않은 메시지 수 업데이트 처리 (온라인/오프라인 대응)
     */
    private void handleUnreadCountUpdate(Long userId, String chatRoomId, boolean isUserOnline) {
        if (isUserOnline) {
            // 온라인 사용자에게는 즉시 업데이트
            updateUnreadCountForUser(chatRoomId, userId);
        } else {
            // 오프라인 사용자는 온라인 시 전체 읽지 않은 메시지 수를 다시 계산하므로 저장하지 않음
            log.debug("[NotificationService] Skipping unread count update for offline user: {}", userId);
        }
    }
    
    /**
     * 채팅방 목록 업데이트 알림을 전송합니다.
     * 새 메시지로 인해 채팅방이 목록 맨 위로 이동해야 한다는 알림을 보냅니다.
     * 
     * @param userId 사용자 ID
     * @param chatRoomId 업데이트된 채팅방 ID
     */
    private void sendChatRoomListUpdate(Long userId, String chatRoomId) {
        log.info("[NotificationService] Sending chat room list update for userId: {}, chatRoomId: {}", userId, chatRoomId);
        
        try {
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("chatRoomId", chatRoomId);
            updateData.put("action", "UPDATE_ORDER"); // 채팅방 순서 업데이트
            updateData.put("sortBy", "lastMessageTime"); // 최근 메시지 시간 순으로 정렬
            updateData.put("sortOrder", "desc"); // 내림차순 (최신 순)
            updateData.put("timestamp", System.currentTimeMillis()); // 업데이트 시간
            
            WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "CHAT_ROOM_LIST_UPDATE",
                updateData,
                "채팅방 목록이 업데이트되었습니다.",
                null
            );
            
            // 특정 사용자에게만 채팅방 목록 업데이트 전송
            String destination = "/queue/chat-updates";
            messagingTemplate.convertAndSendToUser(
                userId.toString(), 
                destination, 
                notification
            );
            
            log.info("[NotificationService] Sent chat room list update to user: {} for chatRoom: {}", 
                     userId, chatRoomId);
            
        } catch (Exception e) {
            log.error("[NotificationService] Failed to send chat room list update: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void sendMessageReadStatusUpdate(String messageId, String chatRoomId, Long readByUserId) {
        log.info("[NotificationService] Sending message read status update for messageId: {}, readByUserId: {}", 
                 messageId, readByUserId);
        
        try {
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("messageId", messageId);
            updateData.put("chatRoomId", chatRoomId);
            updateData.put("readByUserId", readByUserId);
            
            WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "MESSAGE_READ_STATUS_UPDATE",
                updateData
            );
            
            ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                    .orElseThrow(() -> new IllegalArgumentException("Chat room not found"));
            for (User participant : chatRoom.getParticipants()) {
                messagingTemplate.convertAndSendToUser(
                        participant.getId().toString(),
                        "/queue/chat/read-status",
                        notification
                );
            }
            
            log.info("[NotificationService] Sent message read status update to chatRoom: {}", chatRoomId);
            
        } catch (Exception e) {
            log.error("[NotificationService] Failed to send message read status update: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void sendUnreadCountUpdate(String chatRoomId, Long userId, long unreadCount) {
        log.info("[NotificationService] Sending unread count update for chatRoomId: {}, userId: {}, count: {}", 
                 chatRoomId, userId, unreadCount);
        
        try {
            Map<String, Object> countData = new HashMap<>();
            countData.put("chatRoomId", chatRoomId);
            countData.put("unreadCount", unreadCount);
            
            WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "UNREAD_COUNT_UPDATE",
                countData
            );
            
            // 특정 사용자에게만 읽지 않은 메시지 수 업데이트 전송
            String destination = "/queue/chat-updates";
            messagingTemplate.convertAndSendToUser(
                userId.toString(), 
                destination, 
                notification
            );
            
            log.info("[NotificationService] Sent unread count update to user: {} for chatRoom: {}", 
                     userId, chatRoomId);
            
        } catch (Exception e) {
            log.error("[NotificationService] Failed to send unread count update: {}", e.getMessage(), e);
        }
    }
    
    private void updateUnreadCountForUser(String chatRoomId, Long userId) {
        try {
            long unreadCount = messageRepository.countUnreadMessages(chatRoomId, userId);
            sendUnreadCountUpdate(chatRoomId, userId, unreadCount);
        } catch (Exception e) {
            log.error("[NotificationService] Failed to update unread count for user {}: {}", userId, e.getMessage(), e);
        }
    }
    
    private String truncateMessage(String content) {
        if (content == null) return "";
        return content.length() > 50 ? content.substring(0, 47) + "..." : content;
    }
} 
