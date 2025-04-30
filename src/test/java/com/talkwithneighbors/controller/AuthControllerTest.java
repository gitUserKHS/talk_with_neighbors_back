package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.config.TestSecurityConfig;
import com.talkwithneighbors.config.TestWebConfig;
import com.talkwithneighbors.config.WebConfig;
import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.security.AuthInterceptor;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.AuthService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.SessionValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class)
    }
)
@TestPropertySource(properties = {
    "spring.session.store-type=none"
})
@Import({TestSecurityConfig.class, TestConfig.class, TestWebConfig.class})
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;
    
    @MockBean
    private SessionValidationService sessionValidationService;
    
    @MockBean
    private RedisSessionService redisSessionService;
    
    @MockBean
    private AuthInterceptor authInterceptor;

    private RegisterRequestDto registerRequestDto;
    private LoginRequestDto loginRequestDto;
    private UserDto userDto;

    @BeforeEach
    void setUp() {
        registerRequestDto = new RegisterRequestDto();
        registerRequestDto.setEmail("test@example.com");
        registerRequestDto.setUsername("testuser");
        registerRequestDto.setPassword("password123");

        loginRequestDto = new LoginRequestDto();
        loginRequestDto.setEmail("test@example.com");
        loginRequestDto.setPassword("password123");

        userDto = new UserDto();
        userDto.setEmail("test@example.com");
        userDto.setUsername("testuser");
        
        // AuthInterceptor 모킹 - 기본적으로 모든 요청 통과 허용
        try {
            when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("회원가입 성공 테스트")
    void registerSuccess() throws Exception {
        // given
        AuthService.AuthResponse authResponse = AuthService.AuthResponse.builder()
                .sessionId("test-session-id")
                .user(userDto)
                .build();
                
        when(authService.register(any(RegisterRequestDto.class))).thenReturn(authResponse);

        // when & then
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userDto.getEmail()))
                .andExpect(jsonPath("$.username").value(userDto.getUsername()));
    }

    @Test
    @DisplayName("로그인 성공 테스트")
    void loginSuccess() throws Exception {
        // given
        AuthService.AuthResponse authResponse = AuthService.AuthResponse.builder()
                .sessionId("test-session-id")
                .user(userDto)
                .build();
                
        when(authService.login(any(LoginRequestDto.class))).thenReturn(authResponse);

        // when & then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequestDto)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Session-Id"))
                .andExpect(jsonPath("$.email").value(userDto.getEmail()))
                .andExpect(jsonPath("$.username").value(userDto.getUsername()));
    }

    @Test
    @DisplayName("로그아웃 성공 테스트")
    void logoutSuccess() throws Exception {
        // given
        String sessionId = "test-session-id";
        doNothing().when(authService).logout(sessionId);

        // when & then
        mockMvc.perform(post("/api/auth/logout")
                .header("X-Session-Id", sessionId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("현재 사용자 정보 조회 성공 테스트 - 유효한 세션")
    void getCurrentUserWithValidSessionSuccess() throws Exception {
        // given
        String sessionId = "test-session-id";
        
        // 단순히 세션 ID 기반 사용자 조회 테스트 (인증 로직 없음)
        when(authService.getCurrentUser(sessionId)).thenReturn(userDto);

        // when & then
        mockMvc.perform(get("/api/auth/me")
                .header("X-Session-Id", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userDto.getEmail()))
                .andExpect(jsonPath("$.username").value(userDto.getUsername()));
    }
    
    @Test
    @DisplayName("현재 사용자 정보 조회 실패 테스트 - 서비스 예외")
    void getCurrentUserFail() throws Exception {
        // given
        String sessionId = "invalid-session-id";
        when(authService.getCurrentUser(sessionId))
                .thenThrow(new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/auth/me")
                .header("X-Session-Id", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("회원가입 실패 - 이메일 중복")
    void registerFailDuplicateEmail() throws Exception {
        // given
        when(authService.register(any(RegisterRequestDto.class)))
                .thenThrow(new AuthException("이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT));

        // when & then
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequestDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
    }

    @Test
    @DisplayName("로그인 실패 - 잘못된 비밀번호")
    void loginFailWrongPassword() throws Exception {
        // given
        when(authService.login(any(LoginRequestDto.class)))
                .thenThrow(new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

        // when & then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequestDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("로그아웃 실패 - 세션 없음")
    void logoutFailNoSession() throws Exception {
        // given
        String sessionId = "invalid-session-id";
        doThrow(new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND))
                .when(authService).logout(anyString());

        // when & then
        mockMvc.perform(post("/api/auth/logout")
                .header("X-Session-ID", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("로그인 실패 - 존재하지 않는 사용자")
    void loginFailUserNotFound() throws Exception {
        // given
        when(authService.login(any(LoginRequestDto.class)))
                .thenThrow(new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequestDto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("@RequireLogin이 적용된 프로필 수정 테스트 - 인증 성공")
    void updateProfileWithAuthentication() throws Exception {
        // given
        String sessionId = "test-session-id";
        UserSession validUserSession = UserSession.of(1L, "testuser", "test@example.com", "testuser");
        
        // 이 테스트에서만 실제 인증 로직을 사용
        when(sessionValidationService.validateSession(sessionId)).thenReturn(validUserSession);
        when(authService.updateProfile(eq(sessionId), any(UserDto.class))).thenReturn(userDto);
        // 이 테스트에서만 실제 인터셉터 로직을 사용
        when(authInterceptor.preHandle(any(), any(), any())).thenCallRealMethod();
        org.springframework.test.util.ReflectionTestUtils.setField(authInterceptor, "sessionValidationService", sessionValidationService);

        // when & then
        mockMvc.perform(put("/api/auth/profile")
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userDto.getEmail()));
    }

    @Test
    @DisplayName("@RequireLogin이 적용된 프로필 수정 테스트 - 인증 실패")
    void updateProfileWithoutAuthentication() throws Exception {
        // given
        // 이 테스트에서만 실제 인증 로직을 사용
        // 세션 검증 시 예외 발생
        // 이번 테스트에서만 인증 실패 시나리오: preHandle(...)이 예외를 던지도록
        when(authInterceptor.preHandle(any(), any(), any()))
                .thenThrow(new AuthException("세션을 찾을 수 없습니다.", HttpStatus.UNAUTHORIZED));
        when(sessionValidationService.validateSession(anyString()))
                .thenThrow(new RuntimeException("세션이 만료되었습니다. 다시 로그인해주세요."));

        // when & then
        mockMvc.perform(put("/api/auth/profile")
                .header("X-Session-Id", "invalid-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDto)))
                .andExpect(status().isUnauthorized());
    }
} 