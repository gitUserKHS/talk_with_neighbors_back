package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MatchingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private MatchingPreferencesRepository matchingPreferencesRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate; // Even if not directly used in these specific tests, it's a dependency

    @Mock
    private RedisSessionService redisSessionService; // Even if not directly used, it's a dependency

    @InjectMocks
    private MatchingService matchingService;

    private MatchingPreferencesDto preferencesDto;
    private final Long testUserId = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        preferencesDto = createDummyMatchingPreferencesDto();
    }

    // Helper method to create a User mock
    private User mockUser(Long id, boolean isProfileComplete) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.isProfileComplete()).thenReturn(isProfileComplete);
        // Stub other methods of User that might be called by MatchingService
        // These ensure the service methods don't fail due to NullPointerExceptions from the User mock
        when(user.getLatitude()).thenReturn(34.0522); 
        when(user.getLongitude()).thenReturn(-118.2437);
        when(user.getAddress()).thenReturn("123 Main St");
        when(user.getAge()).thenReturn(30);
        when(user.getGender()).thenReturn("Female");
        when(user.getInterests()).thenReturn(List.of("reading"));
        return user;
    }


    private MatchingPreferencesDto createDummyMatchingPreferencesDto() {
        MatchingPreferencesDto dto = new MatchingPreferencesDto();
        dto.setLocation(new MatchingPreferencesDto.LocationDto(34.0522, -118.2437, "123 Main St"));
        dto.setMaxDistance(10.0);
        dto.setAgeRange(new int[]{25, 35});
        dto.setGender("Any");
        dto.setInterests(List.of("Hiking", "Reading"));
        return dto;
    }

    // Tests for startMatching
    @Test
    void testStartMatching_whenUserProfileIncomplete_shouldThrowMatchingException() {
        User incompleteUser = mockUser(testUserId, false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(incompleteUser));

        MatchingException exception = assertThrows(MatchingException.class, () -> {
            matchingService.startMatching(preferencesDto, testUserId);
        });

        assertEquals("프로필을 먼저 설정해야 매칭 기능을 이용할 수 있습니다.", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());

        verify(userRepository).findById(testUserId);
        // Ensure no further interactions that would occur if profile was complete
        verifyNoInteractions(matchingPreferencesRepository);
        verifyNoInteractions(matchRepository);
        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(redisSessionService);
    }

    @Test
    void testStartMatching_whenUserProfileComplete_shouldProceedNormally() {
        User completeUser = mockUser(testUserId, true);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));

        // Mocking behavior for saveMatchingPreferences internal call
        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.empty());
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Mocking behavior for stopMatching internal call
        when(matchRepository.bulkExpireMatches(eq(MatchStatus.EXPIRED), any(LocalDateTime.class), eq(testUserId), eq(MatchStatus.PENDING))).thenReturn(0);

        // Mocking behavior for finding nearby users and saving matches
        when(userRepository.findNearbyUsers(anyDouble(), anyDouble(), anyDouble())).thenReturn(Collections.emptyList());
        when(matchRepository.saveAll(anyList())).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> {
            matchingService.startMatching(preferencesDto, testUserId);
        });

        verify(userRepository).findById(testUserId); // Called once at the start
        // Verifications for saveMatchingPreferences internal call
        verify(matchingPreferencesRepository).findByUserId(testUserId);
        verify(matchingPreferencesRepository).save(any(MatchingPreferences.class));
        // Verification for stopMatching internal call
        verify(matchRepository).bulkExpireMatches(eq(MatchStatus.EXPIRED), any(LocalDateTime.class), eq(testUserId), eq(MatchStatus.PENDING));
        // Verifications for the rest of startMatching
        verify(userRepository).findNearbyUsers(anyDouble(), anyDouble(), anyDouble());
        verify(matchRepository).saveAll(anyList()); // saveAll is called even with an empty list
    }

    // Tests for saveMatchingPreferences
    @Test
    void testSaveMatchingPreferences_whenUserProfileIncomplete_shouldThrowMatchingException() {
        User incompleteUser = mockUser(testUserId, false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(incompleteUser));

        MatchingException exception = assertThrows(MatchingException.class, () -> {
            matchingService.saveMatchingPreferences(preferencesDto, testUserId);
        });

        assertEquals("프로필을 먼저 설정해야 매칭 환경설정을 저장할 수 있습니다.", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());

        verify(userRepository).findById(testUserId);
        verifyNoInteractions(matchingPreferencesRepository);
    }

    @Test
    void testSaveMatchingPreferences_whenUserProfileComplete_shouldSavePreferences() {
        User completeUser = mockUser(testUserId, true);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));

        // Scenario: No existing preferences, so new one will be created and saved
        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.empty());
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> {
            MatchingPreferences prefsToSave = invocation.getArgument(0);
            // Simulate saving by setting an ID or just return the object
            // In a real save, the ID would be set by the DB. For mock, this is enough.
            return prefsToSave;
        });

        assertDoesNotThrow(() -> {
            matchingService.saveMatchingPreferences(preferencesDto, testUserId);
        });

        verify(userRepository).findById(testUserId);
        verify(matchingPreferencesRepository).findByUserId(testUserId);
        verify(matchingPreferencesRepository).save(any(MatchingPreferences.class));
    }

    @Test
    void testSaveMatchingPreferences_whenUserProfileComplete_shouldUpdateExistingPreferences() {
        User completeUser = mockUser(testUserId, true);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(completeUser));

        MatchingPreferences existingPreferences = new MatchingPreferences();
        existingPreferences.setUser(completeUser); // Link to user
        existingPreferences.setId("existingPrefId"); // Simulate it's an existing one
        existingPreferences.setMaxDistance(5.0); // Old value, different from DTO's 10.0
        existingPreferences.setMinAge(20); // Old value
        existingPreferences.setAddress("Old Address"); // Old value


        when(matchingPreferencesRepository.findByUserId(testUserId)).thenReturn(Optional.of(existingPreferences));
        // Important: mock save to return the argument passed to it to simulate saving the updated entity
        when(matchingPreferencesRepository.save(any(MatchingPreferences.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> {
            matchingService.saveMatchingPreferences(preferencesDto, testUserId);
        });

        verify(userRepository).findById(testUserId);
        verify(matchingPreferencesRepository).findByUserId(testUserId);
        // Verify that the *existingPreferences* object (which should be modified) is saved
        verify(matchingPreferencesRepository).save(existingPreferences); 
        
        // Assert that the fields on the existingPreference object were updated from the DTO
        assertEquals(preferencesDto.getMaxDistance(), existingPreferences.getMaxDistance());
        assertEquals(preferencesDto.getAgeRange()[0], existingPreferences.getMinAge());
        assertEquals(preferencesDto.getAgeRange()[1], existingPreferences.getMaxAge());
        assertEquals(preferencesDto.getLocation().getAddress(), existingPreferences.getAddress());
        assertEquals(preferencesDto.getLocation().getLatitude(), existingPreferences.getLatitude());
        assertEquals(preferencesDto.getLocation().getLongitude(), existingPreferences.getLongitude());
        assertEquals(preferencesDto.getGender(), existingPreferences.getPreferredGender());
        assertEquals(preferencesDto.getInterests(), existingPreferences.getPreferredInterests());
    }
}
