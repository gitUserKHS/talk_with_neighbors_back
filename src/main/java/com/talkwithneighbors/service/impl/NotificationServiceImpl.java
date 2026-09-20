package com.talkwithneighbors.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    /**
     * 입장·퇴장·시스템 메시지는 방 안의 안내일 뿐이라 토스트·알림함·푸시를 만들지 않는다.
     * 읽지 않은 수 갱신은 방 목록 배지를 맞추기 위해 그대로 보낸다.
     */
    private static final Set<Message.MessageType> SILENT_MESSAGE_TYPES =
            EnumSet.of(Message.MessageType.ENTER, Message.MessageType.LEAVE, Message.MessageType.SYSTEM);

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final RedisSessionService redisSessionService;
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;

    @Override
    public void sendNewMessageNotification(Message message, ChatRoom chatRoom, Long senderId) {
        log.debug("=== [NotificationService] sendNewMessageNotification START ===");
        log.debug("[NotificationService] MessageId: {}, ChatRoomId: {}, SenderId: {}",
                 message.getId(), chatRoom.getId(), senderId);
        log.debug("[NotificationService] ChatRoom participants count: {}", chatRoom.getParticipants().size());
        boolean silentMessage = message.getType() != null && SILENT_MESSAGE_TYPES.contains(message.getType());

        // 채팅방의 모든 참여자 처리
        for (User participant : chatRoom.getParticipants()) {
            Long participantId = participant.getId();
            boolean isUserOnline = redisSessionService.isUserOnline(participantId.toString());
            boolean isUserInRoom = redisSessionService.isUserInRoom(participantId.toString(), chatRoom.getId());

            log.debug("[NotificationService] Processing participant: {}, Online: {}, InRoom: {}",
                     participantId, isUserOnline, isUserInRoom);

            try {
                // 발신자가 아니면서 채팅방 밖에 있는 사용자에게만 새 메시지 알림 처리
                if (!participantId.equals(senderId) && !isUserInRoom) {
                    if (!silentMessage) {
                        handleNewMessageNotification(participantId, message, chatRoom, isUserOnline);
                    }

                    // 읽지 않은 메시지 수 업데이트 처리
                    handleUnreadCountUpdate(participantId, chatRoom.getId(), isUserOnline);
                } else if (participantId.equals(senderId)) {
                    log.debug("[NotificationService] User {} is sender, skipping new message notification", participantId);
                } else {
                    log.debug("[NotificationService] User {} is currently in room {}, skipping new message notification",
                             participantId, chatRoom.getId());
                }

            } catch (Exception e) {
                log.error("[NotificationService] ❌ Failed to send updates to user {}: {}", participantId, e.getMessage(), e);
            }
        }

        log.debug("=== [NotificationService] sendNewMessageNotification END ===");
    }

    /**
     * 새 메시지 알림 처리 (온라인/오프라인 대응)
     *
     * 알림함 행은 한 번만 저장한다. 열린 소켓이 없는 사용자에게는 저장 단계에서 푸시가 함께 나가고,
     * 온라인 사용자에게는 토스트를 보낸 뒤 같은 행을 전달 완료로 표시한다.
     */
    private void handleNewMessageNotification(Long userId, Message message, ChatRoom chatRoom, boolean isUserOnline) {
        log.debug("=== [NotificationService] handleNewMessageNotification START ===");
        log.debug("[NotificationService] userId: {}, isUserOnline: {}, messageId: {}", userId, isUserOnline, message.getId());

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
            log.debug("[NotificationService] Sending notification to user {} (outside room)", userId);

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

            log.debug("[NotificationService] ✅ Successfully sent notification to user {}", userId);
        } else {
            // 오프라인 사용자는 위에서 저장한 알림함 행을 재접속 시 전달받는다.
            log.debug("[NotificationService] User {} is offline; inbox row kept for delivery on reconnect", userId);
        }
        log.debug("=== [NotificationService] handleNewMessageNotification END ===");
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

    @Override
    public void sendMessageReadStatusUpdate(String messageId, String chatRoomId, Long readByUserId) {
        log.debug("[NotificationService] Sending message read status update for messageId: {}, readByUserId: {}",
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

            log.debug("[NotificationService] Sent message read status update to chatRoom: {}", chatRoomId);

        } catch (Exception e) {
            log.error("[NotificationService] Failed to send message read status update: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendUnreadCountUpdate(String chatRoomId, Long userId, long unreadCount) {
        log.debug("[NotificationService] Sending unread count update for chatRoomId: {}, userId: {}, count: {}",
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

            log.debug("[NotificationService] Sent unread count update to user: {} for chatRoom: {}",
                     userId, chatRoomId);

        } catch (Exception e) {
            log.error("[NotificationService] Failed to send unread count update: {}", e.getMessage(), e);
        }
    }

    private void updateUnreadCountForUser(String chatRoomId, Long userId) {
        try {
            long unreadCount = messageRepository.countVisibleUnreadMessages(
                    chatRoomId, userId, Message.MessageType.SCHEDULE);
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
