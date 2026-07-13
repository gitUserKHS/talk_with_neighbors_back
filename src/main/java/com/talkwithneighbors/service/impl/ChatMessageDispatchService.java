package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageDispatchService {
    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final NotificationService notificationService;

    @Async("chatNotificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchNotifications(String messageId, String roomId, Long senderId) {
        try {
            Message message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new IllegalStateException("Committed chat message not found: " + messageId));
            ChatRoom room = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalStateException("Chat room not found after message commit: " + roomId));
            notificationService.sendNewMessageNotification(message, room, senderId);
        } catch (Exception exception) {
            log.error("Failed to dispatch chat notifications. messageId={}, roomId={}",
                    messageId, roomId, exception);
        }
    }
}
