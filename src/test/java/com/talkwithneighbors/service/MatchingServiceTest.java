package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class MatchingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private MatchingPreferencesRepository matchingPreferencesRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RedisSessionService redisSessionService;

    @Mock
    private ChatService chatService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private OfflineNotificationService offlineNotificationService;

    @Spy
    private CompatibilityScoreService compatibilityScoreService = new CompatibilityScoreService();

    @InjectMocks
    private MatchingService matchingService;

    private MatchingPreferencesDto preferencesDto;
    private final Long testUserId = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        preferencesDto = createDummyMatchingPreferencesDto();
    }

    @Test
    void startMatchingWithIncompleteProfileThrowsBadRequest() {
        User incompleteUser = user(testUserId, false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(incompleteUser));

        MatchingException exception = assertThrows(MatchingException.class, () ->
                matchingService.startMatching(preferencesDto, testUserId)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(userRepository).findById(testUserId);
        verifyNoInteractions(matchingPreferencesRepository);
        verifyNoInteractions(matchRepository);
    }

    @Test
    void startMatchingWithCompleteProfileSavesPreferencesAndReturnsEmptyListWhenNoCandidates() {
        User completeUser = user(testUserId, true);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));
        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.empty());
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(matchRepository.bulkExpireMatches(eq(MatchStatus.EXPIRED), any(LocalDateTime.class), eq(testUserId), eq(MatchStatus.PENDING))).thenReturn(0);
        when(userRepository.findNearbyUsers(anyDouble(), anyDouble(), anyDouble())).thenReturn(Collections.emptyList());
        when(chatService.getUsersWithOneOnOneChatRooms(testUserId)).thenReturn(Collections.emptyList());
        when(matchRepository.saveAll(anyList())).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> matchingService.startMatching(preferencesDto, testUserId));

        verify(userRepository, times(2)).findById(testUserId);
        verify(matchingPreferencesRepository).findByUserId(testUserId);
        verify(matchingPreferencesRepository).save(any(MatchingPreferences.class));
        verify(matchRepository).bulkExpireMatches(eq(MatchStatus.EXPIRED), any(LocalDateTime.class), eq(testUserId), eq(MatchStatus.PENDING));
        verify(userRepository).findNearbyUsers(anyDouble(), anyDouble(), anyDouble());
        verify(matchRepository).saveAll(anyList());
    }

    @Test
    void saveMatchingPreferencesWithIncompleteProfileThrowsBadRequest() {
        User incompleteUser = user(testUserId, false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(incompleteUser));

        MatchingException exception = assertThrows(MatchingException.class, () ->
                matchingService.saveMatchingPreferences(preferencesDto, testUserId)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(userRepository).findById(testUserId);
        verifyNoInteractions(matchingPreferencesRepository);
    }

    @Test
    void saveMatchingPreferencesCreatesNewPreferences() {
        User completeUser = user(testUserId, true);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));
        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.empty());
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> matchingService.saveMatchingPreferences(preferencesDto, testUserId));

        verify(userRepository).findById(testUserId);
        verify(matchingPreferencesRepository).findByUserId(testUserId);
        verify(matchingPreferencesRepository).save(any(MatchingPreferences.class));
    }

    @Test
    void saveMatchingPreferencesUpdatesExistingPreferences() {
        User completeUser = user(testUserId, true);
        MatchingPreferences existingPreferences = new MatchingPreferences();
        existingPreferences.setUser(completeUser);
        existingPreferences.setId(1L);
        existingPreferences.setMaxDistance(5.0);
        existingPreferences.setMinAge(20);

        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));
        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.of(existingPreferences));
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> matchingService.saveMatchingPreferences(preferencesDto, testUserId));

        assertEquals(preferencesDto.getMaxDistance(), existingPreferences.getMaxDistance());
        assertEquals(preferencesDto.getAgeRange()[0], existingPreferences.getMinAge());
        assertEquals(preferencesDto.getAgeRange()[1], existingPreferences.getMaxAge());
        assertEquals(preferencesDto.getGender(), existingPreferences.getPreferredGender());
        assertEquals(preferencesDto.getInterests(), existingPreferences.getPreferredInterests());
    }

    private User user(Long id, boolean completeProfile) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setUsername("user" + id);
        user.setPassword("password");
        if (completeProfile) {
            user.setAge(30);
            user.setGender("Any");
            user.setInterests(List.of("Reading"));
            user.setLatitude(34.0522);
            user.setLongitude(-118.2437);
            user.setAddress("123 Main St");
        }
        return user;
    }

    private MatchingPreferencesDto createDummyMatchingPreferencesDto() {
        MatchingPreferencesDto dto = new MatchingPreferencesDto();
        dto.setMaxDistance(10.0);
        dto.setAgeRange(new Integer[]{25, 35});
        dto.setGender("Any");
        dto.setInterests(List.of("Hiking", "Reading"));
        return dto;
    }
}
