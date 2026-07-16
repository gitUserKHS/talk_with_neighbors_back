package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.UpdateChatRoomRequest;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.MessageAttachment;
import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.domain.event.ChatMessageCommittedEvent;
import com.talkwithneighbors.domain.event.ChatMessageChangedEvent;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @InjectMocks
    private ChatServiceImpl chatService;

    private User testUser;
    private ChatRoom testChatRoom;
    private Message testMessage;
    private CreateRoomRequest createRoomRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        // 참여자 User 객체 생성 (테스트 상황에 맞게 설정)
        User participantUser = new User();
        participantUser.setId(2L);
        participantUser.setUsername("participantUser"); 
        participantUser.setEmail("participant@example.com");

        testChatRoom = new ChatRoom();
        testChatRoom.setId(UUID.randomUUID().toString());
        testChatRoom.setName("Test Room"); // 1:1 채팅 시 이 이름은 덮어쓰여짐
        testChatRoom.setType(ChatRoomType.ONE_ON_ONE);
        testChatRoom.setCreator(testUser);
        testChatRoom.getParticipants().add(testUser);
        testChatRoom.getParticipants().add(participantUser);
        // 필요하다면 testChatRoom의 participants에도 participantUser 추가
        // testChatRoom.getParticipants().add(testUser);
        // testChatRoom.getParticipants().add(participantUser);

        testMessage = new Message();
        testMessage.setId(UUID.randomUUID().toString());
        testMessage.setContent("Test message");
        testMessage.setSender(testUser);
        testMessage.setChatRoom(testChatRoom);
        testMessage.setType(Message.MessageType.TEXT);
        testMessage.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        testMessage.setUpdatedAt(testMessage.getCreatedAt());

        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setName("Test Room"); // 1:1 채팅 시 이 값은 무시될 수 있음
        createRoomRequest.setType("ONE_ON_ONE");
        createRoomRequest.setParticipantNicknames(Arrays.asList("participantUser")); // 닉네임(사용자명)으로 변경
    }

    @Test
    @DisplayName("채팅방 생성 성공 테스트")
    void createRoomSuccess() {
        // given
        User participant = new User();
        participant.setId(2L);
        participant.setUsername("participantUser"); // 사용자명 설정
        // findAllByUsernameIn을 모킹하도록 변경
        when(userRepository.findAllByUsernameIn(Arrays.asList("participantUser"))).thenReturn(Arrays.asList(participant));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser)); // creator 조회 모킹 추가
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom roomToSave = invocation.getArgument(0);
            // 1:1 채팅방 이름 자동 생성 로직에 따라 이름이 "testuser, participantUser" (알파벳순)가 될 것을 예상
            // 실제 저장되는 ChatRoom 객체를 반환하거나, 테스트의 단위를 위해 ID만 설정된 객체 반환 가능
            if (roomToSave.getType() == ChatRoomType.ONE_ON_ONE) {
                 List<String> names = Arrays.asList(testUser.getUsername(), participant.getUsername());
                 names.sort(String::compareToIgnoreCase);
                 roomToSave.setName(String.join(", ", names));
            }
            roomToSave.setId(testChatRoom.getId()); // ID는 유지
            return roomToSave; 
        });

        // when
        ChatRoomDto createdRoomDto = chatService.createRoom(
            createRoomRequest.getName(), // 1:1의 경우 이 값은 서비스 내부에서 덮어쓰여질 수 있음
            createRoomRequest.getTypeEnum(),
            testUser.getId().toString(),
            createRoomRequest.getParticipantNicknames() // 닉네임(사용자명) 사용
        );

        // then
        assertNotNull(createdRoomDto);
        // 1:1 채팅방 이름 자동 생성 로직 검증
        List<String> expectedNames = Arrays.asList(testUser.getUsername(), "participantUser");
        expectedNames.sort(String::compareToIgnoreCase);
        assertEquals(String.join(", ", expectedNames), createdRoomDto.getRoomName());
        assertEquals(testChatRoom.getType(), createdRoomDto.getType());
        assertEquals(testUser.getId().toString(), createdRoomDto.getCreatorId());
    }

    @Test
    @DisplayName("사용자의 채팅방 목록 조회 성공 테스트")
    void getChatRoomsForUserSuccess() {
        // given
        Page<ChatRoom> chatRoomPage = new org.springframework.data.domain.PageImpl<>(List.of(testChatRoom));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(chatRoomRepository.findByParticipantsContainingOrderByLastMessageTimeDesc(any(User.class), any()))
            .thenReturn(chatRoomPage);

        // when
        Page<ChatRoomDto> dtoPage = chatService.getChatRoomsForUser(
            testUser.getId().toString(), org.springframework.data.domain.PageRequest.of(0, 10));

        // then
        assertNotNull(dtoPage);
        assertEquals(1, dtoPage.getTotalElements());
        assertEquals(testChatRoom.getName(), dtoPage.getContent().get(0).getRoomName());
    }

    @Test
    void firstRoomListRepairsHiddenSchedulePreviewBeforePaging() {
        LocalDateTime scheduleCardTime = LocalDateTime.of(2026, 7, 16, 12, 0);
        Message visibleMessage = new Message();
        visibleMessage.setId("visible-message");
        visibleMessage.setChatRoom(testChatRoom);
        visibleMessage.setSender(testUser);
        visibleMessage.setType(Message.MessageType.TEXT);
        visibleMessage.setContent("Visible chat message");
        visibleMessage.setCreatedAt(scheduleCardTime.minusMinutes(5));
        testChatRoom.setLastMessage("Schedule: hidden card");
        testChatRoom.setLastMessageTime(scheduleCardTime);
        Page<ChatRoom> chatRoomPage = new org.springframework.data.domain.PageImpl<>(
                List.of(testChatRoom));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);

        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(messageRepository.findParticipantRoomIdsWithSchedulePreview(
                testUser, Message.MessageType.SCHEDULE))
                .thenReturn(List.of(testChatRoom.getId()));
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId()))
                .thenReturn(Optional.of(testChatRoom));
        when(messageRepository.existsByChatRoom_IdAndTypeAndCreatedAt(
                testChatRoom.getId(), Message.MessageType.SCHEDULE, scheduleCardTime))
                .thenReturn(true);
        when(messageRepository.findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                eq(testChatRoom.getId()), eq(Message.MessageType.SCHEDULE), any()))
                .thenReturn(List.of(visibleMessage));
        when(chatRoomRepository.findByParticipantsContainingOrderByLastMessageTimeDesc(
                testUser, pageable)).thenReturn(chatRoomPage);

        Page<ChatRoomDto> result = chatService.getChatRoomsForUser(
                testUser.getId().toString(), pageable);

        assertEquals("Visible chat message", result.getContent().get(0).getLastMessage());
        assertEquals(visibleMessage.getCreatedAt(),
                LocalDateTime.parse(result.getContent().get(0).getLastMessageTime()));
        org.mockito.InOrder order = inOrder(chatRoomRepository);
        order.verify(chatRoomRepository).save(testChatRoom);
        order.verify(chatRoomRepository)
                .findByParticipantsContainingOrderByLastMessageTimeDesc(testUser, pageable);
    }

    @Test
    @DisplayName("채팅방 메시지 전송 성공 테스트")
    void sendMessageSuccess() {
        // given
        ChatMessageDto messageDto = new ChatMessageDto();
        messageDto.setRoomId(testChatRoom.getId());
        messageDto.setSenderId(testUser.getId());
        messageDto.setContent("Test message");
        
        when(chatRoomRepository.findByIdForUpdate(anyString())).thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(messageRepository.save(any())).thenReturn(testMessage);

        // when
        MessageDto savedDto = chatService.sendMessage(
            testChatRoom.getId(), testUser.getId(), messageDto.getContent());

        // then
        assertNotNull(savedDto);
        assertEquals(testMessage.getContent(), savedDto.getContent());
        assertEquals(testUser.getId().toString(), savedDto.getSenderId());
        verify(applicationEventPublisher).publishEvent(any(ChatMessageCommittedEvent.class));
        verifyNoInteractions(messagingTemplate, notificationService);
    }

    @Test
    @DisplayName("첨부 파일만 있는 메시지도 저장하고 커밋 이벤트에 메타데이터를 포함한다")
    void sendAttachmentOnlyMessageSuccess() {
        MessageAttachment attachment = new MessageAttachment(
                "/uploads/chat/video.mp4",
                "/uploads/chat/video-thumbnail.webp",
                ChatAttachmentType.VIDEO,
                "video/mp4",
                "동영상.mp4",
                1024L,
                640,
                360,
                1.0
        );
        when(chatRoomRepository.findByIdForUpdate(anyString())).thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageDto saved = chatService.sendMessage(
                testChatRoom.getId(), testUser.getId(), "", List.of(attachment));

        assertEquals(Message.MessageType.VIDEO, saved.getType());
        assertEquals(1, saved.getAttachments().size());
        assertEquals("video/mp4", saved.getAttachments().get(0).contentType());
        assertEquals("동영상", testChatRoom.getLastMessage());
        verify(applicationEventPublisher).publishEvent(any(ChatMessageCommittedEvent.class));
    }

    @Test
    @DisplayName("존재하지 않는 채팅방에 메시지 전송 실패 테스트")
    void sendMessageFailWithNonExistentRoom() {
        // given
        ChatMessageDto messageDto = new ChatMessageDto();
        messageDto.setRoomId("non-existent-room");
        messageDto.setSenderId(testUser.getId());
        messageDto.setContent("Test message");
        
        when(chatRoomRepository.findByIdForUpdate(anyString())).thenReturn(Optional.empty());

        // when & then
        assertThrows(RuntimeException.class, () -> chatService.sendMessage(
            messageDto.getRoomId(), messageDto.getSenderId(), messageDto.getContent()));
    }

    @Test
    void senderCanUpdateOwnMessageAndRoomPreview() {
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(messageRepository.findById(testMessage.getId())).thenReturn(Optional.of(testMessage));
        when(messageRepository.save(testMessage)).thenReturn(testMessage);
        when(messageRepository.findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                eq(testChatRoom.getId()), eq(Message.MessageType.SCHEDULE), any()))
                .thenReturn(List.of(testMessage));

        MessageDto result = chatService.updateMessage(
                testChatRoom.getId(), testMessage.getId(), testUser.getId(), "  수정한 메시지  ");

        assertEquals("수정한 메시지", result.getContent());
        assertNotNull(result.getEditedAt());
        assertEquals("수정한 메시지", testChatRoom.getLastMessage());
        verify(applicationEventPublisher).publishEvent(any(ChatMessageChangedEvent.class));
    }

    @Test
    void participantCannotUpdateAnotherUsersMessage() {
        User otherParticipant = testChatRoom.getParticipants().stream()
                .filter(user -> !user.getId().equals(testUser.getId()))
                .findFirst()
                .orElseThrow();
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(messageRepository.findById(testMessage.getId())).thenReturn(Optional.of(testMessage));

        ChatException exception = assertThrows(ChatException.class, () -> chatService.updateMessage(
                testChatRoom.getId(), testMessage.getId(), otherParticipant.getId(), "가로채기"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void senderCanDeleteAttachmentMessageAndMediaIsCleanedAfterCommit() {
        MessageAttachment attachment = new MessageAttachment(
                "/uploads/chat/photo.webp",
                "/uploads/chat/photo-thumbnail.webp",
                ChatAttachmentType.IMAGE,
                "image/webp",
                "photo.jpg",
                128L,
                100,
                100,
                null
        );
        testMessage.setAttachments(new java.util.ArrayList<>(List.of(attachment)));
        testMessage.setType(Message.MessageType.IMAGE);
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(messageRepository.findById(testMessage.getId())).thenReturn(Optional.of(testMessage));
        when(messageRepository.save(testMessage)).thenReturn(testMessage);
        when(messageRepository.findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                eq(testChatRoom.getId()), eq(Message.MessageType.SCHEDULE), any()))
                .thenReturn(List.of());

        MessageDto result = chatService.deleteMessage(
                testChatRoom.getId(), testMessage.getId(), testUser.getId());

        assertTrue(result.isDeleted());
        assertEquals("", result.getContent());
        assertTrue(result.getAttachments().isEmpty());
        assertNotNull(result.getDeletedAt());
        assertNull(testChatRoom.getLastMessage());
        verify(applicationEventPublisher).publishEvent(any(ChatMessageChangedEvent.class));
        verify(domainEventPublisher).publish(argThat(event ->
                event instanceof MediaFilesDeletedEvent deletedEvent
                        && deletedEvent.mediaUrls().contains("/uploads/chat/photo.webp")
                        && deletedEvent.mediaUrls().contains("/uploads/chat/photo-thumbnail.webp")));
    }

    @Test
    void deletedMessageCannotBeUpdated() {
        testMessage.setDeleted(true);
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(messageRepository.findById(testMessage.getId())).thenReturn(Optional.of(testMessage));

        ChatException exception = assertThrows(ChatException.class, () -> chatService.updateMessage(
                testChatRoom.getId(), testMessage.getId(), testUser.getId(), "되살리기"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    @DisplayName("채팅방 메시지 목록 조회 성공 테스트")
    void getMessagesSuccess() {
        // given
        Page<Message> messagePage = new org.springframework.data.domain.PageImpl<>(List.of(testMessage));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(chatRoomRepository.findById(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(messageRepository.findVisibleByChatRoomIdOrderByCreatedAtDesc(
                anyString(), eq(Message.MessageType.SCHEDULE), any()))
            .thenReturn(messagePage);

        // when
        Page<MessageDto> dtoPage = chatService.getMessagesByRoomId(
            testChatRoom.getId(), testUser.getId().toString(), org.springframework.data.domain.PageRequest.of(0, 10));

        // then
        assertNotNull(dtoPage);
        assertEquals(1, dtoPage.getContent().size());
        assertEquals(testMessage.getContent(), dtoPage.getContent().get(0).getContent());
    }

    @Test
    void roomCreatorCanUpdateAndCloseRoom() {
        UpdateChatRoomRequest request = new UpdateChatRoomRequest();
        request.setTitle("Updated room");
        request.setStatus(ChatRoomStatus.CLOSED);
        request.setMaxMembers(4);
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(chatRoomRepository.save(testChatRoom)).thenReturn(testChatRoom);

        ChatRoomDto result = chatService.updateRoom(testChatRoom.getId(), testUser.getId(), request);

        assertEquals("Updated room", result.getRoomName());
        assertEquals(ChatRoomStatus.CLOSED, result.getStatus());
        assertEquals(4, result.getMaxParticipants());
    }

    @Test
    void nonCreatorCannotUpdateRoom() {
        User nonCreator = new User();
        nonCreator.setId(99L);
        UpdateChatRoomRequest request = new UpdateChatRoomRequest();
        request.setTitle("Blocked update");
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId())).thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(nonCreator.getId())).thenReturn(Optional.of(nonCreator));

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.updateRoom(testChatRoom.getId(), nonCreator.getId(), request));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }

    @Test
    void genericPatchCannotChangePublicMeetupCapacity() {
        testChatRoom.setType(ChatRoomType.GROUP);
        testChatRoom.setPublicRoom(true);
        UpdateChatRoomRequest request = new UpdateChatRoomRequest();
        request.setMaxParticipants(8);
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId()))
                .thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.updateRoom(testChatRoom.getId(), testUser.getId(), request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }

    @Test
    void genericPatchCannotChangeRoomType() {
        UpdateChatRoomRequest request = new UpdateChatRoomRequest();
        request.setType(ChatRoomType.GROUP);
        when(chatRoomRepository.findByIdForUpdate(testChatRoom.getId()))
                .thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        ChatException exception = assertThrows(ChatException.class,
                () -> chatService.updateRoom(testChatRoom.getId(), testUser.getId(), request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }
}
