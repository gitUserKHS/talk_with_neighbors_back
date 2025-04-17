package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.LocationDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MatchingController.class)
class MatchingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MatchingService matchingService;

    private MatchingPreferencesDto preferencesDto;
    private UserSession userSession;

    @BeforeEach
    void setUp() {
        LocationDto locationDto = new LocationDto();
        locationDto.setLatitude(37.5665);
        locationDto.setLongitude(126.9780);
        locationDto.setAddress("서울시 중구");

        preferencesDto = new MatchingPreferencesDto();
        preferencesDto.setLocation(locationDto);
        preferencesDto.setMaxDistance(5.0);
        preferencesDto.setAgeRange(new Integer[]{20, 30});
        preferencesDto.setGender("F");

        userSession = UserSession.of(1L, "testuser", "test@example.com", "testuser");
    }

    @Test
    @DisplayName("매칭 선호도 저장 성공 테스트")
    void saveMatchingPreferencesSuccess() throws Exception {
        // given
        doNothing().when(matchingService).saveMatchingPreferences(any(MatchingPreferencesDto.class), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매칭 선호도 저장 실패 - 잘못된 위치 정보")
    void saveMatchingPreferencesFailInvalidLocation() throws Exception {
        // given
        doThrow(new MatchingException("잘못된 위치 정보입니다.", HttpStatus.BAD_REQUEST))
                .when(matchingService).saveMatchingPreferences(any(MatchingPreferencesDto.class), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 위치 정보입니다."));
    }

    @Test
    @DisplayName("매칭 시작 성공 테스트")
    void startMatchingSuccess() throws Exception {
        // given
        doNothing().when(matchingService).startMatching(any(MatchingPreferencesDto.class), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매칭 시작 실패 - 이미 매칭 중")
    void startMatchingFailAlreadyMatching() throws Exception {
        // given
        doThrow(new MatchingException("이미 매칭 중입니다.", HttpStatus.CONFLICT))
                .when(matchingService).startMatching(any(MatchingPreferencesDto.class), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 매칭 중입니다."));
    }

    @Test
    @DisplayName("매칭 중지 성공 테스트")
    void stopMatchingSuccess() throws Exception {
        // given
        doNothing().when(matchingService).stopMatching(anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/stop")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매칭 중지 실패 - 매칭 중이 아님")
    void stopMatchingFailNotMatching() throws Exception {
        // given
        doThrow(new MatchingException("매칭 중이 아닙니다.", HttpStatus.BAD_REQUEST))
                .when(matchingService).stopMatching(anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/stop")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("매칭 중이 아닙니다."));
    }

    @Test
    @DisplayName("매칭 수락 성공 테스트")
    void acceptMatchSuccess() throws Exception {
        // given
        String matchId = "test-match-id";
        doNothing().when(matchingService).acceptMatch(anyString(), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/{matchId}/accept", matchId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매칭 수락 실패 - 존재하지 않는 매칭")
    void acceptMatchFailNotFound() throws Exception {
        // given
        String matchId = "non-existent-match-id";
        doThrow(new MatchingException("존재하지 않는 매칭입니다.", HttpStatus.NOT_FOUND))
                .when(matchingService).acceptMatch(anyString(), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/{matchId}/accept", matchId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 매칭입니다."));
    }

    @Test
    @DisplayName("매칭 거절 성공 테스트")
    void rejectMatchSuccess() throws Exception {
        // given
        String matchId = "test-match-id";
        doNothing().when(matchingService).rejectMatch(anyString(), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/{matchId}/reject", matchId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("매칭 거절 실패 - 이미 처리된 매칭")
    void rejectMatchFailAlreadyProcessed() throws Exception {
        // given
        String matchId = "test-match-id";
        doThrow(new MatchingException("이미 처리된 매칭입니다.", HttpStatus.CONFLICT))
                .when(matchingService).rejectMatch(anyString(), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/{matchId}/reject", matchId)
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 처리된 매칭입니다."));
    }

    @Test
    @DisplayName("근처 사용자 검색 성공 테스트")
    void searchNearbyUsersSuccess() throws Exception {
        // given
        Double latitude = 37.5665;
        Double longitude = 126.9780;
        Double radius = 5.0;

        MatchProfileDto profile1 = new MatchProfileDto();
        profile1.setId("2");
        profile1.setUsername("user2");
        profile1.setAge("25");
        profile1.setGender("M");
        profile1.setBio("안녕하세요");
        LocationDto location1 = new LocationDto();
        location1.setLatitude(37.5660);
        location1.setLongitude(126.9775);
        profile1.setLocation(location1);
        profile1.setDistance(0.5);

        MatchProfileDto profile2 = new MatchProfileDto();
        profile2.setId("3");
        profile2.setUsername("user3");
        profile2.setAge("28");
        profile2.setGender("F");
        profile2.setBio("반갑습니다");
        LocationDto location2 = new LocationDto();
        location2.setLatitude(37.5670);
        location2.setLongitude(126.9785);
        profile2.setLocation(location2);
        profile2.setDistance(0.8);

        List<MatchProfileDto> nearbyUsers = Arrays.asList(profile1, profile2);

        when(matchingService.searchNearbyUsers(anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(nearbyUsers);

        // when & then
        mockMvc.perform(get("/api/matching/nearby")
                .param("latitude", latitude.toString())
                .param("longitude", longitude.toString())
                .param("radius", radius.toString())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value("2"))
                .andExpect(jsonPath("$[1].id").value("3"));
    }

    @Test
    @DisplayName("근처 사용자 검색 실패 - 잘못된 위치 정보")
    void searchNearbyUsersFailInvalidLocation() throws Exception {
        // given
        Double latitude = 37.5665;
        Double longitude = 126.9780;
        Double radius = 5.0;

        when(matchingService.searchNearbyUsers(anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new MatchingException("잘못된 위치 정보입니다.", HttpStatus.BAD_REQUEST));

        // when & then
        mockMvc.perform(get("/api/matching/nearby")
                .param("latitude", latitude.toString())
                .param("longitude", longitude.toString())
                .param("radius", radius.toString())
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 위치 정보입니다."));
    }

    @Test
    @DisplayName("대기 중인 매칭 처리 성공 테스트")
    void processPendingMatchesSuccess() throws Exception {
        // given
        doNothing().when(matchingService).processPendingMatches(anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/process-pending")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("대기 중인 매칭 처리 실패 - 권한 없음")
    void processPendingMatchesFailNoPermission() throws Exception {
        // given
        doThrow(new MatchingException("매칭을 처리할 권한이 없습니다.", HttpStatus.FORBIDDEN))
                .when(matchingService).processPendingMatches(anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/process-pending")
                .sessionAttr("USER_SESSION", userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("매칭을 처리할 권한이 없습니다."));
    }
} 