package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.config.TestSecurityConfig;
import com.talkwithneighbors.dto.schedule.ChatScheduleDto;
import com.talkwithneighbors.dto.schedule.ChatScheduleParticipantDto;
import com.talkwithneighbors.dto.schedule.ChatScheduleSummaryDto;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatScheduleService;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.repository.MessageRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChatScheduleController.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, ChatExceptionHandler.class})
class ChatScheduleControllerTest {
    private static final String SESSION_ID = "schedule-session";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ChatScheduleService chatScheduleService;
    @MockBean SessionValidationService sessionValidationService;
    @MockBean org.springframework.session.SessionRepository<?> sessionRepository;
    @MockBean MessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        when(sessionValidationService.validateSession(SESSION_ID))
                .thenReturn(UserSession.of(1L, "host", "host@example.test", "host"));
    }

    @Test
    void createUsesRoomScopedEndpointAndReturnsRawSchedule() throws Exception {
        when(chatScheduleService.create(eq("room-1"), eq(1L), any()))
                .thenReturn(schedule());

        mockMvc.perform(post("/api/chat/rooms/{roomId}/schedules", "room-1")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"저녁 산책",
                                  "description":"같이 걸어요",
                                  "startsAt":"2099-07-18T19:00:00+09:00",
                                  "durationMinutes":90,
                                  "timeZone":"Asia/Seoul",
                                  "location":"서울숲",
                                  "locationAddress":"서울 성동구 뚝섬로 273",
                                  "latitude":37.5444,
                                  "longitude":127.0374,
                                  "kakaoPlaceId":"place-1"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("schedule-1"))
                .andExpect(jsonPath("$.title").value("저녁 산책"))
                .andExpect(jsonPath("$.participants[0].nickname").value("host"))
                .andExpect(jsonPath("$.participants[0].profileImage").value("/profiles/host.webp"))
                .andExpect(jsonPath("$.participants[0].email").doesNotExist())
                .andExpect(jsonPath("$.participants[0].address").doesNotExist());
    }

    @Test
    void createRejectsMissingDurationBeforeService() throws Exception {
        mockMvc.perform(post("/api/chat/rooms/{roomId}/schedules", "room-1")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"저녁 산책",
                                  "startsAt":"2099-07-18T19:00:00+09:00",
                                  "timeZone":"Asia/Seoul"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsAnArrayRatherThanPageEnvelope() throws Exception {
        when(chatScheduleService.list("room-1", 1L)).thenReturn(List.of(schedule()));

        mockMvc.perform(get("/api/chat/rooms/{roomId}/schedules", "room-1")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("schedule-1"));
    }

    @Test
    void listWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/{roomId}/schedules", "room-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWithoutSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/chat/rooms/{roomId}/schedules", "room-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Evening walk",
                                  "startsAt":"2099-07-18T19:00:00+09:00",
                                  "durationMinutes":90,
                                  "timeZone":"Asia/Seoul"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateCancelAndRsvpReturnRawSchedule() throws Exception {
        when(chatScheduleService.update(eq("room-1"), eq("schedule-1"), eq(1L), any()))
                .thenReturn(schedule());
        when(chatScheduleService.cancel(eq("room-1"), eq("schedule-1"), eq(1L), any()))
                .thenReturn(schedule());
        when(chatScheduleService.rsvp(eq("room-1"), eq("schedule-1"), eq(1L), any()))
                .thenReturn(schedule());

        mockMvc.perform(patch("/api/chat/rooms/{roomId}/schedules/{scheduleId}",
                        "room-1", "schedule-1")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("schedule-1"));

        mockMvc.perform(post("/api/chat/rooms/{roomId}/schedules/{scheduleId}/cancel",
                        "room-1", "schedule-1")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("schedule-1"));

        mockMvc.perform(put("/api/chat/rooms/{roomId}/schedules/{scheduleId}/rsvp",
                        "room-1", "schedule-1")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_ATTENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("schedule-1"));
    }

    private Cookie sessionCookie() {
        return new Cookie("TWN_SESSION", SESSION_ID);
    }

    private ChatScheduleDto schedule() {
        Instant now = Instant.parse("2099-07-01T00:00:00Z");
        return new ChatScheduleDto(
                "schedule-1",
                "room-1",
                1L,
                "저녁 산책",
                "같이 걸어요",
                OffsetDateTime.parse("2099-07-18T19:00:00+09:00"),
                90,
                "Asia/Seoul",
                "서울숲",
                "서울 성동구 뚝섬로 273",
                37.5444,
                127.0374,
                "place-1",
                ChatScheduleStatus.SCHEDULED,
                0L,
                ChatScheduleRsvpStatus.ATTENDING,
                new ChatScheduleSummaryDto(1, 0, 1),
                List.of(new ChatScheduleParticipantDto(
                        1L,
                        "host",
                        "/profiles/host.webp",
                        ChatScheduleRsvpStatus.ATTENDING,
                        true)),
                true,
                true,
                now,
                now,
                null);
    }
}
