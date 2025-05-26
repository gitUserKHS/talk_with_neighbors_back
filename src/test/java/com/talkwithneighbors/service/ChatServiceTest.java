package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.domain.Page;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        // 필요하다면 testChatRoom의 participants에도 participantUser 추가
        // testChatRoom.getParticipants().add(testUser);
        // testChatRoom.getParticipants().add(participantUser);

        testMessage = new Message();
        testMessage.setId(UUID.randomUUID().toString());
        testMessage.setContent("Test message");
        testMessage.setSender(testUser);
        testMessage.setChatRoom(testChatRoom);

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
        when(chatRoomRepository.findByParticipantsContaining(any(User.class), any()))
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
    @DisplayName("채팅방 메시지 전송 성공 테스트")
    void sendMessageSuccess() {
        // given
        ChatMessageDto messageDto = new ChatMessageDto();
        messageDto.setRoomId(testChatRoom.getId());
        messageDto.setSenderId(testUser.getId());
        messageDto.setContent("Test message");
        
        when(chatRoomRepository.findById(anyString())).thenReturn(Optional.of(testChatRoom));
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(messageRepository.save(any())).thenReturn(testMessage);
        
        doNothing().when(messagingTemplate).convertAndSend(anyString(), any(com.talkwithneighbors.dto.MessageDto.class));

        // when
        MessageDto savedDto = chatService.sendMessage(
            testChatRoom.getId(), testUser.getId(), messageDto.getContent());

        // then
        assertNotNull(savedDto);
        assertEquals(testMessage.getContent(), savedDto.getContent());
        assertEquals(testUser.getId().toString(), savedDto.getSenderId());
    }

    @Test
    @DisplayName("존재하지 않는 채팅방에 메시지 전송 실패 테스트")
    void sendMessageFailWithNonExistentRoom() {
        // given
        ChatMessageDto messageDto = new ChatMessageDto();
        messageDto.setRoomId("non-existent-room");
        messageDto.setSenderId(testUser.getId());
        messageDto.setContent("Test message");
        
        when(chatRoomRepository.findById(anyString())).thenReturn(Optional.empty());

        // when & then
        assertThrows(RuntimeException.class, () -> chatService.sendMessage(
            messageDto.getRoomId(), messageDto.getSenderId(), messageDto.getContent()));
    }

    @Test
    @DisplayName("채팅방 메시지 목록 조회 성공 테스트")
    void getMessagesSuccess() {
        // given
        Page<Message> messagePage = new org.springframework.data.domain.PageImpl<>(List.of(testMessage));
        when(messageRepository.findByChatRoomIdOrderByCreatedAtDesc(anyString(), any()))
            .thenReturn(messagePage);

        // when
        Page<MessageDto> dtoPage = chatService.getMessagesByRoomId(
            testChatRoom.getId(), testUser.getId().toString(), org.springframework.data.domain.PageRequest.of(0, 10));

        // then
        assertNotNull(dtoPage);
        assertEquals(1, dtoPage.getContent().size());
        assertEquals(testMessage.getContent(), dtoPage.getContent().get(0).getContent());
    }
} 