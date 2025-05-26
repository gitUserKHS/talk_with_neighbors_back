package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.LocationDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.context.annotation.Import;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;

@SpringBootTest
@WithMockUser(username = "testuser", roles = {"USER"})
public class MatchingControllerTest {
    @MockBean
    private com.talkwithneighbors.security.AuthInterceptor authInterceptor;





    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MatchingService matchingService;

    @MockBean
    private com.talkwithneighbors.service.SessionValidationService sessionValidationService;
    private MatchingPreferencesDto preferencesDto;

    @BeforeEach
    void setUp() {
        Mockito.when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        Mockito.when(sessionValidationService.validateSession(any())).thenReturn(
            new com.talkwithneighbors.security.UserSession(1L, "testuser", "test@test.com", "testnick")
        );

        MatchingController controller = context.getBean(MatchingController.class);

        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.talkwithneighbors.exception.GlobalExceptionHandler())
            .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                    System.out.println("[StandaloneArgumentResolver] supportsParameter: " + parameter.getParameterType());
                    return parameter.getParameterType().equals(com.talkwithneighbors.security.UserSession.class);
                }
                @Override
                public Object resolveArgument(org.springframework.core.MethodParameter parameter, org.springframework.web.method.support.ModelAndViewContainer mavContainer,
                                             org.springframework.web.context.request.NativeWebRequest webRequest, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                    System.out.println("[StandaloneArgumentResolver] resolveArgument called");
                    return new com.talkwithneighbors.security.UserSession(1L, "testuser", "test@test.com", "testnick");
                }
            })
            .build();

        preferencesDto = new MatchingPreferencesDto();
        preferencesDto.setMaxDistance(5.0);
        preferencesDto.setAgeRange(new Integer[]{20, 30});
        preferencesDto.setGender("F");
    }

    @Test
    @DisplayName("매칭 선호도 저장 성공 테스트")
    void saveMatchingPreferencesSuccess() throws Exception {
        // given
        doNothing().when(matchingService).saveMatchingPreferences(any(MatchingPreferencesDto.class), anyLong());

        // when & then
        mockMvc.perform(post("/api/matching/preferences")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                )
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                )
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                )
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(preferencesDto))
                )
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
                .with(csrf())
                )
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
                .with(csrf())
                )
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
        var result = mockMvc.perform(post("/api/matching/{matchId}/accept", matchId)
                .with(csrf()))
                .andReturn();
        System.out.println("[acceptMatchSuccess] 응답 본문: " + result.getResponse().getContentAsString());
        System.out.println("[acceptMatchSuccess] 응답 상태: " + result.getResponse().getStatus());
        // 실제로 호출됐는지 검증
        org.mockito.Mockito.verify(matchingService, org.mockito.Mockito.times(1)).acceptMatch(anyString(), anyLong());
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus());
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
                .with(csrf())
                )
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
        var result = mockMvc.perform(post("/api/matching/{matchId}/reject", matchId)
                .with(csrf())
                )
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            System.out.println("[rejectMatchSuccess] 응답 본문: " + result.getResponse().getContentAsString());
            System.out.println("[rejectMatchSuccess] 응답 상태: " + result.getResponse().getStatus());
        }
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus());
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
                .with(csrf())
                )
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
                )
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
                )
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
                )
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
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("매칭을 처리할 권한이 없습니다."));
    }
} 