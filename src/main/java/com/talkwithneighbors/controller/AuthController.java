package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.LoginResponseDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@RequestBody LoginRequestDto request, HttpSession session) {
        return ResponseEntity.ok(authService.login(request, session));
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegisterRequestDto request, HttpSession session) {
        return ResponseEntity.ok(authService.register(request, session));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        authService.logout(session);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        if (userSession == null) {
            return ResponseEntity.ok(null);
        }
        UserDto user = authService.getUserById(userSession.getUserId());
        return ResponseEntity.ok(user);
    }

    @PutMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@RequestBody UserDto request, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        if (userSession == null) {
            return ResponseEntity.ok(null);
        }
        UserDto updatedUser = authService.updateProfile(userSession.getUserId(), request);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * 이메일과 닉네임 중복 여부를 확인합니다.
     * @param email 확인할 이메일
     * @param username 확인할 닉네임
     * @return 중복 여부를 담은 객체
     */
    @GetMapping("/check-duplicates")
    public ResponseEntity<AuthService.DuplicateCheckResponse> checkDuplicates(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String username) {
        return ResponseEntity.ok(authService.checkDuplicates(email, username));
    }
} 