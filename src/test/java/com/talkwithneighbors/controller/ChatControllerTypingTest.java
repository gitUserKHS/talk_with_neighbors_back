package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.TypingSignalDto;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.RoomParticipantCache;
import com.talkwithneighbors.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTypingTest {
    private static final String ROOM_ID = "room-1";
    private static final String ROOM_QUEUE = "/queue/chat/room/" + ROOM_ID;

    @Mock
    private ChatService chatService;

    @Mock
    private RedisSessionService redisSessionService;

    @Mock
    private UserService userService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private RoomParticipantCache roomParticipantCache;

    private ChatController controller() {
        return new ChatController(
                chatService,
                redisSessionService,
                userService,
                messagingTemplate,
                messageRepository,
                mediaStorageService,
                roomParticipantCache
        );
    }

    private static Map<String, Object> sessionOf(String userId) {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("userId", userId);
        return sessionAttributes;
    }

    @Test
    void typingSignalFansOutToEveryOtherParticipantOnTheRoomQueue() {
        when(roomParticipantCache.participantIds(ROOM_ID)).thenReturn(List.of(1L, 2L, 3L));
        when(roomParticipantCache.participantName(ROOM_ID, 1L)).thenReturn("alice");
        LocalDateTime before = LocalDateTime.now();

        controller().typing(Map.of("roomId", ROOM_ID), sessionOf("1"));

        ArgumentCaptor<TypingSignalDto> signal = ArgumentCaptor.forClass(TypingSignalDto.class);
        verify(messagingTemplate).convertAndSendToUser(eq("2"), eq(ROOM_QUEUE), signal.capture());
        verify(messagingTemplate).convertAndSendToUser(eq("3"), eq(ROOM_QUEUE), any(TypingSignalDto.class));
        verify(messagingTemplate, never()).convertAndSendToUser(eq("1"), anyString(), any(TypingSignalDto.class));

        TypingSignalDto sent = signal.getValue();
        assertEquals("TYPING", sent.type());
        assertEquals(ROOM_ID, sent.roomId());
        assertEquals(1L, sent.userId());
        assertEquals("alice", sent.senderName());
        assertTrue(sent.expiresAt().isAfter(before.plusSeconds(3)));
        assertTrue(sent.expiresAt().isBefore(LocalDateTime.now().plusSeconds(5)));
        verifyNoInteractions(chatService, messageRepository);
    }

    @Test
    void signalFromUserOutsideTheCachedParticipantListIsDroppedSilently() {
        when(roomParticipantCache.participantIds(ROOM_ID)).thenReturn(List.of(2L, 3L));

        controller().typing(Map.of("roomId", ROOM_ID), sessionOf("1"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(TypingSignalDto.class));
        verify(roomParticipantCache, never()).participantName(anyString(), anyLong());
    }

    @Test
    void signalWithoutRoomIdOrSessionUserNeverTouchesCacheOrBroker() {
        controller().typing(Map.of(), sessionOf("1"));
        controller().typing(Map.of("roomId", ROOM_ID), new HashMap<>());

        verifyNoInteractions(roomParticipantCache, messagingTemplate);
    }
}
