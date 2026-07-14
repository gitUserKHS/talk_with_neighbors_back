package com.talkwithneighbors.domain.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DomainNotificationEventListenerTest {
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RedisSessionService redisSessionService;

    @Mock
    private OfflineNotificationService offlineNotificationService;

    @Test
    void roomDeletionClearsPresenceAndUpdatesBothChatQueues() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );
        when(redisSessionService.isUserOnline("1")).thenReturn(true);
        ChatRoomDeletedEvent event = ChatRoomDeletedEvent.create("room-1", List.of(1L));

        listener.onChatRoomDeleted(event);

        verify(redisSessionService).clearUserCurrentRoomIfMatches("1", "room-1");
        verify(offlineNotificationService).saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.ROOM_DELETED),
                any(String.class),
                eq("채팅방이 삭제되었어."),
                eq("/chat"),
                eq(8)
        );

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<WebSocketNotification> notification =
                ArgumentCaptor.forClass(WebSocketNotification.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1"), eq("/queue/chat-notifications"), notification.capture());
        verify(messagingTemplate).convertAndSendToUser(
                eq("1"), eq("/queue/chat-updates"), any(WebSocketNotification.class));
        assertEquals("ROOM_DELETED", notification.getValue().getType());
    }

    @Test
    void transientSocketFailureLeavesDurableNotificationPendingWithoutFailingEvent() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );
        OfflineNotification saved = new OfflineNotification();
        saved.setId(42L);
        when(offlineNotificationService.saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.ROOM_DELETED),
                any(String.class),
                any(String.class),
                any(String.class),
                any(Integer.class)
        )).thenReturn(saved);
        when(redisSessionService.isUserOnline("1")).thenReturn(true);
        doThrow(new IllegalStateException("socket unavailable"))
                .when(messagingTemplate)
                .convertAndSendToUser(
                        eq("1"), eq("/queue/chat-notifications"), any(WebSocketNotification.class));

        assertDoesNotThrow(() -> listener.onChatRoomDeleted(
                ChatRoomDeletedEvent.create("room-1", List.of(1L))));

        verify(offlineNotificationService, never()).markAsDelivered(42L);
    }
}
