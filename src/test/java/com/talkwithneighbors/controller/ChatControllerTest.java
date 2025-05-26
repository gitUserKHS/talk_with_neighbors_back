package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import com.talkwithneighbors.dto.ChatMessageDto;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.springframework.context.annotation.ComponentScan;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.exception.AuthException;
import org.springframework.session.Session;
import static org.mockito.Mockito.mock;

@WebMvcTest(
    controllers = ChatController.class,
    excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = com.talkwithneighbors.config.WebConfig.class)
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import({MockRedisSessionConfig.class, ChatExceptionHandler.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    @MockBean
    private UserService userService;

    @MockBean
    private SessionValidationService sessionValidationService;

    @MockBean
    private SimpMessagingTemplate messagingTemplate;
    
    @MockBean
    private MessageRepository messageRepository;

    @MockBean
    private org.springframework.session.SessionRepository sessionRepository;

    private UserSession userSession;
    private User testUser;
    private ChatRoom testRoom;
    private CreateRoomRequest createRoomRequest;

    @BeforeEach
    void setUp() {
        userSession = UserSession.of(1L, "testuser", "test@example.com", "testuser");

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        testRoom = new ChatRoom();
        testRoom.setId("test-room-id");
        testRoom.setName("Test Room");
        testRoom.setType(ChatRoomType.GROUP);
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("otheruser");
        otherUser.setEmail("other@example.com");
        testRoom.setCreator(otherUser);

        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setName("New Room");
        createRoomRequest.setType("GROUP");
        createRoomRequest.setParticipantNicknames(Arrays.asList("otheruser", "anotheruser"));

        when(userService.getUserById(1L)).thenReturn(testUser);
        when(sessionValidationService.validateSession(anyString())).thenReturn(userSession);

        Session mockSession = mock(Session.class);
        when(mockSession.getId()).thenReturn("mock-session-id");
        when(sessionRepository.createSession()).thenReturn(mockSession);
        when(sessionRepository.findById(anyString())).thenReturn(mockSession);
    }

    @Test
    @DisplayName("채팅방 생성 성공 테스트")
    void createRoomSuccess() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        when(chatService.createRoom(
                anyString(), any(ChatRoomType.class), anyString(), anyList()))
            .thenReturn(roomDto);

        mockMvc.perform(post("/api/chat/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRoomRequest))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testRoom.getId()))
                .andExpect(jsonPath("$.roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 목록 조회 성공 테스트")
    void getRoomsSuccess() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        Page<ChatRoomDto> page = new PageImpl<>(List.of(roomDto));
        when(chatService.getChatRoomsForUser(anyString(), any(Pageable.class)))
            .thenReturn(page);

        mockMvc.perform(get("/api/chat/rooms")
                .param("page", "0")
                .param("size", "20")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(testRoom.getId()))
                .andExpect(jsonPath("$.content[0].roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 상세 조회 성공 테스트")
    void getRoomSuccess() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        when(chatService.getRoomById(anyString(), anyString()))
            .thenReturn(roomDto);

        mockMvc.perform(get("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testRoom.getId()))
                .andExpect(jsonPath("$.roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 참여 성공 테스트")
    void joinRoomSuccess() throws Exception {
        doNothing().when(chatService).joinRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("채팅방 나가기 성공 테스트")
    void leaveRoomSuccess() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        roomDto.setCreatorId("2"); 
        when(chatService.getRoomById(anyString(), anyString()))
            .thenReturn(roomDto);
        doNothing().when(chatService).leaveRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메시지 목록 조회 성공 테스트")
    void getMessagesSuccess() throws Exception {
        MessageDto messageDto = new MessageDto();
        messageDto.setId("test-message-id");
        messageDto.setContent("Test message");
        
        Page<MessageDto> msgPage = new PageImpl<>(List.of(messageDto));
        when(chatService.getMessagesByRoomId(anyString(), anyString(), any(Pageable.class)))
            .thenReturn(msgPage);

        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", testRoom.getId())
                .param("page", "0")
                .param("size", "20")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("test-message-id"))
                .andExpect(jsonPath("$.content[0].content").value("Test message"));
    }

    @Test
    @DisplayName("메시지 전송 성공 테스트")
    void postMessageSuccess() throws Exception {
        ChatMessageDto requestDto = new ChatMessageDto();
        requestDto.setContent("Hello");
        MessageDto responseDto = new MessageDto();
        responseDto.setId("msg-1");
        responseDto.setContent("Hello");
        when(chatService.sendMessage(anyString(), anyLong(), anyString()))
            .thenReturn(responseDto);

        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", testRoom.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDto))
                .sessionAttr("USER_SESSION", userSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("msg-1"))
            .andExpect(jsonPath("$.content").value("Hello"));
    }

    @Test
    @DisplayName("메시지 읽음 표시 성공 테스트")
    void markMessageAsReadSuccess() throws Exception {
        doNothing().when(chatService).markMessageAsRead(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/read", testRoom.getId(), "msg-1")
                .sessionAttr("USER_SESSION", userSession))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("그룹 채팅방 검색 성공 테스트")
    void searchGroupRoomsSuccess() throws Exception {
        ChatRoomDto roomDto1 = new ChatRoomDto();
        roomDto1.setId("room1");
        roomDto1.setRoomName("Group Room 1");
        roomDto1.setType(ChatRoomType.GROUP);
        roomDto1.setCreatorId("user1");
        roomDto1.setParticipantCount(3);

        ChatRoomDto roomDto2 = new ChatRoomDto();
        roomDto2.setId("room2");
        roomDto2.setRoomName("Another Group Room");
        roomDto2.setType(ChatRoomType.GROUP);
        roomDto2.setCreatorId("user2");
        roomDto2.setParticipantCount(5);

        Page<ChatRoomDto> page = new PageImpl<>(Arrays.asList(roomDto1, roomDto2));
        when(chatService.getChatRoomsForUser(anyString(), any(Pageable.class))).thenReturn(page); 

        mockMvc.perform(get("/api/chat/rooms")
                .param("keyword", "Group") 
                .param("type", "GROUP")   
                .param("page", "0")      
                .param("size", "20")     
                .sessionAttr("USER_SESSION", userSession)) 
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].roomName").value("Group Room 1"));
    }

    @Test
    @DisplayName("채팅방 삭제 성공 테스트")
    void deleteRoomSuccess() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setCreatorId(userSession.getUserId().toString()); 
        when(chatService.getRoomById(eq(testRoom.getId()), eq(userSession.getUserId().toString()))).thenReturn(roomDto);
        doNothing().when(chatService).deleteRoom(testRoom.getId());

        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("채팅방 생성 실패 - 권한 없음 (인증 실패)")
    void createRoomFailNoPermission() throws Exception {
        when(sessionValidationService.validateSession(null))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));
         when(sessionValidationService.validateSession(""))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(post("/api/chat/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRoomRequest)))
                .andExpect(status().isUnauthorized()); 
    }

    @Test
    @DisplayName("채팅방 목록 조회 실패 - 세션 없음")
    void getRoomsFailNoSession() throws Exception {
        when(sessionValidationService.validateSession(null)) 
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));
        when(sessionValidationService.validateSession("")) 
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(get("/api/chat/rooms")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isUnauthorized()); 
    }

    @Test
    @DisplayName("채팅방 상세 조회 실패 - 존재하지 않는 채팅방")
    void getRoomFailNotFound() throws Exception {
        when(chatService.getRoomById(eq("non-existing-room"), anyString()))
            .thenThrow(new ChatException("Room not found", HttpStatus.NOT_FOUND));
        
        mockMvc.perform(get("/api/chat/rooms/{roomId}", "non-existing-room")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("채팅방 참여 실패 - 이미 참여 중")
    void joinRoomFailAlreadyJoined() throws Exception {
        doThrow(new ChatException("Already joined", HttpStatus.BAD_REQUEST))
            .when(chatService).joinRoom(anyString(), anyString());
        
        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("채팅방 나가기 실패 - 참여하지 않은 채팅방")
    void leaveRoomFailNotJoined() throws Exception {
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setCreatorId("2"); 
        when(chatService.getRoomById(anyString(), anyString())).thenReturn(roomDto);
        doThrow(new ChatException("Not a participant", HttpStatus.BAD_REQUEST))
            .when(chatService).leaveRoom(anyString(), anyString());
        
        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("메시지 목록 조회 실패 - 권한 없음 (인증 실패)")
    void getMessagesFailNoPermission() throws Exception {
        when(sessionValidationService.validateSession(null))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));
        when(sessionValidationService.validateSession(""))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", testRoom.getId()))
                .andExpect(status().isUnauthorized()); 
    }

    @Test
    @DisplayName("메시지 읽음 표시 실패 - 존재하지 않는 메시지")
    void markMessageAsReadFailNotFound() throws Exception {
        doThrow(new ChatException("Message not found", HttpStatus.NOT_FOUND))
            .when(chatService).markMessageAsRead(eq("non-existing-message"), anyString());
        
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/read", 
                                testRoom.getId(), "non-existing-message")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("채팅방 삭제 실패 - 권한 없음 (방장이 아닌 경우)")
    void deleteRoomFailNoPermission() throws Exception {
        ChatRoomDto roomDtoFromService = new ChatRoomDto();
        roomDtoFromService.setId(testRoom.getId());
        roomDtoFromService.setCreatorId(testRoom.getCreator().getId().toString()); 
        
        when(chatService.getRoomById(eq(testRoom.getId()), eq(userSession.getUserId().toString())))
            .thenReturn(roomDtoFromService);
        
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden()); 
    }

    @Test
    @DisplayName("채팅방 삭제 실패 - 인증 안됨")
    void deleteRoomFailNoAuth() throws Exception {
        when(sessionValidationService.validateSession(null))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));
        when(sessionValidationService.validateSession(""))
            .thenThrow(new AuthException("세션이 없습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId()))
                .andExpect(status().isUnauthorized()); 
    }
} 