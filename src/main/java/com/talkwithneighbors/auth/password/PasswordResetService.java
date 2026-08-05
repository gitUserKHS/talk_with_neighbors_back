package com.talkwithneighbors.auth.password;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.RedisSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 비밀번호 재설정.
 *
 * 전 과정에서 어떤 이메일이 가입되어 있는지 드러내지 않는다. 요청 응답은 계정이 있든 없든 같고,
 * 확인 단계의 실패 사유도 구분하지 않는다. 로그인 폼이 막아 놓은 계정 열거를
 * 재설정 폼이 대신 열어 주는 일이 흔하다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    private final PasswordResetProperties properties;
    private final PasswordResetChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisSessionService redisSessionService;
    private final ObjectProvider<PasswordResetEmailSender> emailSender;

    public boolean isEnabled() {
        PasswordResetEmailSender sender = emailSender.getIfAvailable();
        return properties.isEnabled() && sender != null && sender.isAvailable();
    }

    /**
     * 재설정 코드를 보낸다.
     *
     * 계정이 없거나 발송에 실패해도 호출자에게는 성공으로 보인다.
     * 호출자가 구분할 수 있는 유일한 실패는 재발송 쿨다운이며, 이는 이미 요청을 보낸
     * 본인만 마주치는 상태다.
     */
    @Transactional
    public void requestReset(String email) {
        if (!isEnabled()) {
            throw new AuthException("비밀번호 재설정을 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        }

        String normalized = normalize(email);
        Instant now = Instant.now();

        Optional<PasswordResetChallenge> existing = challengeRepository.findByEmailNormalized(normalized);
        if (existing.isPresent() && !existing.get().canResend(now)) {
            throw new AuthException(
                    "잠시 후에 다시 요청해 주세요.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        Optional<User> user = userRepository.findByEmail(normalized);
        if (user.isEmpty()) {
            // 가입되지 않은 주소다. 응답은 성공과 동일하게 두어 계정 존재 여부를 감춘다.
            return;
        }

        String code = generateCode();
        PasswordResetChallenge challenge = existing
                .map(found -> {
                    found.reissue(hash(code), now, properties);
                    return found;
                })
                .orElseGet(() -> PasswordResetChallenge.issue(normalized, hash(code), now, properties));
        challengeRepository.save(challenge);

        try {
            emailSender.getObject().sendResetCode(normalized, code, properties.getCodeTtl());
        } catch (RuntimeException exception) {
            // 발송 실패를 알리면 어떤 주소가 존재하는지 유추할 수 있다. 기록만 남긴다.
            log.warn("Password reset delivery failed", exception);
        }
    }

    /**
     * 코드를 확인하고 비밀번호를 바꾼다.
     *
     * 성공하면 그 계정의 모든 세션을 끊는다. 재설정을 요청하는 흔한 이유가
     * 계정을 도난당했기 때문인데, 세션을 남겨 두면 침입자가 그대로 남는다.
     */
    @Transactional
    public void confirmReset(String email, String code, String newPassword) {
        if (!isEnabled()) {
            throw new AuthException("비밀번호 재설정을 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new AuthException(
                    "비밀번호는 " + MINIMUM_PASSWORD_LENGTH + "자 이상이어야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }

        String normalized = normalize(email);
        Instant now = Instant.now();

        PasswordResetChallenge challenge = challengeRepository.findByEmailNormalized(normalized)
                .orElseThrow(this::invalidCode);

        if (!challenge.isUsable(now, properties.getMaxAttempts())) {
            throw invalidCode();
        }

        if (!constantTimeEquals(challenge.getCodeHash(), hash(code == null ? "" : code))) {
            challenge.recordFailure();
            challengeRepository.save(challenge);
            throw invalidCode();
        }

        User user = userRepository.findByEmail(normalized).orElseThrow(this::invalidCode);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        challenge.consume(now);
        challengeRepository.save(challenge);

        redisSessionService.removeUserSessions(String.valueOf(user.getId()));
    }

    /**
     * 코드가 틀렸는지, 만료되었는지, 애초에 요청이 없었는지를 구분해 주지 않는다.
     * 구분해 주면 그 자체가 계정 존재 여부를 알려 주는 신호가 된다.
     */
    private AuthException invalidCode() {
        return new AuthException("인증번호가 올바르지 않거나 만료되었습니다.", HttpStatus.BAD_REQUEST);
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new AuthException("이메일을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** 앞자리가 0이어도 여섯 자리를 유지하도록 문자열로 만든다. */
    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required to hash reset codes", exception);
        }
    }

    /** 해시 비교 시간이 코드 내용에 따라 달라지지 않게 한다. */
    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
