package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.domain.event.ChatRoomDeletedEvent;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.repository.ChatRoomDeletionRepository;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.MeetupWaitlistRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.ChatScheduleRsvpRepository;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ChatRoomDeletionRepository chatRoomDeletionRepository;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ChatScheduleRepository chatScheduleRepository;

    @Mock
    private ChatScheduleRsvpRepository chatScheduleRsvpRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private MeetupWaitlistRepository meetupWaitlistRepository;

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
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), testUser))
                .thenReturn(Optional.of(room));
        when(messageRepository.save(any(Message.class))).thenReturn(messageToMark);

        chatService.markMessageAsRead(room.getId(), "msg-id", "1");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        Message savedMessage = messageCaptor.getValue();
        assertTrue(savedMessage.getReadByUsers().contains(testUser.getId()));
    }

    @Test
    void markMessageAsReadRejectsNonParticipantBeforeLoadingMessage() {
        User outsider = createUser(9L, "outsider");
        when(userRepository.findById(9L)).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), outsider))
                .thenReturn(Optional.empty());

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.markMessageAsRead(room.getId(), "msg-id", "9"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(messageRepository, never()).findById(anyString());
        verify(messageRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void markMessageAsReadRejectsMessageFromAnotherRoom() {
        User reader = createUser(1L, "reader");
        ChatRoom anotherRoom = createChatRoom("another-room", creator, participants);
        Message foreignMessage = createMessage("foreign-message", anotherRoom, creator);
        when(userRepository.findById(1L)).thenReturn(Optional.of(reader));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), reader))
                .thenReturn(Optional.of(room));
        when(messageRepository.findById("foreign-message")).thenReturn(Optional.of(foreignMessage));

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.markMessageAsRead(room.getId(), "foreign-message", "1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(messageRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void unreadCountRequiresRoomMembership() {
        User outsider = createUser(9L, "outsider");
        when(userRepository.findById(9L)).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), outsider))
                .thenReturn(Optional.empty());

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.getUnreadCount(room.getId(), "9"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(messageRepository, never()).countUnreadMessages(anyString(), any());
    }

    @Test
    void unreadCountReturnsCountForParticipant() {
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), creator))
                .thenReturn(Optional.of(room));
        when(messageRepository.countUnreadMessages(room.getId(), creator.getId())).thenReturn(3L);

        assertEquals(3L, chatService.getUnreadCount(room.getId(), creator.getId().toString()));
    }

    @Test
    void genericMeetupJoinRejectsBlockedUserAtServiceBoundary() {
        User outsider = createUser(3L, "outsider");
        room.setPublicRoom(true);
        room.setParticipants(new HashSet<>(List.of(creator)));
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(userBlockRepository.existsBetween(outsider.getId(), creator.getId())).thenReturn(true);

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.joinRoom(room.getId(), outsider.getId().toString()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(chatRoomRepository, never()).save(room);
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void genericMeetupJoinRejectsExpiredRegistration() {
        User outsider = createUser(3L, "outsider");
        room.setPublicRoom(true);
        room.setParticipants(new HashSet<>(List.of(creator)));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setRegistrationDeadline(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.joinRoom(room.getId(), outsider.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void genericMeetupJoinUsesLockedLookupAndCannotLeapfrogWaitlist() {
        User outsider = createUser(3L, "outsider");
        room.setPublicRoom(true);
        room.setMaxParticipants(4);
        room.setParticipants(new HashSet<>(List.of(creator)));
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(meetupWaitlistRepository.countByRoom_Id(room.getId())).thenReturn(1L);

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.joinRoom(room.getId(), outsider.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(chatRoomRepository).findByIdForUpdate(room.getId());
        verify(chatRoomRepository, never()).findById(room.getId());
        verify(chatRoomRepository, never()).save(room);
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void deleteRoomDeletesTheCompleteGraphAndPublishesAfterCommitEvents() {
        String roomId = room.getId();
        List<String> mediaUrls = List.of(
                "/api/media/chat/image.webp",
                "/api/media/chat/image-thumbnail.webp"
        );
        ChatRoomDeletionRepository.ChatRoomDeletionResult deletionResult =
                new ChatRoomDeletionRepository.ChatRoomDeletionResult(0, 2, 1, 1, 0, 2, 1);

        when(chatRoomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(chatRoomDeletionRepository.findMediaUrlsByRoomId(roomId)).thenReturn(mediaUrls);
        when(chatRoomDeletionRepository.deleteByRoomId(roomId)).thenReturn(deletionResult);

        chatService.deleteRoom(roomId, creator.getId());

        verify(chatRoomDeletionRepository).deleteByRoomId(roomId);
        verify(domainEventPublisher).publish(argThat(event ->
                event instanceof MediaFilesDeletedEvent mediaEvent
                        && mediaEvent.mediaUrls().equals(mediaUrls)
                        && mediaEvent.aggregateId().equals(roomId)));
        verify(domainEventPublisher).publish(argThat(event ->
                event instanceof ChatRoomDeletedEvent roomEvent
                        && roomEvent.roomId().equals(roomId)
                        && Set.copyOf(roomEvent.participantIds())
                                .equals(Set.of(creator.getId(), participant.getId()))));
    }

    @Test
    void deleteRoomMissingRoomReturnsNotFoundWithoutDeletingAnything() {
        when(chatRoomRepository.findByIdForUpdate(testRoomId)).thenReturn(Optional.empty());

        ChatException exception = assertThrows(
                ChatException.class,
                () -> chatService.deleteRoom(testRoomId, creator.getId())
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(chatRoomDeletionRepository, applicationEventPublisher, domainEventPublisher);
    }

    @Test
    void deleteRoomDatabaseFailureDoesNotPublishDeletionEvents() {
        String roomId = room.getId();
        when(chatRoomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(chatRoomDeletionRepository.findMediaUrlsByRoomId(roomId)).thenReturn(List.of());
        when(chatRoomDeletionRepository.deleteByRoomId(roomId))
                .thenThrow(new IllegalStateException("constraint failure"));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> chatService.deleteRoom(roomId, creator.getId())
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        verifyNoInteractions(applicationEventPublisher, domainEventPublisher);
    }

    @Test
    void nonCreatorCannotDeleteRoomAtServiceBoundary() {
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> chatService.deleteRoom(room.getId(), participant.getId()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(chatRoomDeletionRepository, applicationEventPublisher, domainEventPublisher);
    }

    @Test
    void leavingRoomRemovesScheduleRsvps() {
        when(userRepository.findById(participant.getId())).thenReturn(Optional.of(participant));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        chatService.leaveRoom(room.getId(), participant.getId().toString());

        assertFalse(room.getParticipants().contains(participant));
        verify(chatScheduleRsvpRepository)
                .deleteBySchedule_Room_IdAndUser_Id(room.getId(), participant.getId());
        verify(chatRoomRepository).save(room);
    }

    @Test
    void scheduleCreatorCannotLeaveBeforeCancellingFutureSchedule() {
        when(userRepository.findById(participant.getId())).thenReturn(Optional.of(participant));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(chatScheduleRepository.existsByRoom_IdAndCreator_IdAndStatusAndStartsAtAfter(
                eq(room.getId()),
                eq(participant.getId()),
                eq(ChatScheduleStatus.SCHEDULED),
                any(Instant.class)))
                .thenReturn(true);

        ChatException exception = assertThrows(
                ChatException.class,
                () -> chatService.leaveRoom(room.getId(), participant.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(room.getParticipants().contains(participant));
        verify(chatScheduleRsvpRepository, never())
                .deleteBySchedule_Room_IdAndUser_Id(anyString(), any());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void searchRoomsUsesOnlyTheParticipantScopedRepositoryQuery() {
        User currentUser = createUser(testUserIdLong, "currentUser");
        ChatRoom participantRoom = createChatRoom(
                "participant-room",
                currentUser,
                new HashSet<>(List.of(currentUser))
        );
        Page<ChatRoom> participantRooms = new PageImpl<>(List.of(participantRoom));

        when(userRepository.findById(testUserIdLong)).thenReturn(Optional.of(currentUser));
        when(chatRoomRepository.searchParticipantRooms(
                currentUser, ChatRoomType.ONE_ON_ONE, "coffee", pageable
        )).thenReturn(participantRooms);

        var result = chatService.searchRooms(
                "  coffee  ", ChatRoomType.ONE_ON_ONE, testUserIdString, pageable
        );

        assertEquals(List.of("participant-room"),
                result.getContent().stream().map(dto -> dto.getId()).toList());
        verify(chatRoomRepository).searchParticipantRooms(
                currentUser, ChatRoomType.ONE_ON_ONE, "coffee", pageable
        );
        verify(chatRoomRepository, never()).findAll(any(Pageable.class));
        verify(chatRoomRepository, never()).findByType(any(ChatRoomType.class), any(Pageable.class));
    }
}
