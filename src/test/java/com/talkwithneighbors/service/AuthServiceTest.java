package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    }

    @Test
    @DisplayName("회원가입 성공 테스트")
    void registerSuccess() {
        // given
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        doNothing().when(redisSessionService).saveSession(anyString(), any());

        // when
        AuthService.AuthResponse response = authService.register(registerRequestDto);

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
        assertThrows(AuthException.class, () -> authService.register(registerRequestDto));
    }

    @Test
    @DisplayName("로그인 성공 테스트")
    void loginSuccess() {
        // given
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        doNothing().when(redisSessionService).saveSession(anyString(), any());

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
} 