package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.dto.MessageDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageChangedEventListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChatMessageChangedEventListener listener;

    @Test
    void sendsChangedMessageAndRoomSummaryToEveryParticipant() {
        MessageDto message = new MessageDto();
        message.setId("message-1");
        message.setRoomId("room-1");
        message.setContent("수정된 메시지");

        ChatMessageChangedEvent event = new ChatMessageChangedEvent(
                message,
                "room-1",
                "수정된 메시지",
                "2026-07-13T12:34:56",
                "코아",
                List.of(11L, 22L)
        );

        listener.onMessageChanged(event);

        Map<String, Object> roomUpdate = Map.of(
                "type", "CHAT_MESSAGE_CHANGED",
                "data", Map.of(
                        "chatRoomId", "room-1",
                        "lastMessage", "수정된 메시지",
                        "lastMessageTime", "2026-07-13T12:34:56",
                        "lastSenderName", "코아"
                )
        );
        verify(messagingTemplate).convertAndSendToUser(
                "11", "/queue/chat/room/room-1", message);
        verify(messagingTemplate).convertAndSendToUser(
                "22", "/queue/chat/room/room-1", message);
        verify(messagingTemplate).convertAndSendToUser(
                "11", "/queue/chat-updates", roomUpdate);
        verify(messagingTemplate).convertAndSendToUser(
                "22", "/queue/chat-updates", roomUpdate);
    }
}
