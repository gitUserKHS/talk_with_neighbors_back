package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.LocationDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MatchingPreferencesRepository matchingPreferencesRepository;

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RedisSessionService redisSessionService;

    @InjectMocks
    private MatchingService matchingService;

    private User testUser;
    private User testUser2;
    private MatchingPreferences testPreferences;
    private MatchingPreferencesDto testPreferencesDto;
    private MatchProfileDto testMatchProfile;
    private Match testMatch;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testUser");
        testUser.setLatitude(37.5665);
        testUser.setLongitude(126.9780);

        testUser2 = new User();
        testUser2.setId(2L);
        testUser2.setUsername("testUser2");
        testUser2.setLatitude(37.5668);
        testUser2.setLongitude(126.9783);

        testPreferences = new MatchingPreferences();
        testPreferences.setId(1L);
        testPreferences.setUser(testUser);
        testPreferences.setMaxDistance(10.0);
        testPreferences.setMinAge(20);
        testPreferences.setMaxAge(30);

        // 위치 정보 초기화
        LocationDto locationDto = new LocationDto();
        locationDto.setLatitude(37.5665);
        locationDto.setLongitude(126.9780);
        locationDto.setAddress("서울시 중구");

        testPreferencesDto = new MatchingPreferencesDto();
        testPreferencesDto.setMaxDistance(10.0);
        testPreferencesDto.setAgeRange(new Integer[]{20, 30});
        testPreferencesDto.setLocation(locationDto); // 위치 정보 설정

        testMatchProfile = new MatchProfileDto();
        testMatchProfile.setId("2");
        testMatchProfile.setUsername("user2");
        testMatchProfile.setAge("25");
        testMatchProfile.setGender("M");
        testMatchProfile.setBio("안녕하세요");

        testMatch = new Match();
        testMatch.setId(UUID.randomUUID().toString());
        testMatch.setUser1(testUser);
        testMatch.setUser2(testUser2);
        testMatch.setStatus(MatchStatus.PENDING);
        testMatch.setCreatedAt(LocalDateTime.now());
        testMatch.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("매칭 선호도 저장 성공")
    void saveMatchingPreferencesSuccess() {
        // given
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenReturn(testPreferences);

        // when
        matchingService.saveMatchingPreferences(testPreferencesDto, testUser.getId());

        // then
        verify(matchingPreferencesRepository).save(any(MatchingPreferences.class));
    }

    @Test
    @DisplayName("매칭 선호도 저장 실패 - 존재하지 않는 사용자")
    void saveMatchingPreferencesFailUserNotFound() {
        // given
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        // when & then
        MatchingException exception = assertThrows(MatchingException.class,
                () -> matchingService.saveMatchingPreferences(testPreferencesDto, testUser.getId()));
        assertEquals("존재하지 않는 사용자입니다.", exception.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    @DisplayName("매칭 생성 테스트")
    void startMatchingCreateSuccess() {
        // given
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.findById(testUser2.getId())).thenReturn(Optional.of(testUser2));
        when(matchingPreferencesRepository.findByUserId(anyLong())).thenReturn(Optional.of(testPreferences));
        when(valueOperations.get(anyString())).thenReturn(null);
        when(userRepository.findNearbyUsers(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Arrays.asList(testUser2));
        when(matchRepository.save(any(Match.class))).thenReturn(testMatch);
        when(redisSessionService.isUserOnline(anyString())).thenReturn(true);
        
        // when
        matchingService.startMatching(testPreferencesDto, testUser.getId());
        
        // then
        verify(matchRepository).save(any(Match.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
            anyString(), anyString(), any(MatchProfileDto.class));
    }

    @Test
    @DisplayName("매칭 수락 시 데이터베이스 업데이트 성공")
    void acceptMatchUpdateSuccess() {
        // given
        String matchId = testMatch.getId();
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(matchRepository.findByIdAndUserId(matchId, testUser.getId())).thenReturn(Optional.of(testMatch));
        when(matchRepository.save(any(Match.class))).thenReturn(testMatch);
        
        // when
        matchingService.acceptMatch(matchId, testUser.getId());
        
        // then
        assertEquals(MatchStatus.ACCEPTED, testMatch.getStatus());
        assertNotNull(testMatch.getRespondedAt());
        verify(matchRepository).save(testMatch);
    }

    @Test
    @DisplayName("매칭 거절 시 데이터베이스 업데이트 성공")
    void rejectMatchUpdateSuccess() {
        // given
        String matchId = testMatch.getId();
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(matchRepository.findByIdAndUserId(matchId, testUser.getId())).thenReturn(Optional.of(testMatch));
        when(matchRepository.save(any(Match.class))).thenReturn(testMatch);
        
        // when
        matchingService.rejectMatch(matchId, testUser.getId());
        
        // then
        assertEquals(MatchStatus.REJECTED, testMatch.getStatus());
        assertNotNull(testMatch.getRespondedAt());
        verify(matchRepository).save(testMatch);
    }

    @Test
    @DisplayName("매칭 만료 처리 성공")
    void stopMatchingExpiresPendingMatchesSuccess() {
        // given
        List<Match> pendingMatches = Arrays.asList(testMatch);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(matchRepository.findByUser1IdOrUser2IdAndStatus(
            testUser.getId(), testUser.getId(), MatchStatus.PENDING))
            .thenReturn(pendingMatches);
        when(matchRepository.save(any(Match.class))).thenReturn(testMatch);
        
        // when
        matchingService.stopMatching(testUser.getId());
        
        // then
        assertEquals(MatchStatus.EXPIRED, testMatch.getStatus());
        verify(matchRepository).save(testMatch);
    }

    @Test
    @DisplayName("근처 사용자 검색 성공")
    void searchNearbyUsersSuccess() {
        // given
        Double latitude = 37.5665;
        Double longitude = 126.9780;
        Double radius = 5.0;
        List<User> nearbyUsers = Arrays.asList(testUser2);
        
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.findNearbyUsers(anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(nearbyUsers);
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        
        // when
        List<MatchProfileDto> result = matchingService.searchNearbyUsers(
            latitude, longitude, radius, testUser.getId());
        
        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(userRepository).save(testUser);
    }

    @Test
    @DisplayName("대기 중인 매칭 처리 성공")
    void processPendingMatchesSuccess() {
        // given
        String matchId = testMatch.getId();
        List<String> pendingMatchIds = Arrays.asList(matchId);
        
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(redisSessionService.getPendingMatches(testUser.getId().toString()))
            .thenReturn(pendingMatchIds);
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(testMatch));
        
        // when
        matchingService.processPendingMatches(testUser.getId());
        
        // then
        verify(messagingTemplate).convertAndSendToUser(
            anyString(), anyString(), any(MatchProfileDto.class));
    }
} 