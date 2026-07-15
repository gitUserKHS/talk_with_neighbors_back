package com.talkwithneighbors.controller;

import com.talkwithneighbors.config.TestSecurityConfig;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.dto.meetup.HobbyMeetupParticipantDto;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.HobbyMeetupService;
import com.talkwithneighbors.service.SessionValidationService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HobbyMeetupController.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, ChatExceptionHandler.class})
class HobbyMeetupControllerTest {
    private static final String SESSION_ID = "meetup-session";

    @Autowired MockMvc mockMvc;
    @MockBean HobbyMeetupService hobbyMeetupService;
    @MockBean SessionValidationService sessionValidationService;
    @MockBean org.springframework.session.SessionRepository<?> sessionRepository;
    @MockBean MessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        when(sessionValidationService.validateSession(SESSION_ID))
                .thenReturn(UserSession.of(1L, "host", "host@example.test", "host"));
    }

    @Test
    void detailReturnsManagementFlagAndParticipantSummaries() throws Exception {
        when(hobbyMeetupService.getMeetup(1L, "meetup-1")).thenReturn(meetup());

        mockMvc.perform(get("/api/meetups/{roomId}", "meetup-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creatorId").value(1))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.participants[0].nickname").value("host"))
                .andExpect(jsonPath("$.participants[0].profileImageUrl").value("/profile/host.webp"))
                .andExpect(jsonPath("$.participants[0].host").value(true));
    }

    @Test
    void patchPassesValidatedFullMeetupFormToService() throws Exception {
        when(hobbyMeetupService.updateMeetup(eq(1L), eq("meetup-1"), any()))
                .thenReturn(meetup());

        mockMvc.perform(patch("/api/meetups/{roomId}", "meetup-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Updated meetup",
                                  "description":"Together",
                                  "interestTags":["walk"],
                                  "location":"Seoul Forest",
                                  "locationAddress":"Seoul",
                                  "latitude":37.5,
                                  "longitude":127.0,
                                  "maxParticipants":8,
                                  "scheduledAt":"2099-08-01T19:00:00+09:00",
                                  "durationMinutes":120,
                                  "registrationDeadline":"2099-08-01T18:00:00+09:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("meetup-1"));

        verify(hobbyMeetupService).updateMeetup(eq(1L), eq("meetup-1"), any());
    }

    @Test
    void deleteUsesAuthenticatedHost() throws Exception {
        mockMvc.perform(delete("/api/meetups/{roomId}", "meetup-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID)))
                .andExpect(status().isNoContent());

        verify(hobbyMeetupService).deleteMeetup(1L, "meetup-1");
    }

    private HobbyMeetupDto meetup() {
        HobbyMeetupDto dto = new HobbyMeetupDto();
        dto.setRoomId("meetup-1");
        dto.setCreatorId(1L);
        dto.setCanManage(true);
        dto.setParticipants(List.of(new HobbyMeetupParticipantDto(
                1L, "host", "/profile/host.webp", true)));
        return dto;
    }
}
