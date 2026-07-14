package com.talkwithneighbors.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashSet;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
