package com.talkwithneighbors.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    @Test
    void readStatusIsDeliveredOnlyToParticipantUserQueues() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ChatRoomRepository rooms = mock(ChatRoomRepository.class);
        ChatRoom room = new ChatRoom();
        room.setId("room-1");
        User first = user(1L);
        User second = user(2L);
        room.setParticipants(new LinkedHashSet<>(java.util.List.of(first, second)));
        when(rooms.findById("room-1")).thenReturn(Optional.of(room));

        NotificationServiceImpl service = new NotificationServiceImpl(
                messaging,
                mock(MessageRepository.class),
                rooms,
                mock(RedisSessionService.class),
                mock(OfflineNotificationService.class),
                new ObjectMapper()
        );

        service.sendMessageReadStatusUpdate("message-1", "room-1", 2L);

        verify(messaging).convertAndSendToUser(eq("1"), eq("/queue/chat/read-status"), any());
        verify(messaging).convertAndSendToUser(eq("2"), eq("/queue/chat/read-status"), any());
        verify(messaging, never()).convertAndSend(eq("/topic/chat/room/room-1/read-status"), any(Object.class));
    }

    @Test
    void onlineOutsideRoomRecipientGetsOneInboxRowAndOneToast() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        RedisSessionService sessions = mock(RedisSessionService.class);
        OfflineNotificationService inbox = mock(OfflineNotificationService.class);
        User sender = user(1L);
        User recipient = user(2L);
        ChatRoom room = room("room-1", sender, recipient);
        Message message = message("message-1", room, sender, Message.MessageType.TEXT);
        when(sessions.isUserOnline("2")).thenReturn(true);
        when(sessions.isUserInRoom("2", "room-1")).thenReturn(false);
        OfflineNotification saved = new OfflineNotification();
        saved.setId(42L);
        when(inbox.saveOfflineNotification(eq(2L), eq(OfflineNotification.NotificationType.NEW_MESSAGE),
                any(), any(), eq("/chat/room-1"), any())).thenReturn(saved);

        service(messaging, sessions, inbox).sendNewMessageNotification(message, room, 1L);

        // 알림함 행은 한 번만 저장되고, 온라인이므로 토스트 뒤에 같은 행이 전달 완료로 표시된다.
        verify(inbox, times(1)).saveOfflineNotification(eq(2L), eq(OfflineNotification.NotificationType.NEW_MESSAGE),
                any(), any(), any(), any());
        verify(inbox).markAsDelivered(42L);
        verify(messaging, times(1)).convertAndSendToUser(eq("2"), eq("/queue/chat-notifications"), any());
        verify(inbox, never()).saveOfflineNotification(eq(1L), any(), any(), any(), any(), any());
    }

    @Test
    void offlineRecipientGetsExactlyOneInboxRow() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        RedisSessionService sessions = mock(RedisSessionService.class);
        OfflineNotificationService inbox = mock(OfflineNotificationService.class);
        User sender = user(1L);
        User recipient = user(2L);
        ChatRoom room = room("room-1", sender, recipient);
        Message message = message("message-1", room, sender, Message.MessageType.TEXT);
        when(sessions.isUserOnline("2")).thenReturn(false);
        when(sessions.isUserInRoom("2", "room-1")).thenReturn(false);

        service(messaging, sessions, inbox).sendNewMessageNotification(message, room, 1L);

        // 예전에는 오프라인 분기에서 같은 행을 한 번 더 저장했다.
        verify(inbox, times(1)).saveOfflineNotification(eq(2L), eq(OfflineNotification.NotificationType.NEW_MESSAGE),
                any(), any(), any(), any());
        verify(messaging, never()).convertAndSendToUser(eq("2"), eq("/queue/chat-notifications"), any());
    }

    @Test
    void systemMessageDoesNotCreateToastOrInboxRow() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        RedisSessionService sessions = mock(RedisSessionService.class);
        OfflineNotificationService inbox = mock(OfflineNotificationService.class);
        User sender = user(1L);
        User recipient = user(2L);
        ChatRoom room = room("room-1", sender, recipient);
        Message message = message("message-1", room, sender, Message.MessageType.SYSTEM);
        when(sessions.isUserOnline("2")).thenReturn(true);
        when(sessions.isUserInRoom("2", "room-1")).thenReturn(false);

        service(messaging, sessions, inbox).sendNewMessageNotification(message, room, 1L);

        verify(inbox, never()).saveOfflineNotification(any(), any(), any(), any(), any(), any());
        verify(messaging, never()).convertAndSendToUser(eq("2"), eq("/queue/chat-notifications"), any());
        // 읽지 않은 수 배지는 그대로 갱신된다.
        verify(messaging).convertAndSendToUser(eq("2"), eq("/queue/chat-updates"), any());
    }

    private NotificationServiceImpl service(SimpMessagingTemplate messaging,
                                            RedisSessionService sessions,
                                            OfflineNotificationService inbox) {
        return new NotificationServiceImpl(
                messaging,
                mock(MessageRepository.class),
                mock(ChatRoomRepository.class),
                sessions,
                inbox,
                new ObjectMapper()
        );
    }

    private ChatRoom room(String id, User... participants) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("room " + id);
        room.setParticipants(new LinkedHashSet<>(java.util.List.of(participants)));
        return room;
    }

    private Message message(String id, ChatRoom room, User sender, Message.MessageType type) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setType(type);
        message.setContent("hello");
        message.setCreatedAt(LocalDateTime.of(2026, 9, 20, 12, 0));
        return message;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        return user;
    }
}
