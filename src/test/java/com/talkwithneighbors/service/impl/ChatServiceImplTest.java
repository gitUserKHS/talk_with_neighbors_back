package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.ChatRoomType;
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
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    private ChatRoom room;
    private Message message;
    private User creator;
    private User participant;
    private Set<User> participants;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pageable = Pageable.unpaged();

        creator = createUser(1L, "creator");
        participant = createUser(2L, "participant");
        participants = new HashSet<>(List.of(creator, participant));

        room = new ChatRoom();
        room.setId("test-room-id");
        room.setName("Test Room");
        room.setType(ChatRoomType.GROUP);
        room.setCreator(creator);
        room.setParticipants(participants);

        message = createMessage("test-message-id", room, creator);
    }

    private User createUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setCreatedRooms(new ArrayList<>());
        user.setJoinedRooms(new ArrayList<>());
        user.setSentMessages(new ArrayList<>());
        user.setInterests(new ArrayList<>());
        return user;
    }

    private ChatRoom createChatRoom(String id, User creator, Set<User> participants) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("Test Room " + id);
        room.setType(ChatRoomType.GROUP);
        room.setCreator(creator);
        room.setParticipants(participants);
        return room;
    }
    
    private Message createMessage(String id, ChatRoom room, User sender) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent("Hello!");
        message.setCreatedAt(LocalDateTime.now());
        message.setReadByUsers(new HashSet<>());
        message.getReadByUsers().add(sender.getId());
        return message;
    }


    @Test
    void testGetMessagesByRoomId_whenUserNotParticipant_shouldThrowChatException() {
        User requestingUser = createUser(testUserIdLong, "requestingUser");
        User otherUser = createUser(3L, "otherUser");
        ChatRoom testRoom = createChatRoom(testRoomId, otherUser, new HashSet<>(List.of(otherUser)));

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
        User participantUser = createUser(testUserIdLong, "participantUser");
        ChatRoom testRoom = createChatRoom(testRoomId, participantUser, new HashSet<>(List.of(participantUser)));

        Message message1 = createMessage(UUID.randomUUID().toString(), testRoom, participantUser);
        List<Message> messages = List.of(message1);
        Page<Message> messagePage = new PageImpl<>(messages, pageable, messages.size());

        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(participantUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.findByChatRoomIdOrderByCreatedAtDesc(testRoomId, pageable)).thenReturn(messagePage);
        when(messageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));


        Page<MessageDto> result = chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(message1.getId(), result.getContent().get(0).getId());
        assertTrue(result.getContent().get(0).isReadByCurrentUser(), "Message should be marked as read by current user");

        verify(userRepository).findById(testUserIdLong);
        verify(chatRoomRepository).findById(testRoomId);
        verify(messageRepository).findByChatRoomIdOrderByCreatedAtDesc(testRoomId, pageable);
    }
    
    @Test
    void testGetMessagesByRoomId_whenUserIsParticipant_MessageFromAnotherUser_MarkedAsRead() {
        User currentUser = createUser(testUserIdLong, "currentUser");
        User otherUser = createUser(2L, "otherUser");
        ChatRoom testRoom = createChatRoom(testRoomId, otherUser, new HashSet<>(List.of(currentUser, otherUser)));

        Message messageFromOtherUser = createMessage(UUID.randomUUID().toString(), testRoom, otherUser);
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
        assertTrue(result.getContent().get(0).isReadByCurrentUser());

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());
        List<Message> capturedMessages = captor.getValue();
        assertFalse(capturedMessages.isEmpty());
        assertTrue(capturedMessages.get(0).getReadByUsers().contains(currentUser.getId()));
    }


    @Test
    void testGetMessagesByRoomId_whenRoomNotFound_shouldThrowChatException() {
        User requestingUser = createUser(testUserIdLong, "requestingUser");
        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(requestingUser));
        when(chatRoomRepository.findById(testRoomId)).thenReturn(Optional.empty());

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
        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.empty());

        ChatException exception = assertThrows(ChatException.class, () -> {
            chatService.getMessagesByRoomId(testRoomId, testUserIdString, pageable);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertTrue(exception.getMessage().startsWith("User not found with id:"));
        verify(userRepository).findById(testUserIdLong);
        verifyNoInteractions(chatRoomRepository);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void markMessageAsRead_ShouldUpdateReadByUsers() {
        User testUser = createUser(1L, "testUser");
        Message messageToMark = createMessage("msg-id", room, testUser);
        messageToMark.getReadByUsers().remove(testUser.getId());

        when(messageRepository.findById("msg-id")).thenReturn(Optional.of(messageToMark));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(messageRepository.save(any(Message.class))).thenReturn(messageToMark);

        chatService.markMessageAsRead("msg-id", "1");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        Message savedMessage = messageCaptor.getValue();
        assertTrue(savedMessage.getReadByUsers().contains(testUser.getId()));
    }
}
