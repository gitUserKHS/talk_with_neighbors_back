package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate; // Though not directly used by getMessagesByRoomId

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatServiceImplTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate; // Dependency of ChatServiceImpl

    @InjectMocks
    private ChatServiceImpl chatService;

    private final String testRoomId = "test-room-123";
    private final String testUserIdString = "1";
    private final Long testUserIdLong = 1L;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pageable = Pageable.unpaged();
    }

    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("testuser" + id);
        // Initialize collections to avoid NullPointerExceptions if accessed
        user.setCreatedRooms(new ArrayList<>());
        user.setJoinedRooms(new ArrayList<>());
        user.setSentMessages(new ArrayList<>());
        user.setInterests(new ArrayList<>());
        return user;
    }

    private ChatRoom createChatRoom(String id, User... participants) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("Test Room");
        room.setParticipants(new ArrayList<>(List.of(participants)));
         // Initialize other collections
        room.setMessages(new ArrayList<>());
        return room;
    }
    
    private Message createMessage(String id, ChatRoom room, User sender) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent("Hello!");
        message.setCreatedAt(LocalDateTime.now());
        message.setReadByUsers(new HashSet<>()); // Initialize to empty set
        message.getReadByUsers().add(sender.getId()); // Sender has read the message
        return message;
    }


    @Test
    void testGetMessagesByRoomId_whenUserNotParticipant_shouldThrowChatException() {
        User requestingUser = createUser(testUserIdLong);
        User otherUser = createUser(2L);
        ChatRoom testRoom = createChatRoom(testRoomId, otherUser); // requestingUser is not a participant

        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(requestingUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.of(testRoom));

        ChatException exception = assertThrows(ChatException.class, () -> {
            chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);
        });

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertTrue(exception.getMessage().startsWith("Access denied to chat room"));
        verify(userRepository).findById(testUserIdLong);
        verify(chatRoomRepository).findById(testRoomId);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void testGetMessagesByRoomId_whenUserIsParticipant_shouldReturnMessages() {
        User participantUser = createUser(testUserIdLong);
        ChatRoom testRoom = createChatRoom(testRoomId, participantUser); // User is a participant

        Message message1 = createMessage(UUID.randomUUID().toString(), testRoom, participantUser);
        List<Message> messages = List.of(message1);
        Page<Message> messagePage = new PageImpl<>(messages, pageable, messages.size());

        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(participantUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.findByChatRoomIdOrderByCreatedAtDesc(testRoomId, pageable)).thenReturn(messagePage);
        // Mock saveAll for the read-by-users update
        when(messageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));


        Page<MessageDto> result = chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(message1.getId(), result.getContent().get(0).getId());
        assertTrue(result.getContent().get(0).isReadByCurrentUser(), "Message should be marked as read by current user");

        verify(userRepository).findById(testUserIdLong);
        verify(chatRoomRepository).findById(testRoomId);
        verify(messageRepository).findByChatRoomIdOrderByCreatedAtDesc(testRoomId, pageable);
        // Verify that saveAll was called if there were messages to mark as read.
        // In this setup, message1 is sent by participantUser, so it's already "read" by them.
        // Let's refine this: if the message was from another user, it would be marked read.
        // For simplicity, we'll just check if saveAll is potentially called.
        // If message1 was not read by participantUser, it would be added to messagesToUpdate.
        // Since participantUser is the sender, readByUsers already contains their ID.
        // Thus, messagesToUpdate would be empty unless other unread messages existed.
        // Let's assume the "mark as read" logic is tested more deeply elsewhere or accept this level of detail.
        // For now, we just verify the main path. If message1 was not read, saveAll would be called.
        // If we want to explicitly test saveAll, we need a message not read by the user.
        // For now, let's assume it works, or add a specific test for "mark as read" part.
        // verify(messageRepository, atMostOnce()).saveAll(anyList()); // atMostOnce because it might be an empty list
    }
    
    @Test
    void testGetMessagesByRoomId_whenUserIsParticipant_MessageFromAnotherUser_MarkedAsRead() {
        User currentUser = createUser(testUserIdLong);
        User otherUser = createUser(2L); // Sender of the message
        ChatRoom testRoom = createChatRoom(testRoomId, currentUser, otherUser); // Both are participants

        Message messageFromOtherUser = createMessage(UUID.randomUUID().toString(), testRoom, otherUser);
        // Ensure currentUser has NOT read this message yet for the test
        messageFromOtherUser.getReadByUsers().remove(currentUser.getId()); 

        List<Message> messages = List.of(messageFromOtherUser);
        Page<Message> messagePage = new PageImpl<>(messages, pageable, messages.size());

        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(currentUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.findByChatRoomIdOrderByCreatedAtDesc(testRoomId, pageable)).thenReturn(messagePage);
        when(messageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        Page<MessageDto> result = chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertTrue(result.getContent().get(0).isReadByCurrentUser()); // Should now be true
        verify(messageRepository).saveAll(argThat(list -> !list.isEmpty() && ((Message)list.get(0)).getReadByUsers().contains(currentUser.getId())));
    }


    @Test
    void testGetMessagesByRoomId_whenRoomNotFound_shouldThrowChatException() {
        User requestingUser = createUser(testUserIdLong);
        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(requestingUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.empty()); // Room does not exist

        ChatException exception = assertThrows(ChatException.class, () -> {
            chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertTrue(exception.getMessage().startsWith("Chat room not found with id:"));
        verify(userRepository).findById(testUserIdLong);
        verify(chatRoomRepository).findById(testRoomId);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void testGetMessagesByRoomId_whenUserNotFound_shouldThrowChatException() {
        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.empty()); // User does not exist

        ChatException exception = assertThrows(ChatException.class, () -> {
            chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertTrue(exception.getMessage().startsWith("User not found with id:"));
        verify(userRepository).findById(testUserIdLong);
        verifyNoInteractions(chatRoomRepository); // Should not be called if user check fails first
        verifyNoInteractions(messageRepository);
    }
}
