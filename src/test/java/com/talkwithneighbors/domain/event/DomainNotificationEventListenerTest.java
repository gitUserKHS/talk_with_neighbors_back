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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void postCommentNotifiesAuthorOnSystemQueueWithSnippet() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );
        OfflineNotification saved = new OfflineNotification();
        saved.setId(7L);
        when(offlineNotificationService.saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.POST_COMMENTED),
                any(String.class),
                any(String.class),
                any(String.class),
                any(Integer.class)
        )).thenReturn(saved);
        when(redisSessionService.isUserOnline("1")).thenReturn(true);

        listener.onPostCommented(PostCommentedEvent.create(
                "post-1", "comment-1", 1L, 2L, "neighbor", "nice photo"));

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(offlineNotificationService).saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.POST_COMMENTED),
                data.capture(),
                eq("neighbor님이 회원님의 글에 댓글을 남겼어요: nice photo"),
                eq("/feed/post-1"),
                eq(4)
        );
        assertEquals("{\"postId\":\"post-1\",\"commentId\":\"comment-1\",\"actorId\":2}", data.getValue());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<WebSocketNotification> notification =
                ArgumentCaptor.forClass(WebSocketNotification.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1"), eq("/queue/system-notifications"), notification.capture());
        assertEquals("POST_COMMENTED", notification.getValue().getType());
        verify(offlineNotificationService).markAsDelivered(7L);
    }

    @Test
    void postLikeNotifiesAuthorAndStaysPendingWhileOffline() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );
        when(redisSessionService.isUserOnline("1")).thenReturn(false);

        listener.onPostLiked(PostLikedEvent.create("post-1", 1L, 2L, "neighbor"));

        verify(offlineNotificationService).saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.POST_LIKED),
                eq("{\"postId\":\"post-1\",\"actorId\":2}"),
                eq("neighbor님이 회원님의 글을 좋아해요"),
                eq("/feed/post-1"),
                eq(4)
        );
        verify(messagingTemplate, never()).convertAndSendToUser(
                any(String.class), any(String.class), any(Object.class));
        verify(offlineNotificationService, never()).markAsDelivered(any(Long.class));
    }

    @Test
    void repeatedLikeOnAlreadyDeliveredNotificationDoesNotToastAgain() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );
        OfflineNotification delivered = new OfflineNotification();
        delivered.setId(9L);
        delivered.setIsSent(true);
        when(offlineNotificationService.saveOfflineNotification(
                eq(1L),
                eq(OfflineNotification.NotificationType.POST_LIKED),
                any(String.class),
                any(String.class),
                any(String.class),
                any(Integer.class)
        )).thenReturn(delivered);

        listener.onPostLiked(PostLikedEvent.create("post-1", 1L, 2L, "neighbor"));

        verify(messagingTemplate, never()).convertAndSendToUser(
                any(String.class), any(String.class), any(Object.class));
        verify(offlineNotificationService, never()).markAsDelivered(any(Long.class));
        verifyNoInteractions(redisSessionService);
    }

    @Test
    void selfCommentAndSelfLikeNeverDeliver() {
        DomainNotificationEventListener listener = new DomainNotificationEventListener(
                messagingTemplate,
                redisSessionService,
                offlineNotificationService,
                new ObjectMapper()
        );

        listener.onPostCommented(PostCommentedEvent.create(
                "post-1", "comment-1", 1L, 1L, "author", "talking to myself"));
        listener.onPostLiked(PostLikedEvent.create("post-1", 1L, 1L, "author"));

        verifyNoInteractions(offlineNotificationService, messagingTemplate);
    }
}
