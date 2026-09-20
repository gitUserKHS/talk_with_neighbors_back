package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.dto.auth.UpdateNicknameRequest;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.AuthService;
import com.talkwithneighbors.service.AuthService.AuthResponse;
import com.talkwithneighbors.service.AuthService.DuplicateCheckResponse;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.media.MediaAsset;
import com.talkwithneighbors.auth.email.EmailVerificationCookieFactory;
import com.talkwithneighbors.auth.session.SessionCookieFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final AuthService authService;
    private final MediaStorageService mediaStorageService;
    private final SessionCookieFactory sessionCookies;
    private final EmailVerificationCookieFactory emailVerificationCookies;

    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@Valid @RequestBody LoginRequestDto request) {
        AuthResponse response = authService.login(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.create(response.getSessionId()).toString())
                .body(response.getUser());
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(
            @Valid @RequestBody RegisterRequestDto request,
            @CookieValue(name = "TWN_EMAIL_PROOF", required = false) String emailProof) {
        AuthResponse response = authService.register(request, emailProof);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        sessionCookies.create(response.getSessionId()).toString(),
                        emailVerificationCookies.expiredProof().toString())
                .body(response.getUser());
    }

    /**
     * 로그아웃은 세션을 검증하지 않고 쿠키의 자격 증명 자체를 폐기한다.
     * 이미 만료된 세션이어도 쿠키는 지워져야 하기 때문이다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookieFactory.COOKIE_NAME, required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("Logout request received with no session ID");
            return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookies.expire().toString()).build();
        }

        // 세션 ID가 쉼표로 구분되어 있을 경우 첫 번째 세션 ID만 사용
        String actualSessionId = sessionId.split(",")[0].trim();

        authService.logout(actualSessionId);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookies.expire().toString()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(UserSession userSession) {
        return ResponseEntity.ok(authService.getCurrentUser(userSession.getUserId()));
    }

    @PutMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@RequestBody UserDto request,
                                                  UserSession userSession) {
        return ResponseEntity.ok(authService.updateProfile(userSession.getUserId(), request));
    }

    @PutMapping("/profile/nickname")
    public ResponseEntity<UserDto> updateNickname(
            @Valid @RequestBody UpdateNicknameRequest request,
            UserSession userSession) {
        return ResponseEntity.ok(authService.updateNickname(
                userSession.getUserId(), request.nickname()));
    }

    @PostMapping(value = "/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDto> uploadProfileImage(
            @RequestPart("file") MultipartFile file,
            UserSession userSession
    ) {
        MediaAsset stored = mediaStorageService.storeProfileImage(file);
        try {
            return ResponseEntity.ok(authService.updateProfileImage(userSession.getUserId(), stored.url()));
        } catch (RuntimeException exception) {
            mediaStorageService.deleteMedia(stored.ownedUrls());
            throw exception;
        }
    }

    @DeleteMapping("/profile/image")
    public ResponseEntity<UserDto> deleteProfileImage(UserSession userSession) {
        return ResponseEntity.ok(authService.updateProfileImage(userSession.getUserId(), null));
    }

    @GetMapping("/profile")
    public ResponseEntity<UserDto> getProfile(UserSession userSession) {
        return getCurrentUser(userSession);
    }

    /**
     * 이메일과 닉네임 중복 여부를 확인합니다.
     * @param email 확인할 이메일
     * @param username 확인할 닉네임
     * @return 중복 여부를 담은 객체
     */
    @GetMapping("/check-duplicates")
    public ResponseEntity<DuplicateCheckResponse> checkDuplicates(
            @RequestParam String username) {
        DuplicateCheckResponse response = authService.checkDuplicates(null, username);
        return ResponseEntity.ok(response);
    }
}
