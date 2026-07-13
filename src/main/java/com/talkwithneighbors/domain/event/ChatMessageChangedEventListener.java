package com.talkwithneighbors.domain.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageChangedEventListener {
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageChanged(ChatMessageChangedEvent event) {
        try {
            String destination = "/queue/chat/room/" + event.roomId();
            Map<String, Object> summary = new HashMap<>();
            summary.put("chatRoomId", event.roomId());
            summary.put("lastMessage", event.lastMessage());
            summary.put("lastMessageTime", event.lastMessageTime());
            summary.put("lastSenderName", event.lastSenderName());
            Map<String, Object> roomUpdate = Map.of(
                    "type", "CHAT_MESSAGE_CHANGED",
                    "data", summary
            );
            event.participantIds().forEach(participantId -> {
                messagingTemplate.convertAndSendToUser(
                        participantId.toString(), destination, event.message());
                messagingTemplate.convertAndSendToUser(
                        participantId.toString(), "/queue/chat-updates", roomUpdate);
            });
        } catch (Exception exception) {
            log.error("Failed to dispatch changed chat message. messageId={}, roomId={}",
                    event.message().getId(), event.roomId(), exception);
        }
    }
}
