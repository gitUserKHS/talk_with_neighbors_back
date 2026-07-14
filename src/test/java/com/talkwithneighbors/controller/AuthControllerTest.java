package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.config.TestSecurityConfig;
import com.talkwithneighbors.config.WebConfig;
import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.service.AuthService;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.RedisSessionService;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.auth.email.EmailVerificationCookieFactory;
import com.talkwithneighbors.auth.session.SessionCookieFactory;
import com.talkwithneighbors.auth.email.EmailVerificationProperties;
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
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

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
@Import({TestSecurityConfig.class, TestConfig.class})
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private MediaStorageService mediaStorageService;
    
    @MockBean
    private SessionValidationService sessionValidationService;
    
    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private SessionCookieFactory sessionCookieFactory;

    @MockBean
    private EmailVerificationCookieFactory emailVerificationCookieFactory;

    @MockBean
    private EmailVerificationProperties emailVerificationProperties;
    
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
        when(sessionCookieFactory.create(anyString())).thenAnswer(invocation ->
                ResponseCookie.from("TWN_SESSION", invocation.getArgument(0)).httpOnly(true).sameSite("Lax").build());
        when(sessionCookieFactory.expire()).thenReturn(
                ResponseCookie.from("TWN_SESSION", "").maxAge(0).build());
        when(emailVerificationCookieFactory.expiredProof()).thenReturn(
                ResponseCookie.from("TWN_EMAIL_PROOF", "").maxAge(0).build());
        
    }

    @Test
    @DisplayName("회원가입 성공 테스트")
    void registerSuccess() throws Exception {
        // given
        AuthService.AuthResponse authResponse = AuthService.AuthResponse.builder()
                .sessionId("test-session-id")
                .user(userDto)
                .build();
                
        when(authService.register(any(RegisterRequestDto.class), any())).thenReturn(authResponse);

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
                .andExpect(header().doesNotExist("X-Session-Id"))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("TWN_SESSION=test-session-id")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("SameSite=Lax")))
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
                .cookie(new Cookie("TWN_SESSION", sessionId)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("TWN_SESSION=;")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
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
                .cookie(new Cookie("TWN_SESSION", sessionId)))
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
                .cookie(new Cookie("TWN_SESSION", sessionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("회원가입 실패 - 이메일 중복")
    void registerFailDuplicateEmail() throws Exception {
        // given
        when(authService.register(any(RegisterRequestDto.class), any()))
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
                .cookie(new Cookie("TWN_SESSION", sessionId)))
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
    @DisplayName("HttpOnly 세션 쿠키로 프로필 수정")
    void updateProfileWithAuthentication() throws Exception {
        // given
        String sessionId = "test-session-id";
        when(authService.updateProfile(eq(sessionId), any(UserDto.class))).thenReturn(userDto);

        // when & then
        mockMvc.perform(put("/api/auth/profile")
                .cookie(new Cookie("TWN_SESSION", sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userDto.getEmail()));
    }

    @Test
    void invalidLoginReturnsBadRequest() throws Exception {
        loginRequestDto.setEmail("not-an-email");
        loginRequestDto.setPassword("");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void shortRegistrationPasswordReturnsBadRequest() throws Exception {
        registerRequestDto.setPassword("short");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.password").exists());
    }

}
