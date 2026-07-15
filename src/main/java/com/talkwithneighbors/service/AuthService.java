package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.UserDto;
import com.talkwithneighbors.dto.auth.LoginRequestDto;
import com.talkwithneighbors.dto.auth.RegisterRequestDto;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.auth.email.EmailVerificationService;
import com.talkwithneighbors.auth.nickname.NicknameException;
import com.talkwithneighbors.auth.session.SessionIssuer;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.security.UserSession;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import com.talkwithneighbors.service.OfflineNotificationService;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisSessionService redisSessionService;
    private final OfflineNotificationService offlineNotificationService;
    private final DomainEventPublisher domainEventPublisher;
    private final EmailVerificationService emailVerificationService;
    private final SessionIssuer sessionIssuer;

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequestDto request, String emailProof) {
        try {
            String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
            if (emailVerificationService.registrationRequired()
                    && !emailVerificationService.availability().enabled()) {
                throw new AuthException("Email registration is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
            }
            if (emailVerificationService.registrationRequired()
                    || emailVerificationService.availability().enabled()) {
                emailVerificationService.consumeProof(normalizedEmail, emailProof);
            }
            // 이메일과 사용자명 중복 체크
            boolean emailExists = userRepository.existsByEmail(normalizedEmail);
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
            user.setEmail(normalizedEmail);
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setAccountType(UserAccountType.MEMBER);
            user.setPasswordLoginEnabled(true);
            
            // 기본값 설정
            user.setAge(0);  // 임시 값
            user.setGender("");  // 임시 값
            user.setLatitude(0.0);  // 임시 값
            user.setLongitude(0.0);  // 임시 값
            user.setAddress("");  // 임시 값

            User savedUser = userRepository.save(user);
            log.info("User registered successfully: {}", savedUser.getId());
            
            // 새로운 세션 ID 생성
            String sessionId = sessionIssuer.issue(savedUser);
            
            // 회원가입 후 바로 오프라인 알림 전송 (선택적, 필요하다면 추가)
            // offlineNotificationService.sendPendingNotifications(savedUser.getId());
            
            return new AuthResponse(UserDto.fromEntity(savedUser), sessionId);
        } catch (Exception e) {
            log.error("Error during user registration", e);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse login(LoginRequestDto request) {
        try {
            String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
            User user = userRepository.findByEmail(normalizedEmail)
                    .orElseThrow(() -> new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

            if (user.getAccountType() == UserAccountType.SYSTEM
                    || Boolean.FALSE.equals(user.getPasswordLoginEnabled())
                    || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
            }

            // 새로운 세션 ID 생성
            String sessionId = sessionIssuer.issue(user);
            
            // 로그인 성공 후 오프라인 알림 전송 -> SessionConnectedEvent 리스너에서 처리하도록 변경
            // try {
            //     log.info("[AuthService.login] Attempting to send pending notifications for user: {}", user.getId());
            //     offlineNotificationService.sendPendingNotifications(user.getId());
            // } catch (Exception e) {
            //     log.error("[AuthService.login] Error sending pending notifications for user {}: {}", user.getId(), e.getMessage(), e);
            //     // 알림 전송 실패가 로그인 전체를 실패시키지는 않도록 예외 처리
            // }
            
            return new AuthResponse(UserDto.fromEntity(user), sessionId);
        } catch (Exception e) {
            log.error("Login error occurred", e);
            throw e;
        }
    }

    public void logout(String sessionId) {
        redisSessionService.deleteSession(sessionId);
    }

    @Transactional(readOnly = true)
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
    public UserDto updateNickname(String sessionId, String rawNickname) {
        User user = requireSessionUser(sessionId);
        boolean changed = applyNickname(user, rawNickname, true);
        if (!changed) return UserDto.fromEntity(user);

        User updatedUser = saveNicknameChange(user);
        redisSessionService.updateSession(sessionId, updatedUser.getId(), updatedUser.getUsername());
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional(rollbackFor = Exception.class)
    public UserDto updateProfile(String sessionId, UserDto request) {
        UserSession userSession = redisSessionService.getSession(sessionId);
        if (userSession == null) {
            throw new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
        User user = userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        boolean nicknameChanged = request.getUsername() != null
                && applyNickname(user, request.getUsername(), false);
        user.setAge(request.getAge());
        user.setGender(request.getGender());
        user.setBio(request.getBio());
        // Profile images are changed only through the validated multipart upload endpoint.
        user.setLatitude(request.getLatitude());
        user.setLongitude(request.getLongitude());
        user.setAddress(request.getAddress());
        user.setInterests(request.getInterests());

        User updatedUser = nicknameChanged ? saveNicknameChange(user) : userRepository.save(user);
        if (nicknameChanged) {
            redisSessionService.updateSession(sessionId, updatedUser.getId(), updatedUser.getUsername());
        }
        return UserDto.fromEntity(updatedUser);
    }

    private User requireSessionUser(String sessionId) {
        UserSession userSession = redisSessionService.getSession(sessionId);
        if (userSession == null) {
            throw new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
        return userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private boolean applyNickname(User user, String rawNickname, boolean requireChange) {
        String nickname = normalizeNickname(rawNickname);
        if (nickname.equals(user.getUsername())) {
            if (requireChange && Boolean.TRUE.equals(user.getNicknameSetupRequired())) {
                throw new NicknameException(
                        "NICKNAME_CHANGE_REQUIRED",
                        "자동 생성된 닉네임과 다른 닉네임을 입력해 주세요.",
                        HttpStatus.BAD_REQUEST);
            }
            return false;
        }
        if (userRepository.existsByUsernameAndIdNot(nickname, user.getId())) {
            throw duplicateNickname();
        }
        user.setUsername(nickname);
        user.setNicknameSetupRequired(false);
        return true;
    }

    private String normalizeNickname(String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.strip();
        int length = nickname.codePointCount(0, nickname.length());
        boolean invalid = length < 2
                || length > 30
                || nickname.codePoints().anyMatch(AuthService::isForbiddenNicknameCodePoint);
        if (invalid) {
            throw new NicknameException(
                    "NICKNAME_INVALID",
                    "닉네임은 공백 없이 2자 이상 30자 이하로 입력해 주세요.",
                    HttpStatus.BAD_REQUEST);
        }
        return nickname;
    }

    private static boolean isForbiddenNicknameCodePoint(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private User saveNicknameChange(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateNickname();
        }
    }

    private NicknameException duplicateNickname() {
        return new NicknameException(
                "USERNAME_ALREADY_IN_USE",
                "이미 사용 중인 닉네임이에요.",
                HttpStatus.CONFLICT);
    }

    @Transactional(rollbackFor = Exception.class)
    public UserDto updateProfileImage(String sessionId, String profileImageUrl) {
        UserSession userSession = redisSessionService.getSession(sessionId);
        if (userSession == null) {
            throw new AuthException("세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
        User user = userRepository.findById(userSession.getUserId())
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        String previousImageUrl = user.getProfileImage();
        user.setProfileImage(profileImageUrl);
        User updatedUser = userRepository.save(user);

        if (previousImageUrl != null
                && !previousImageUrl.isBlank()
                && !previousImageUrl.equals(profileImageUrl)) {
            domainEventPublisher.publish(MediaFilesDeletedEvent.create(
                    "User", user.getId().toString(), java.util.List.of(previousImageUrl)));
        }
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional(readOnly = true)
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
        // Kept only for legacy clients. New registration checks username here
        // and verifies email through the challenge flow to avoid enumeration.
        boolean emailExists = false;
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
