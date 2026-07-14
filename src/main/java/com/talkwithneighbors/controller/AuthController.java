package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
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
        AuthResponse response = authService.login(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(response.getSessionId()).toString())
                .body(response.getUser());
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegisterRequestDto request) {
        AuthResponse response = authService.register(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(response.getSessionId()).toString())
                .body(response.getUser());
    }
    
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String sessionId = resolveSessionId(request);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("Logout request received with no session ID");
            return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString()).build();
        }
        
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        
        authService.logout(actualSessionId);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(HttpServletRequest request) {
        String sessionId = resolveSessionId(request);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("getCurrentUser request received with no session ID");
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        UserDto user = authService.getCurrentUser(actualSessionId);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@RequestBody UserDto request,
                                                  HttpServletRequest httpRequest) {
        String sessionId = resolveSessionId(httpRequest);
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("updateProfile request received with no session ID");
            throw new RuntimeException("세션이 없습니다. 다시 로그인해주세요.");
        }
        
        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();
        UserDto updatedUser = authService.updateProfile(actualSessionId, request);
        return ResponseEntity.ok(updatedUser);
    }

    @PostMapping(value = "/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDto> uploadProfileImage(
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        String actualSessionId = requireSessionId(request);
        MediaAsset stored = mediaStorageService.storeProfileImage(file);
        try {
            return ResponseEntity.ok(authService.updateProfileImage(actualSessionId, stored.url()));
        } catch (RuntimeException exception) {
            mediaStorageService.deleteMedia(stored.ownedUrls());
            throw exception;
        }
    }

    @DeleteMapping("/profile/image")
    public ResponseEntity<UserDto> deleteProfileImage(
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(authService.updateProfileImage(requireSessionId(request), null));
    }

    @GetMapping("/profile")
    public ResponseEntity<UserDto> getProfile(HttpServletRequest request) {
        return getCurrentUser(request);
    }

    private String resolveSessionId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        }
        return null;
    }

    private String requireSessionId(HttpServletRequest request) {
        String sessionId = resolveSessionId(request);
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
        DuplicateCheckResponse response = authService.checkDuplicates(email, username);
        return ResponseEntity.ok(response);
    }
}
