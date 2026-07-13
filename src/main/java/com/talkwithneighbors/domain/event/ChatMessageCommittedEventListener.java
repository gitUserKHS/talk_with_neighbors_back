package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.service.impl.ChatMessageDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageCommittedEventListener {
    private final ChatMessageDispatchService dispatchService;
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCommitted(ChatMessageCommittedEvent event) {
        try {
            String destination = "/queue/chat/room/" + event.roomId();
            event.participantIds().stream()
                    .filter(participantId -> !participantId.equals(event.senderId()))
                    .forEach(participantId -> messagingTemplate.convertAndSendToUser(
                            participantId.toString(), destination, event.message()));

            // Notification fan-out is bounded and asynchronous. It must not consume a
            // database connection per HTTP sender while realtime delivery is occurring.
            dispatchService.dispatchNotifications(
                    event.message().getId(), event.roomId(), event.senderId());
        } catch (Exception exception) {
            // The message is already committed. Do not return a misleading 500 that may
            // cause clients to retry and create a duplicate message.
            log.error("Failed to dispatch committed chat message. messageId={}, roomId={}",
                    event.message().getId(), event.roomId(), exception);
        }
    }
}
