package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.CreateRoomRequest;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ChatController.class,
        excludeFilters = @ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = com.talkwithneighbors.config.WebConfig.class
        )
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ChatExceptionHandler.class, com.talkwithneighbors.config.TestSecurityConfig.class})
class ChatControllerTest {
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String SESSION_ID = "mock-session-id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MediaStorageService mediaStorageService;

    @MockBean
    private ChatService chatService;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private UserService userService;

    @MockBean
    private SessionValidationService sessionValidationService;

    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    @MockBean
    private MessageRepository messageRepository;

    @MockBean
    private org.springframework.session.SessionRepository<?> sessionRepository;

    private User testUser;
    private CreateRoomRequest createRoomRequest;

    @BeforeEach
    void setUp() {
        UserSession userSession = UserSession.of(1L, "testuser", "test@example.com", "testuser");

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        createRoomRequest = new CreateRoomRequest();
        createRoomRequest.setName("New Room");
        createRoomRequest.setType("GROUP");
        createRoomRequest.setParticipantNicknames(Arrays.asList("otheruser", "anotheruser"));

        when(sessionValidationService.validateSession(SESSION_ID)).thenReturn(userSession);
        when(userService.getUserById(1L)).thenReturn(testUser);
    }

    @Test
    void createRoomSuccess() throws Exception {
        ChatRoomDto roomDto = room("test-room-id", "New Room", ChatRoomType.GROUP, "1");
        when(chatService.createRoom(anyString(), any(ChatRoomType.class), anyString(), any()))
                .thenReturn(roomDto);

        mockMvc.perform(post("/api/chat/rooms")
                        .header(SESSION_HEADER, SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRoomRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-room-id"))
                .andExpect(jsonPath("$.roomName").value("New Room"));
    }

    @Test
    void getRoomsSuccess() throws Exception {
        when(chatService.getChatRoomsForUser(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(room("room-1", "Room 1", ChatRoomType.GROUP, "1"))));

        mockMvc.perform(get("/api/chat/rooms")
                        .header(SESSION_HEADER, SESSION_ID)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("room-1"));
    }

    @Test
    void getRoomSuccess() throws Exception {
        when(chatService.getRoomById(anyString(), anyString()))
                .thenReturn(room("room-1", "Room 1", ChatRoomType.GROUP, "1"));

        mockMvc.perform(get("/api/chat/rooms/{roomId}", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("room-1"));
    }

    @Test
    void joinRoomSuccess() throws Exception {
        doNothing().when(chatService).joinRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    void leaveRoomSuccess() throws Exception {
        when(chatService.getRoomById(anyString(), anyString()))
                .thenReturn(room("room-1", "Room 1", ChatRoomType.GROUP, "2"));
        doNothing().when(chatService).leaveRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    void getMessagesSuccess() throws Exception {
        MessageDto messageDto = new MessageDto();
        messageDto.setId("msg-1");
        messageDto.setContent("Hello");
        when(chatService.getMessagesByRoomId(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(messageDto)));

        mockMvc.perform(get("/api/chat/rooms/{roomId}/messages", "room-1")
                        .header(SESSION_HEADER, SESSION_ID)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("msg-1"));
    }

    @Test
    void postMessageSuccess() throws Exception {
        ChatMessageDto request = new ChatMessageDto();
        request.setContent("Hello");
        MessageDto response = new MessageDto();
        response.setId("msg-1");
        response.setContent("Hello");
        when(chatService.sendMessage(anyString(), anyLong(), anyString())).thenReturn(response);

        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", "room-1")
                        .header(SESSION_HEADER, SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("msg-1"));
    }

    @Test
    void markMessageAsReadSuccess() throws Exception {
        doNothing().when(chatService).markMessageAsRead(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/read", "room-1", "msg-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    void searchGroupRoomsSuccess() throws Exception {
        Page<ChatRoomDto> page = new PageImpl<>(List.of(
                room("room-1", "Group Room 1", ChatRoomType.GROUP, "1"),
                room("room-2", "Group Room 2", ChatRoomType.GROUP, "2")
        ));
        when(chatService.searchRooms(anyString(), eq(ChatRoomType.GROUP), anyString(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/chat/rooms")
                        .header(SESSION_HEADER, SESSION_ID)
                        .param("keyword", "Group")
                        .param("type", "GROUP")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void deleteRoomSuccess() throws Exception {
        when(chatService.getRoomById(eq("room-1"), anyString()))
                .thenReturn(room("room-1", "Room 1", ChatRoomType.GROUP, "1"));
        doNothing().when(chatService).deleteRoom("room-1");

        mockMvc.perform(delete("/api/chat/rooms/{roomId}", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    void createRoomWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/chat/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRoomRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRoomNotFoundReturnsNotFound() throws Exception {
        when(chatService.getRoomById(eq("missing-room"), anyString()))
                .thenThrow(new ChatException("Room not found", HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/api/chat/rooms/{roomId}", "missing-room")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void joinRoomAlreadyJoinedReturnsBadRequest() throws Exception {
        doThrow(new ChatException("Already joined", HttpStatus.BAD_REQUEST))
                .when(chatService).joinRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/join", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leaveRoomNotJoinedReturnsBadRequest() throws Exception {
        when(chatService.getRoomById(anyString(), anyString()))
                .thenReturn(room("room-1", "Room 1", ChatRoomType.GROUP, "2"));
        doThrow(new ChatException("Not a participant", HttpStatus.BAD_REQUEST))
                .when(chatService).leaveRoom(anyString(), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/leave", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markMessageAsReadNotFoundReturnsNotFound() throws Exception {
        doThrow(new ChatException("Message not found", HttpStatus.NOT_FOUND))
                .when(chatService).markMessageAsRead(eq("missing-message"), anyString());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/read", "room-1", "missing-message")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRoomWithoutPermissionReturnsForbidden() throws Exception {
        when(chatService.getRoomById(eq("room-1"), anyString()))
                .thenReturn(room("room-1", "Room 1", ChatRoomType.GROUP, "2"));

        mockMvc.perform(delete("/api/chat/rooms/{roomId}", "room-1")
                        .header(SESSION_HEADER, SESSION_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRoomWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/chat/rooms/{roomId}", "room-1"))
                .andExpect(status().isUnauthorized());
    }

    private ChatRoomDto room(String id, String name, ChatRoomType type, String creatorId) {
        ChatRoomDto dto = new ChatRoomDto();
        dto.setId(id);
        dto.setRoomName(name);
        dto.setType(type);
        dto.setCreatorId(creatorId);
        dto.setParticipantCount(2);
        return dto;
    }
}
