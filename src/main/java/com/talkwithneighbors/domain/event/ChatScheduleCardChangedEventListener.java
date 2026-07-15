package com.talkwithneighbors.domain.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatScheduleCardChangedEventListener {
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleCardChanged(ChatScheduleCardChangedEvent event) {
        try {
            String destination = "/queue/chat/room/" + event.roomId();
            event.participantIds().stream()
                    .distinct()
                    .forEach(participantId -> messagingTemplate.convertAndSendToUser(
                            participantId.toString(), destination, event.message()));
        } catch (Exception exception) {
            log.error("Failed to dispatch schedule card update. scheduleMessageId={}, roomId={}",
                    event.message().getId(), event.roomId(), exception);
        }
    }
}
