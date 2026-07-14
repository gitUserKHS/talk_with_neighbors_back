package com.talkwithneighbors.auth.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    @Mock EmailVerificationChallengeRepository repository;

    private CapturingSender sender;
    private EmailVerificationProperties properties;
    private EmailVerificationService service;
    private EmailVerificationChallenge challenge;

    @BeforeEach
    void setUp() {
        sender = new CapturingSender();
        properties = new EmailVerificationProperties();
        properties.setEnabled(true);
        properties.setHmacSecret("0123456789abcdef0123456789abcdef");
        properties.setMaxAttempts(2);
        properties.setCodeTtl(Duration.ofMinutes(10));
        properties.setProofTtl(Duration.ofMinutes(10));
        properties.setResendCooldown(Duration.ofMinutes(1));
        service = new EmailVerificationService(
                repository,
                sender,
                properties,
                new SecureRandom(),
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            challenge = invocation.getArgument(0);
            return challenge;
        });
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesOnlyHashesAndConsumesProofOnce() {
        when(repository.findLockedByEmail("dayun@example.com"))
                .thenReturn(Optional.empty())
                .thenAnswer(ignored -> Optional.of(challenge));

        EmailVerificationService.IssuedChallenge issued = service.requestCode("Dayun@example.com");
        assertNotNull(issued.challengeId());
        assertNotEquals(sender.code, challenge.getCodeHash());

        when(repository.findLockedByChallengeId(issued.challengeId())).thenReturn(Optional.of(challenge));
        EmailVerificationService.ConfirmedProof proof = service.confirm(issued.challengeId(), sender.code);
        assertNotEquals(proof.value(), challenge.getProofHash());

        service.consumeProof("dayun@example.com", proof.value());
        EmailVerificationException reused = assertThrows(
                EmailVerificationException.class,
                () -> service.consumeProof("dayun@example.com", proof.value()));
        assertEquals("email_verification_proof_invalid", reused.getCode());
    }

    @Test
    void enforcesCooldownAndReturnsRetryAfter() {
        when(repository.findLockedByEmail("dayun@example.com")).thenReturn(Optional.empty());
        EmailVerificationService.IssuedChallenge issued = service.requestCode("dayun@example.com");
        when(repository.findLockedByChallengeId(issued.challengeId())).thenReturn(Optional.of(challenge));

        EmailVerificationException exception = assertThrows(
                EmailVerificationException.class, () -> service.resend(issued.challengeId()));
        assertEquals("email_verification_resend_cooldown", exception.getCode());
        assertEquals(60L, exception.getRetryAfterSeconds());
    }

    @Test
    void locksAfterConfiguredWrongAttempts() {
        when(repository.findLockedByEmail("dayun@example.com")).thenReturn(Optional.empty());
        EmailVerificationService.IssuedChallenge issued = service.requestCode("dayun@example.com");
        when(repository.findLockedByChallengeId(issued.challengeId())).thenReturn(Optional.of(challenge));

        String firstWrong = sender.code.equals("999999") ? "000000" : "999999";
        String secondWrong = sender.code.equals("888888") ? "000001" : "888888";
        EmailVerificationException first = assertThrows(
                EmailVerificationException.class, () -> service.confirm(issued.challengeId(), firstWrong));
        assertEquals("email_verification_code_invalid_or_expired", first.getCode());
        EmailVerificationException second = assertThrows(
                EmailVerificationException.class, () -> service.confirm(issued.challengeId(), secondWrong));
        assertEquals("email_verification_attempts_exhausted", second.getCode());
    }

    @Test
    void disabledSenderIsExplicitlyUnavailable() {
        sender.available = false;
        EmailVerificationException exception = assertThrows(
                EmailVerificationException.class, () -> service.requestCode("dayun@example.com"));
        assertEquals(503, exception.getStatus().value());
        assertEquals("email_verification_unavailable", exception.getCode());
        verifyNoInteractions(repository);
    }

    private static class CapturingSender implements VerificationEmailSender {
        boolean available = true;
        String code;

        @Override public boolean isAvailable() { return available; }
        @Override public void sendVerificationCode(String email, String code, Duration validity) {
            this.code = code;
        }
    }
}
