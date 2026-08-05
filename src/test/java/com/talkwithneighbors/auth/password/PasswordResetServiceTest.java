package com.talkwithneighbors.auth.password;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.RedisSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    @Mock PasswordResetChallengeRepository challengeRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisSessionService redisSessionService;

    PasswordResetProperties properties;
    PasswordResetEmailSender sender;
    PasswordResetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new PasswordResetProperties();
        properties.setEnabled(true);

        sender = mock(PasswordResetEmailSender.class);
        when(sender.isAvailable()).thenReturn(true);

        ObjectProvider<PasswordResetEmailSender> provider = mock(ObjectProvider.class, RETURNS_DEEP_STUBS);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(provider.getObject()).thenReturn(sender);

        service = new PasswordResetService(
                properties, challengeRepository, userRepository, passwordEncoder, redisSessionService, provider);
    }

    private User member(String email) {
        return User.builder().id(5L).email(email).username("member").password("old-hash")
                .latitude(0.0).longitude(0.0).address("a").build();
    }

    @Test
    void featureIsOffWhenNoSenderIsConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PasswordResetEmailSender> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        PasswordResetService offline = new PasswordResetService(
                properties, challengeRepository, userRepository, passwordEncoder, redisSessionService, empty);

        assertThat(offline.isEnabled()).isFalse();
        assertThatThrownBy(() -> offline.requestReset("someone@example.test"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void unknownAddressLooksExactlyLikeASuccessfulRequest() {
        when(challengeRepository.findByEmailNormalized("ghost@example.test")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost@example.test")).thenReturn(Optional.empty());

        service.requestReset("Ghost@Example.Test");

        // 없는 계정에는 코드를 만들지도, 메일을 보내지도 않는다. 그러나 예외도 던지지 않는다.
        verify(challengeRepository, never()).save(any());
        verify(sender, never()).sendResetCode(anyString(), anyString(), any());
    }

    @Test
    void knownAddressStoresOnlyAHashAndMailsTheCode() {
        when(challengeRepository.findByEmailNormalized("member@example.test")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("member@example.test"))
                .thenReturn(Optional.of(member("member@example.test")));

        service.requestReset("member@example.test");

        ArgumentCaptor<PasswordResetChallenge> saved = ArgumentCaptor.forClass(PasswordResetChallenge.class);
        verify(challengeRepository).save(saved.capture());

        ArgumentCaptor<String> mailedCode = ArgumentCaptor.forClass(String.class);
        verify(sender).sendResetCode(any(), mailedCode.capture(), any(Duration.class));

        assertThat(mailedCode.getValue()).hasSize(6).containsOnlyDigits();
        assertThat(saved.getValue().getCodeHash())
                .hasSize(64)
                .isNotEqualTo(mailedCode.getValue());
    }

    @Test
    void deliveryFailureIsSwallowedSoItCannotRevealWhoIsRegistered() {
        when(challengeRepository.findByEmailNormalized(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(member("member@example.test")));
        doThrow(new RuntimeException("SES rejected")).when(sender)
                .sendResetCode(anyString(), anyString(), any());

        service.requestReset("member@example.test");
    }

    @Test
    void resendIsRefusedDuringTheCooldown() {
        PasswordResetChallenge fresh = PasswordResetChallenge.issue(
                "member@example.test", "hash", java.time.Instant.now(), properties);
        when(challengeRepository.findByEmailNormalized("member@example.test")).thenReturn(Optional.of(fresh));

        assertThatThrownBy(() -> service.requestReset("member@example.test"))
                .isInstanceOf(AuthException.class)
                .satisfies(thrown -> assertThat(((AuthException) thrown).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void shortPasswordIsRejectedBeforeAnyCodeIsChecked() {
        assertThatThrownBy(() -> service.confirmReset("member@example.test", "123456", "short"))
                .isInstanceOf(AuthException.class);

        verify(challengeRepository, never()).findByEmailNormalized(anyString());
    }

    @Test
    void wrongCodeCountsAsAFailedAttemptAndKeepsTheReasonVague() {
        PasswordResetChallenge challenge = PasswordResetChallenge.issue(
                "member@example.test", "a".repeat(64), java.time.Instant.now(), properties);
        when(challengeRepository.findByEmailNormalized("member@example.test"))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.confirmReset("member@example.test", "000000", "long-enough-password"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("인증번호가 올바르지 않거나 만료되었습니다");

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        verify(userRepository, never()).save(any());
    }

    @Test
    void missingChallengeFailsTheSameWayAsAWrongCode() {
        when(challengeRepository.findByEmailNormalized("ghost@example.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmReset("ghost@example.test", "000000", "long-enough-password"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("인증번호가 올바르지 않거나 만료되었습니다");
    }

    @Test
    void challengeStopsWorkingOnceAttemptsRunOut() {
        PasswordResetChallenge challenge = PasswordResetChallenge.issue(
                "member@example.test", "hash", java.time.Instant.now(), properties);
        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt += 1) {
            challenge.recordFailure();
        }

        assertThat(challenge.isUsable(java.time.Instant.now(), properties.getMaxAttempts())).isFalse();
    }

    @Test
    void consumedChallengeCannotBeReused() {
        PasswordResetChallenge challenge = PasswordResetChallenge.issue(
                "member@example.test", "hash", java.time.Instant.now(), properties);
        challenge.consume(java.time.Instant.now());

        assertThat(challenge.isUsable(java.time.Instant.now(), properties.getMaxAttempts())).isFalse();
    }
}
