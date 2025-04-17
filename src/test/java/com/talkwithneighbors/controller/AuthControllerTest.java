package com.talkwithneighbors.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

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
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.user").exists());
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
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.user").exists());
    }

    @Test
    @DisplayName("로그아웃 성공 테스트")
    void logoutSuccess() throws Exception {
        // given
        String sessionId = "test-session-id";
        doNothing().when(authService).logout(anyString());

        // when & then
        mockMvc.perform(post("/api/auth/logout")
                .header("X-Session-ID", sessionId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("현재 사용자 정보 조회 성공 테스트")
    void getCurrentUserSuccess() throws Exception {
        // given
        String sessionId = "test-session-id";
        when(authService.getCurrentUser(anyString())).thenReturn(userDto);

        // when & then
        mockMvc.perform(get("/api/auth/me")
                .header("X-Session-ID", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userDto.getEmail()))
                .andExpect(jsonPath("$.username").value(userDto.getUsername()));
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
    @DisplayName("현재 사용자 정보 조회 실패 - 세션 없음")
    void getCurrentUserFailNoSession() throws Exception {
        // given
        String sessionId = "invalid-session-id";
        when(authService.getCurrentUser(anyString()))
                .thenThrow(new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/auth/me")
                .header("X-Session-ID", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("세션을 찾을 수 없습니다."));
    }
} 