package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.UserService;
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
        when(chatService.createRoom(anyString(), any(User.class), any(ChatRoomType.class), any()))
                .thenReturn(testRoom);

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
        List<ChatRoom> rooms = Arrays.asList(testRoom);
        when(chatService.getRoomsByUser(any(User.class))).thenReturn(rooms);

        // when & then
        mockMvc.perform(get("/api/chat/rooms")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(testRoom.getId()))
                .andExpect(jsonPath("$[0].roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 상세 조회 성공 테스트")
    void getRoomSuccess() throws Exception {
        // given
        when(chatService.getRoom(anyString(), any(User.class))).thenReturn(testRoom);

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
        doNothing().when(chatService).joinRoom(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("채팅방 나가기 성공 테스트")
    void leaveRoomSuccess() throws Exception {
        // given
        when(chatService.getRoom(anyString(), any(User.class))).thenReturn(testRoom);
        doNothing().when(chatService).leaveRoom(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메시지 목록 조회 성공 테스트")
    void getMessagesSuccess() throws Exception {
        // given
        Message message = new Message();
        message.setId("test-message-id");
        message.setContent("Test message");
        message.setSender(testUser);
        message.setChatRoom(testRoom);
        message.setCreatedAt(LocalDateTime.now());
        message.setType(Message.MessageType.TEXT);
        message.setReadByUsers(new java.util.HashSet<>()); // NPE 방지
        message.setUpdatedAt(message.getCreatedAt()); // updatedAt 명확히 지정
        message.setDeleted(false); // isDeleted 명확히 지정

        List<Message> messages = Arrays.asList(message);
        when(chatService.getMessages(anyString(), anyInt(), anyInt())).thenReturn(messages);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", testRoom.getId())
                .param("page", "0")
                .param("size", "20")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(message.getId()))
                .andExpect(jsonPath("$[0].content").value(message.getContent()));
    }

    @Test
    @DisplayName("메시지 읽음 표시 성공 테스트")
    void markMessageAsReadSuccess() throws Exception {
        // given
        String messageId = "test-message-id";
        doNothing().when(chatService).markMessageAsRead(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/read", testRoom.getId())
                .param("messageId", messageId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("그룹 채팅방 검색 성공 테스트")
    void searchGroupRoomsSuccess() throws Exception {
        // given
        List<ChatRoom> rooms = Arrays.asList(testRoom);
        when(chatService.searchGroupRooms(anyString())).thenReturn(rooms);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/search")
                .param("keyword", "test")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(testRoom.getId()))
                .andExpect(jsonPath("$[0].roomName").value(testRoom.getName()));
    }

    @Test
    @DisplayName("채팅방 삭제 성공 테스트")
    void deleteRoomSuccess() throws Exception {
        // given
        when(chatService.deleteRoom(anyString(), any(User.class))).thenReturn(true);

        // when & then
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("채팅방 생성 실패 - 권한 없음")
    void createRoomFailNoPermission() throws Exception {
        // given
        when(chatService.createRoom(anyString(), any(User.class), any(ChatRoomType.class), any()))
                .thenThrow(new ChatException("채팅방을 생성할 권한이 없습니다.", HttpStatus.FORBIDDEN));

        // when & then
        mockMvc.perform(post("/api/chat/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRoomRequest))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("채팅방을 생성할 권한이 없습니다."));
    }

    @Test
    @DisplayName("채팅방 목록 조회 실패 - 세션 없음")
    void getRoomsFailNoSession() throws Exception {
        // given
        when(chatService.getRoomsByUser(any(User.class)))
                .thenThrow(new ChatException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/chat/rooms")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("채팅방 상세 조회 실패 - 존재하지 않는 채팅방")
    void getRoomFailNotFound() throws Exception {
        // given
        when(chatService.getRoom(anyString(), any(User.class)))
                .thenThrow(new ChatException("존재하지 않는 채팅방입니다.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}", "non-existent-room")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 채팅방입니다."));
    }

    @Test
    @DisplayName("채팅방 참여 실패 - 이미 참여 중")
    void joinRoomFailAlreadyJoined() throws Exception {
        // given
        doThrow(new ChatException("이미 참여 중인 채팅방입니다.", HttpStatus.CONFLICT))
                .when(chatService).joinRoom(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 참여 중인 채팅방입니다."));
    }

    @Test
    @DisplayName("채팅방 나가기 실패 - 참여하지 않은 채팅방")
    void leaveRoomFailNotJoined() throws Exception {
        // given
        when(chatService.getRoom(anyString(), any(User.class))).thenReturn(testRoom);
        doThrow(new ChatException("참여하지 않은 채팅방입니다.", HttpStatus.FORBIDDEN))
                .when(chatService).leaveRoom(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("참여하지 않은 채팅방입니다."));
    }

    @Test
    @DisplayName("메시지 목록 조회 실패 - 권한 없음")
    void getMessagesFailNoPermission() throws Exception {
        // given
        when(chatService.getMessages(anyString(), anyInt(), anyInt()))
                .thenThrow(new ChatException("메시지를 조회할 권한이 없습니다.", HttpStatus.FORBIDDEN));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", testRoom.getId())
                .param("page", "0")
                .param("size", "20")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("메시지를 조회할 권한이 없습니다."));
    }

    @Test
    @DisplayName("메시지 읽음 표시 실패 - 존재하지 않는 메시지")
    void markMessageAsReadFailNotFound() throws Exception {
        // given
        String messageId = "non-existent-message-id";
        doThrow(new ChatException("존재하지 않는 메시지입니다.", HttpStatus.NOT_FOUND))
                .when(chatService).markMessageAsRead(anyString(), any(User.class));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/read", testRoom.getId())
                .param("messageId", messageId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 메시지입니다."));
    }

    @Test
    @DisplayName("채팅방 삭제 실패 - 권한 없음")
    void deleteRoomFailNoPermission() throws Exception {
        // given
        when(chatService.deleteRoom(anyString(), any(User.class)))
                .thenThrow(new ChatException("채팅방을 삭제할 권한이 없습니다.", HttpStatus.FORBIDDEN));

        // when & then
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", testRoom.getId())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("채팅방을 삭제할 권한이 없습니다."));
    }
} 