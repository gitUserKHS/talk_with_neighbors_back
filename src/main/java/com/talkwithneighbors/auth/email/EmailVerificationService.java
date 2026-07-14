package com.talkwithneighbors.auth.email;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class EmailVerificationService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final EmailVerificationChallengeRepository repository;
    private final VerificationEmailSender sender;
    private final EmailVerificationProperties properties;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public EmailVerificationService(
            EmailVerificationChallengeRepository repository,
            VerificationEmailSender sender,
            EmailVerificationProperties properties) {
        this(repository, sender, properties, new SecureRandom(), Clock.systemUTC());
    }

    EmailVerificationService(
            EmailVerificationChallengeRepository repository,
            VerificationEmailSender sender,
            EmailVerificationProperties properties,
            SecureRandom secureRandom,
            Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public Availability availability() {
        boolean available = properties.isEnabled() && properties.hasSafeHmacSecret() && sender.isAvailable();
        return new Availability(available, available ? null : "email_verification_unavailable");
    }

    public boolean registrationRequired() {
        return properties.isRequired();
    }

    @Transactional
    public IssuedChallenge requestCode(String rawEmail) {
        ensureAvailable();
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        EmailVerificationChallenge challenge = repository.findLockedByEmail(email).orElse(null);
        if (challenge != null && !challenge.canResend(now)) {
            long retryAfter = Math.max(1, Duration.between(now, challenge.getResendAvailableAt()).toSeconds());
            throw new EmailVerificationException(
                    "email_verification_resend_cooldown",
                    "Please wait before requesting another verification code.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    retryAfter);
        }

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        String codeHash = hash("code", email, code);
        if (challenge == null) {
            challenge = EmailVerificationChallenge.issue(email, codeHash, now, properties);
        } else {
            challenge.reissue(codeHash, now, properties);
        }
        repository.saveAndFlush(challenge);
        sender.sendVerificationCode(email, code, properties.getCodeTtl());
        return issued(challenge);
    }

    @Transactional(noRollbackFor = EmailVerificationException.class)
    public ConfirmedProof confirm(String challengeId, String code) {
        ensureAvailable();
        Instant now = clock.instant();
        EmailVerificationChallenge challenge = repository.findLockedByChallengeId(challengeId)
                .orElseThrow(() -> invalidCode(HttpStatus.BAD_REQUEST));
        String email = challenge.getEmailNormalized();
        if (challenge.getConfirmedAt() != null || challenge.codeExpired(now)) {
            throw invalidCode(HttpStatus.BAD_REQUEST);
        }
        if (challenge.getFailedAttempts() >= properties.getMaxAttempts()) {
            throw invalidCode(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!constantTimeEquals(challenge.getCodeHash(), hash("code", email, code))) {
            challenge.recordFailure();
            repository.save(challenge);
            HttpStatus status = challenge.getFailedAttempts() >= properties.getMaxAttempts()
                    ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
            throw invalidCode(status);
        }

        byte[] proofBytes = new byte[32];
        secureRandom.nextBytes(proofBytes);
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(proofBytes);
        challenge.confirm(hash("proof", email, proof), now, properties);
        repository.save(challenge);
        return new ConfirmedProof(proof, properties.getProofTtl());
    }

    @Transactional
    public IssuedChallenge resend(String challengeId) {
        ensureAvailable();
        Instant now = clock.instant();
        EmailVerificationChallenge challenge = repository.findLockedByChallengeId(challengeId)
                .orElseThrow(() -> invalidCode(HttpStatus.BAD_REQUEST));
        if (!challenge.canResend(now)) {
            long retryAfter = Math.max(1, Duration.between(now, challenge.getResendAvailableAt()).toSeconds());
            throw new EmailVerificationException(
                    "email_verification_resend_cooldown",
                    "Please wait before requesting another verification code.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    retryAfter);
        }
        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        challenge.reissue(hash("code", challenge.getEmailNormalized(), code), now, properties);
        repository.saveAndFlush(challenge);
        sender.sendVerificationCode(challenge.getEmailNormalized(), code, properties.getCodeTtl());
        return issued(challenge);
    }

    @Transactional
    public void consumeProof(String rawEmail, String proof) {
        ensureAvailable();
        if (proof == null || proof.isBlank()) {
            throw invalidProof();
        }
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        EmailVerificationChallenge challenge = repository.findLockedByEmail(email)
                .orElseThrow(this::invalidProof);
        if (!challenge.proofUsable(now)
                || !constantTimeEquals(challenge.getProofHash(), hash("proof", email, proof))) {
            throw invalidProof();
        }
        challenge.consume(now);
        repository.save(challenge);
    }

    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void deleteStaleChallenges() {
        repository.deleteStale(clock.instant().minus(Duration.ofDays(1)));
    }

    private void ensureAvailable() {
        if (!availability().enabled()) {
            throw new EmailVerificationException(
                    "email_verification_unavailable",
                    "Email verification is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new EmailVerificationException("A valid email is required.", HttpStatus.BAD_REQUEST);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String purpose, String email, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((purpose + ":" + email + ":" + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Email verification hashing is unavailable.", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private EmailVerificationException invalidCode(HttpStatus status) {
        String code = status == HttpStatus.TOO_MANY_REQUESTS
                ? "email_verification_attempts_exhausted"
                : "email_verification_code_invalid_or_expired";
        return new EmailVerificationException(code, "The verification code is invalid or expired.", status);
    }

    private EmailVerificationException invalidProof() {
        return new EmailVerificationException(
                "email_verification_proof_invalid",
                "Email verification proof is invalid or expired.", HttpStatus.UNAUTHORIZED);
    }

    private IssuedChallenge issued(EmailVerificationChallenge challenge) {
        return new IssuedChallenge(
                challenge.getChallengeId(),
                properties.getCodeTtl().toSeconds(),
                properties.getResendCooldown().toSeconds());
    }

    public record Availability(boolean enabled, String reason) {}
    public record IssuedChallenge(String challengeId, long expiresInSeconds, long resendAfterSeconds) {}
    public record ConfirmedProof(String value, Duration ttl) {}
}
