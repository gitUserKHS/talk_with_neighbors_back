package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisSessionService redisSessionService;

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequestDto request) {
        log.info("Registering new user with email: {}", request.getEmail());
        
        try {
            // 이메일과 사용자명 중복 체크
            boolean emailExists = userRepository.existsByEmail(request.getEmail());
            boolean usernameExists = userRepository.existsByUsername(request.getUsername());
            
            if (emailExists || usernameExists) {
                log.warn("Registration failed: emailExists={}, usernameExists={}", emailExists, usernameExists);
                throw new AuthException(
                    String.format("이메일 중복: %s, 사용자명 중복: %s", 
                        emailExists ? "있음" : "없음", 
                        usernameExists ? "있음" : "없음"), 
                    HttpStatus.CONFLICT
                );
            }

            User user = new User();
            user.setEmail(request.getEmail());
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            
            // 기본값 설정
            user.setAge(0);  // 임시 값
            user.setGender("");  // 임시 값
            user.setLatitude(0.0);  // 임시 값
            user.setLongitude(0.0);  // 임시 값
            user.setAddress("");  // 임시 값

            User savedUser = userRepository.save(user);
            log.info("User registered successfully: {}", savedUser.getId());
            
            // 새로운 세션 ID 생성
            String sessionId = UUID.randomUUID().toString();
            
            // Redis에 세션 저장 (새로운 세션이므로 직접 저장)
            UserSession userSession = UserSession.of(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getUsername()  // nickname은 username과 동일하게 설정
            );
            redisSessionService.saveSession(sessionId, userSession);
            
            return new AuthResponse(UserDto.fromEntity(savedUser), sessionId);
        } catch (Exception e) {
            log.error("Error during user registration", e);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse login(LoginRequestDto request) {
        try {
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
            }

            // 새로운 세션 ID 생성
            String sessionId = UUID.randomUUID().toString();
            
            // Redis에 세션 저장 (새로운 세션이므로 직접 저장)
            UserSession userSession = UserSession.of(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUsername()  // nickname은 username과 동일하게 설정
            );
            redisSessionService.saveSession(sessionId, userSession);
            
            return new AuthResponse(UserDto.fromEntity(user), sessionId);
        } catch (Exception e) {
            log.error("Login error occurred", e);
            throw e;
        }
    }

    public void logout(String sessionId) {
        redisSessionService.deleteSession(sessionId);
    }

    public UserDto getCurrentUser(String sessionId) {
        UserSession userSession = redisSessionService.getSession(sessionId);
        if (userSession == null) {
            throw new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
        User user = userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        return UserDto.fromEntity(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public UserDto updateProfile(String sessionId, UserDto request) {
        UserSession userSession = redisSessionService.getSession(sessionId);
        if (userSession == null) {
            throw new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
        User user = userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        user.setUsername(request.getUsername());
        user.setAge(request.getAge());
        user.setGender(request.getGender());
        user.setBio(request.getBio());
        user.setLatitude(request.getLatitude());
        user.setLongitude(request.getLongitude());
        user.setAddress(request.getAddress());
        user.setInterests(request.getInterests());

        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    public UserDto getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        return UserDto.fromEntity(user);
    }

    /**
     * 이메일과 사용자명 중복 여부를 확인합니다.
     * @param email 확인할 이메일
     * @param username 확인할 사용자명
     * @return 중복 여부를 담은 객체
     */
    public DuplicateCheckResponse checkDuplicates(String email, String username) {
        boolean emailExists = userRepository.existsByEmail(email);
        boolean usernameExists = userRepository.existsByUsername(username);
        
        return new DuplicateCheckResponse(emailExists, usernameExists);
    }
    
    /**
     * 중복 체크 결과를 담는 내부 클래스
     */
    @Getter
    @Setter
    @AllArgsConstructor
    public static class DuplicateCheckResponse {
        private boolean emailExists;
        private boolean usernameExists;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @Builder
    public static class AuthResponse {
        private UserDto user;
        private String sessionId;
    }
} 