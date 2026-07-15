package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.dto.MessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class ChatScheduleCardChangedEventListenerTest {
    @Test
    void sendsStableCardToEveryDistinctRoomParticipantAfterCommit() throws Exception {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ChatScheduleCardChangedEventListener listener =
                new ChatScheduleCardChangedEventListener(messagingTemplate);
        MessageDto message = new MessageDto();
        message.setId("schedule-card-1");

        listener.onScheduleCardChanged(new ChatScheduleCardChangedEvent(
                message, "room-1", List.of(1L, 2L, 1L)));

        verify(messagingTemplate).convertAndSendToUser(
                "1", "/queue/chat/room/room-1", message);
        verify(messagingTemplate).convertAndSendToUser(
                "2", "/queue/chat/room/room-1", message);
        verifyNoMoreInteractions(messagingTemplate);

        Method method = ChatScheduleCardChangedEventListener.class
                .getMethod("onScheduleCardChanged", ChatScheduleCardChangedEvent.class);
        TransactionalEventListener annotation =
                method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
