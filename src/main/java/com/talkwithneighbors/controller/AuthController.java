package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.AuthService;
import com.talkwithneighbors.service.AuthService.AuthResponse;
import com.talkwithneighbors.service.AuthService.DuplicateCheckResponse;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.media.MediaAsset;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import org.springframework.http.ResponseCookie;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_COOKIE = "TWN_SESSION";

    private final AuthService authService;
    private final MediaStorageService mediaStorageService;

    @Value("${app.session.cookie-secure:false}")
    private boolean secureSessionCookie;

    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@RequestBody LoginRequestDto request) {
        log.info("Login request received for email: {}", request.getEmail());
        AuthResponse response = authService.login(request);
        
        // 세션 ID를 응답 헤더에 포함
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Session-Id", response.getSessionId());
        // 세션 ID 설정에 "replace" 추가
        headers.add("Access-Control-Expose-Headers", "X-Session-Id");
        headers.add(HttpHeaders.SET_COOKIE, sessionCookie(response.getSessionId()).toString());
        log.info("Login successful. Setting session ID in header: {}", response.getSessionId());
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(response.getUser());
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegisterRequestDto request) {
        log.info("Register request received for email: {}", request.getEmail());
        AuthResponse response = authService.register(request);
        
        // 세션 ID를 응답 헤더에 포함
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Session-Id", response.getSessionId());
        // 세션 ID 설정에 "replace" 추가
        headers.add("Access-Control-Expose-Headers", "X-Session-Id");
        headers.add(HttpHeaders.SET_COOKIE, sessionCookie(response.getSessionId()).toString());
        log.info("Registration successful. Setting session ID in header: {}", response.getSessionId());
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(response.getUser());
    }
    
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                       HttpServletRequest request) {
        sessionId = resolveSessionId(sessionId, request);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("Logout request received with no session ID");
            return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString()).build();
        }
        
        log.info("Logout request received for session: {}", sessionId);
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        log.info("Using first session ID for logout: {}", actualSessionId);
        
        authService.logout(actualSessionId);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                                   HttpServletRequest request) {
        sessionId = resolveSessionId(sessionId, request);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("getCurrentUser request received with no session ID");
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        log.info("Using first session ID for getCurrentUser: {}", actualSessionId);
        
        UserDto user = authService.getCurrentUser(actualSessionId);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/profile")
    @RequireLogin
    public ResponseEntity<UserDto> updateProfile(@RequestBody UserDto request, @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                                  HttpServletRequest httpRequest) {
        sessionId = resolveSessionId(sessionId, httpRequest);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("updateProfile request received with no session ID");
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        log.info("Using first session ID for updateProfile: {}", actualSessionId);
        
        UserDto updatedUser = authService.updateProfile(actualSessionId, request);
        return ResponseEntity.ok(updatedUser);
    }

    @PostMapping(value = "/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireLogin
    public ResponseEntity<UserDto> uploadProfileImage(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            HttpServletRequest request
    ) {
        String actualSessionId = requireSessionId(sessionId, request);
        MediaAsset stored = mediaStorageService.storeProfileImage(file);
        try {
            return ResponseEntity.ok(authService.updateProfileImage(actualSessionId, stored.url()));
        } catch (RuntimeException exception) {
            mediaStorageService.deleteMedia(stored.ownedUrls());
            throw exception;
        }
    }

    @DeleteMapping("/profile/image")
    @RequireLogin
    public ResponseEntity<UserDto> deleteProfileImage(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(authService.updateProfileImage(requireSessionId(sessionId, request), null));
    }

    @GetMapping("/profile")
    @RequireLogin
    public ResponseEntity<UserDto> getProfile(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                               HttpServletRequest request) {
        return getCurrentUser(sessionId, request);
    }

    private String resolveSessionId(String headerSessionId, HttpServletRequest request) {
        if (headerSessionId != null && !headerSessionId.isBlank()) return headerSessionId;
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        }
        return null;
    }

    private String requireSessionId(String headerSessionId, HttpServletRequest request) {
        String sessionId = resolveSessionId(headerSessionId, request);
        if (sessionId == null || sessionId.isBlank()) {
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        return sessionId.split(",")[0].trim();
    }

    private ResponseCookie sessionCookie(String sessionId) {
        return ResponseCookie.from(SESSION_COOKIE, sessionId)
                .httpOnly(true).secure(secureSessionCookie).sameSite("Lax").path("/")
                .maxAge(Duration.ofHours(24)).build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "").httpOnly(true).secure(secureSessionCookie)
                .sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
    }

    /**
     * 이메일과 닉네임 중복 여부를 확인합니다.
     * @param email 확인할 이메일
     * @param username 확인할 닉네임
     * @return 중복 여부를 담은 객체
     */
    @GetMapping("/check-duplicates")
    public ResponseEntity<DuplicateCheckResponse> checkDuplicates(
            @RequestParam String email,
            @RequestParam String username) {
        log.info("Duplicate check request received for email: {} and username: {}", email, username);
        DuplicateCheckResponse response = authService.checkDuplicates(email, username);
        return ResponseEntity.ok(response);
    }
}
