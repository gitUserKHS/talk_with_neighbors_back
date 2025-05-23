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

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import com.talkwithneighbors.dto.ChatMessageDto;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;

@WebMvcTest(
    controllers = ChatController.class,
    excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = com.talkwithneighbors.config.WebConfig.class)
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import({MockRedisSessionConfig.class, ChatExceptionHandler.class})
@org.springframework.boot.test.mock.mockito.MockBean(com.talkwithneighbors.service.SessionValidationService.class)
@org.springframework.boot.test.mock.mockito.MockBean(org.springframework.session.SessionRepository.class)
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
    private SimpMessagingTemplate messagingTemplate;

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
        // 방장(creator)를 testUser와 다른 User로 지정
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("otheruser");
        otherUser.setEmail("other@example.com");
        testRoom.setCreator(otherUser);

        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setName("New Room");
        createRoomRequest.setType("GROUP");
        createRoomRequest.setParticipantIds(Arrays.asList(2L, 3L));

        when(userService.getUserById(1L)).thenReturn(testUser);
    }

    @Test
    @DisplayName("채팅방 생성 성공 테스트")
    void createRoomSuccess() throws Exception {
        // given
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        when(chatService.createRoom(
                anyString(), any(ChatRoomType.class), anyString(), anyList()))
            .thenReturn(roomDto);

        // when & then
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
        // given
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        Page<ChatRoomDto> page = new PageImpl<>(List.of(roomDto));
        when(chatService.getChatRoomsForUser(anyString(), any(Pageable.class)))
            .thenReturn(page);

        // when & then
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
        // given
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        when(chatService.getRoomById(anyString(), anyString()))
            .thenReturn(roomDto);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testRoom.getId()))
                .andExpect(jsonPath("$.roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 참여 성공 테스트")
    void joinRoomSuccess() throws Exception {
        // given
        doNothing().when(chatService).joinRoom(anyString(), anyString());

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("채팅방 나가기 성공 테스트")
    void leaveRoomSuccess() throws Exception {
        // given
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        roomDto.setCreatorId("2"); // simulate creator is other
        when(chatService.getRoomById(anyString(), anyString()))
            .thenReturn(roomDto);
        doNothing().when(chatService).leaveRoom(anyString(), anyString());

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메시지 목록 조회 성공 테스트")
    void getMessagesSuccess() throws Exception {
        // given
        MessageDto messageDto = new MessageDto();
        messageDto.setId("test-message-id");
        messageDto.setContent("Test message");
        
        Page<MessageDto> msgPage = new PageImpl<>(List.of(messageDto));
        when(chatService.getMessagesByRoomId(anyString(), anyString(), any(Pageable.class)))
            .thenReturn(msgPage);

        // when & then
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
        // given
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setRoomName(testRoom.getName());
        roomDto.setType(testRoom.getType());
        Page<ChatRoomDto> rooms = new PageImpl<>(List.of(roomDto));
        when(chatService.searchRooms(anyString(), any(ChatRoomType.class), anyString(), any(Pageable.class))).thenReturn(rooms);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/search/group")
                .param("query", "Test")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(testRoom.getId()));
    }

    @Test
    @DisplayName("채팅방 삭제 성공 테스트")
    void deleteRoomSuccess() throws Exception {
        // given
        // 방장(creator)를 testUser로 설정하여 삭제 권한 부여
        testRoom.setCreator(testUser);
        ChatRoomDto roomDto = new ChatRoomDto(); // ChatRoomDto 생성
        roomDto.setId(testRoom.getId());
        roomDto.setCreatorId(String.valueOf(testUser.getId())); // creatorId 설정
        when(chatService.getRoomById(anyString(), anyString())).thenReturn(roomDto); // getRoomById Mocking 추가
        doNothing().when(chatService).deleteRoom(anyString());

        // when & then
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("채팅방 생성 실패 - 권한 없음")
    void createRoomFailNoPermission() throws Exception {
        // given
        when(chatService.createRoom(anyString(), any(ChatRoomType.class), anyString(), anyList()))
            .thenThrow(new ChatException("No permission to create room.", HttpStatus.FORBIDDEN));

        // when & then
        mockMvc.perform(post("/api/chat/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRoomRequest))
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("채팅방 목록 조회 실패 - 세션 없음")
    void getRoomsFailNoSession() throws Exception {
        // given
        when(chatService.getChatRoomsForUser(anyString(), any(Pageable.class)))
            .thenThrow(new ChatException("User session not found.", HttpStatus.UNAUTHORIZED));


        // when & then
        mockMvc.perform(get("/api/chat/rooms"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("채팅방 상세 조회 실패 - 존재하지 않는 채팅방")
    void getRoomFailNotFound() throws Exception {
        // given
        when(chatService.getRoomById(anyString(), anyString()))
            .thenThrow(new ChatException("Room not found.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}", "non-existent-room")
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("채팅방 참여 실패 - 이미 참여 중")
    void joinRoomFailAlreadyJoined() throws Exception {
        // given
        doThrow(new ChatException("User already in room.", HttpStatus.CONFLICT))
                .when(chatService).joinRoom(anyString(), anyString());

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("채팅방 나가기 실패 - 참여하지 않은 채팅방")
    void leaveRoomFailNotJoined() throws Exception {
        // given
        // getRoomById가 ChatRoomDto를 반환하도록 설정
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setCreatorId(String.valueOf(testUser.getId() + 1)); // 현재 사용자가 방장이 아니도록 설정
        when(chatService.getRoomById(anyString(), anyString())).thenReturn(roomDto);
    
        doThrow(new ChatException("User not in room.", HttpStatus.BAD_REQUEST))
                .when(chatService).leaveRoom(anyString(), anyString());

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("메시지 목록 조회 실패 - 권한 없음")
    void getMessagesFailNoPermission() throws Exception {
        // given
        when(chatService.getMessagesByRoomId(anyString(), anyString(), any(Pageable.class)))
            .thenThrow(new ChatException("No permission to view messages.", HttpStatus.FORBIDDEN));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", testRoom.getId())
                .param("page", "0")
                .param("size", "20")
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("메시지 읽음 표시 실패 - 존재하지 않는 메시지")
    void markMessageAsReadFailNotFound() throws Exception {
        // given
        doThrow(new ChatException("Message not found.", HttpStatus.NOT_FOUND))
                .when(chatService).markMessageAsRead(anyString(), anyString());

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/read", testRoom.getId(), "non-existent-message")
                .sessionAttr("USER_SESSION", userSession)) // 세션 추가
                .andExpect(status().isNotFound());
    }


    @Test
    @DisplayName("채팅방 삭제 실패 - 권한 없음")
    void deleteRoomFailNoPermission() throws Exception {
        // given
        // 방장(creator)를 testUser와 다른 User로 설정하여 삭제 권한이 없도록 함
        User otherUser = new User();
        otherUser.setId(testUser.getId() + 1); // 다른 ID로 설정
        testRoom.setCreator(otherUser);
    
        ChatRoomDto roomDto = new ChatRoomDto();
        roomDto.setId(testRoom.getId());
        roomDto.setCreatorId(String.valueOf(otherUser.getId())); // 다른 사용자를 방장으로 설정
    
        when(chatService.getRoomById(anyString(), anyString())).thenReturn(roomDto);
        doThrow(new ChatException("No permission to delete room.", HttpStatus.FORBIDDEN))
            .when(chatService).deleteRoom(anyString());
    
        // when & then
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
            .sessionAttr("USER_SESSION", userSession))
            .andExpect(status().isForbidden());
    }
} 