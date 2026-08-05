package com.talkwithneighbors.auth.password;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 진행 중인 비밀번호 재설정 요청.
 *
 * 코드는 원문이 아니라 해시로 저장한다. 데이터베이스를 읽을 수 있는 사람이
 * 남의 비밀번호를 재설정할 수 있어서는 안 된다.
 * 이메일당 한 건만 두어 요청이 쌓이지 않게 하고, 재요청은 같은 행을 갱신한다.
 */
@Entity
@Table(name = "password_reset_challenges")
@Getter
@NoArgsConstructor
public class PasswordResetChallenge {

    @Id
    @Column(name = "challenge_id", length = 36)
    private String challengeId;

    @Column(name = "email_normalized", nullable = false, unique = true, length = 320)
    private String emailNormalized;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_available_at", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    private long version;

    public static PasswordResetChallenge issue(
            String emailNormalized, String codeHash, Instant now, PasswordResetProperties properties) {
        PasswordResetChallenge challenge = new PasswordResetChallenge();
        challenge.challengeId = UUID.randomUUID().toString();
        challenge.emailNormalized = emailNormalized;
        challenge.reissue(codeHash, now, properties);
        return challenge;
    }

    /** 재요청. 이전 코드는 즉시 무효가 되고 시도 횟수와 소비 표시도 초기화된다. */
    public void reissue(String codeHash, Instant now, PasswordResetProperties properties) {
        this.codeHash = codeHash;
        this.expiresAt = now.plus(properties.getCodeTtl());
        this.resendAvailableAt = now.plus(properties.getResendCooldown());
        this.failedAttempts = 0;
        this.consumedAt = null;
    }

    public boolean canResend(Instant now) {
        return !now.isBefore(resendAvailableAt);
    }

    public Duration retryAfter(Instant now) {
        return canResend(now) ? Duration.ZERO : Duration.between(now, resendAvailableAt);
    }

    public boolean isUsable(Instant now, int maxAttempts) {
        return consumedAt == null && now.isBefore(expiresAt) && failedAttempts < maxAttempts;
    }

    public void recordFailure() {
        failedAttempts += 1;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
