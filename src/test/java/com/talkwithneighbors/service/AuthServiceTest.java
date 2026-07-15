package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.auth.email.EmailVerificationService;
import com.talkwithneighbors.auth.nickname.NicknameException;
import com.talkwithneighbors.auth.session.SessionIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RedisSessionService redisSessionService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private SessionIssuer sessionIssuer;

    @InjectMocks
    private AuthService authService;

    private RegisterRequestDto registerRequestDto;
    private LoginRequestDto loginRequestDto;
    private User testUser;

    @BeforeEach
    void setUp() {
        registerRequestDto = new RegisterRequestDto();
        registerRequestDto.setEmail("test@example.com");
        registerRequestDto.setUsername("testuser");
        registerRequestDto.setPassword("password123");

        loginRequestDto = new LoginRequestDto();
        loginRequestDto.setEmail("test@example.com");
        loginRequestDto.setPassword("password123");

        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
        testUser.setUsername("testuser");
        testUser.setPassword("encodedPassword");
        lenient().when(emailVerificationService.availability())
                .thenReturn(new EmailVerificationService.Availability(false, "disabled"));
    }

    @Test
    @DisplayName("회원가입 성공 테스트")
    void registerSuccess() {
        // given
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(sessionIssuer.issue(any(User.class))).thenReturn("test-session-id");

        // when
        AuthService.AuthResponse response = authService.register(registerRequestDto, null);

        // then
        assertNotNull(response);
        assertNotNull(response.getSessionId());
        assertEquals(testUser.getEmail(), response.getUser().getEmail());
        assertEquals(testUser.getUsername(), response.getUser().getUsername());
    }

    @Test
    @DisplayName("이메일 중복 회원가입 실패 테스트")
    void registerFailWithDuplicateEmail() {
        // given
        when(userRepository.existsByEmail(anyString())).thenReturn(true);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);

        // when & then
        assertThrows(AuthException.class, () -> authService.register(registerRequestDto, null));
    }

    @Test
    @DisplayName("로그인 성공 테스트")
    void loginSuccess() {
        // given
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(sessionIssuer.issue(any(User.class))).thenReturn("test-session-id");

        // when
        AuthService.AuthResponse response = authService.login(loginRequestDto);

        // then
        assertNotNull(response);
        assertNotNull(response.getSessionId());
        assertEquals(testUser.getEmail(), response.getUser().getEmail());
        assertEquals(testUser.getUsername(), response.getUser().getUsername());
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 실패 테스트")
    void loginFailWithWrongPassword() {
        // given
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // when & then
        assertThrows(AuthException.class, () -> authService.login(loginRequestDto));
    }

    @Test
    void systemAccountsCannotLogInEvenWithAMatchingPassword() {
        testUser.setAccountType(UserAccountType.SYSTEM);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        assertThrows(AuthException.class, () -> authService.login(loginRequestDto));

        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(redisSessionService);
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 실패 테스트")
    void loginFailWithNonExistentEmail() {
        // given
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // when & then
        assertThrows(AuthException.class, () -> authService.login(loginRequestDto));
    }

    @Test
    @DisplayName("로그아웃 성공 테스트")
    void logoutSuccess() {
        // given
        String sessionId = "test-session-id";
        doNothing().when(redisSessionService).deleteSession(anyString());

        // when & then
        assertDoesNotThrow(() -> authService.logout(sessionId));
        verify(redisSessionService).deleteSession(sessionId);
    }

    @Test
    @DisplayName("현재 사용자 정보 조회 성공 테스트")
    void getCurrentUserSuccess() {
        // given
        String sessionId = "test-session-id";
        UserSession userSession = UserSession.of(testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession(anyString())).thenReturn(userSession);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));

        // when
        UserDto userDto = authService.getCurrentUser(sessionId);

        // then
        assertNotNull(userDto);
        assertEquals(testUser.getEmail(), userDto.getEmail());
        assertEquals(testUser.getUsername(), userDto.getUsername());
    }

    @Test
    @DisplayName("세션이 없는 경우 현재 사용자 정보 조회 실패 테스트")
    void getCurrentUserFailWithNoSession() {
        // given
        String sessionId = "test-session-id";
        when(redisSessionService.getSession(anyString())).thenReturn(null);

        // when & then
        assertThrows(AuthException.class, () -> authService.getCurrentUser(sessionId));
    }

    @Test
    void requiredNicknameCanBeReplacedWithoutResettingTheSession() {
        testUser.setUsername("kakao_defaultname");
        testUser.setNicknameSetupRequired(true);
        UserSession session = UserSession.of(
                testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession("test-session-id")).thenReturn(session);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.existsByUsernameAndIdNot("다윤이웃", testUser.getId())).thenReturn(false);
        when(userRepository.saveAndFlush(testUser)).thenReturn(testUser);

        UserDto updated = authService.updateNickname("test-session-id", "  다윤이웃  ");

        assertEquals("다윤이웃", updated.getUsername());
        assertFalse(updated.isNicknameSetupRequired());
        verify(redisSessionService).updateSession("test-session-id", testUser.getId(), "다윤이웃");
    }

    @Test
    void requiredNicknameCannotConfirmTheGeneratedValueUnchanged() {
        testUser.setUsername("kakao_defaultname");
        testUser.setNicknameSetupRequired(true);
        UserSession session = UserSession.of(
                testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession("test-session-id")).thenReturn(session);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        NicknameException exception = assertThrows(NicknameException.class, () ->
                authService.updateNickname("test-session-id", "kakao_defaultname"));

        assertEquals("NICKNAME_CHANGE_REQUIRED", exception.getCode());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void nicknameRejectsWhitespaceAndInvisibleFormatCharacters() {
        testUser.setNicknameSetupRequired(true);
        UserSession session = UserSession.of(
                testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession("test-session-id")).thenReturn(session);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        NicknameException spaced = assertThrows(NicknameException.class, () ->
                authService.updateNickname("test-session-id", "다 윤"));
        NicknameException invisible = assertThrows(NicknameException.class, () ->
                authService.updateNickname("test-session-id", "다\u200B윤"));

        assertEquals("NICKNAME_INVALID", spaced.getCode());
        assertEquals("NICKNAME_INVALID", invisible.getCode());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNicknameReturnsStableConflict() {
        testUser.setNicknameSetupRequired(true);
        UserSession session = UserSession.of(
                testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession("test-session-id")).thenReturn(session);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.existsByUsernameAndIdNot("이미사용중", testUser.getId())).thenReturn(true);

        NicknameException exception = assertThrows(NicknameException.class, () ->
                authService.updateNickname("test-session-id", "이미사용중"));

        assertEquals("USERNAME_ALREADY_IN_USE", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void databaseNicknameRaceAlsoReturnsStableConflict() {
        testUser.setNicknameSetupRequired(true);
        UserSession session = UserSession.of(
                testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getUsername());
        when(redisSessionService.getSession("test-session-id")).thenReturn(session);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.existsByUsernameAndIdNot("동시요청", testUser.getId())).thenReturn(false);
        when(userRepository.saveAndFlush(testUser)).thenThrow(new DataIntegrityViolationException("duplicate"));

        NicknameException exception = assertThrows(NicknameException.class, () ->
                authService.updateNickname("test-session-id", "동시요청"));

        assertEquals("USERNAME_ALREADY_IN_USE", exception.getCode());
    }

    @Test
    void nicknameSetupKeepsAnOtherwiseCompleteProfileIncomplete() {
        testUser.setNicknameSetupRequired(true);
        testUser.setAge(24);
        testUser.setGender("female");
        testUser.setInterests(java.util.List.of("산책"));
        testUser.setLatitude(37.5);
        testUser.setLongitude(127.0);
        testUser.setAddress("서울시");

        UserDto dto = UserDto.fromEntity(testUser);

        assertTrue(dto.isNicknameSetupRequired());
        assertFalse(dto.isProfileComplete());
    }
}
