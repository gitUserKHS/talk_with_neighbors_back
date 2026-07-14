package com.talkwithneighbors.auth.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "email_verification_challenges")
@Getter
@NoArgsConstructor
public class EmailVerificationChallenge {
    @Id
    @Column(name = "challenge_id", length = 36)
    private String challengeId;

    @Column(name = "email_normalized", nullable = false, unique = true, length = 320)
    private String emailNormalized;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "code_expires_at", nullable = false)
    private Instant codeExpiresAt;

    @Column(name = "resend_available_at", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "proof_hash", length = 64)
    private String proofHash;

    @Column(name = "proof_expires_at")
    private Instant proofExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    private long version;

    public static EmailVerificationChallenge issue(
            String email, String codeHash, Instant now, EmailVerificationProperties properties) {
        EmailVerificationChallenge challenge = new EmailVerificationChallenge();
        challenge.challengeId = java.util.UUID.randomUUID().toString();
        challenge.emailNormalized = email;
        challenge.reissue(codeHash, now, properties);
        return challenge;
    }

    public void reissue(String codeHash, Instant now, EmailVerificationProperties properties) {
        this.codeHash = codeHash;
        this.codeExpiresAt = now.plus(properties.getCodeTtl());
        this.resendAvailableAt = now.plus(properties.getResendCooldown());
        this.failedAttempts = 0;
        this.proofHash = null;
        this.proofExpiresAt = null;
        this.confirmedAt = null;
        this.consumedAt = null;
    }

    public boolean canResend(Instant now) {
        return !now.isBefore(resendAvailableAt);
    }

    public boolean codeExpired(Instant now) {
        return !now.isBefore(codeExpiresAt);
    }

    public void recordFailure() {
        failedAttempts++;
    }

    public void confirm(String proofHash, Instant now, EmailVerificationProperties properties) {
        this.proofHash = proofHash;
        this.proofExpiresAt = now.plus(properties.getProofTtl());
        this.confirmedAt = now;
        this.consumedAt = null;
    }

    public boolean proofUsable(Instant now) {
        return confirmedAt != null && consumedAt == null && proofExpiresAt != null && now.isBefore(proofExpiresAt);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
