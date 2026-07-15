package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "user_identities", uniqueConstraints = {
        @UniqueConstraint(name = "uk_identity_provider_subject", columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uk_identity_user_provider", columnNames = {"user_id", "provider"})
})
@Getter
@NoArgsConstructor
public class UserIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider_email", length = 320)
    private String providerEmail;

    @Column(name = "provider_email_verified", nullable = false)
    private boolean providerEmailVerified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    /**
     * Durable marker for the one-time legacy nickname assessment. Without this
     * marker a user-selected nickname that resembles the old generated format
     * could be incorrectly challenged again on every login or restart.
     */
    @Column(name = "nickname_setup_assessed", nullable = false,
            columnDefinition = "boolean default false")
    private boolean nicknameSetupAssessed;

    public static UserIdentity create(
            OAuthProvider provider, String subject, User user, String email, Instant now) {
        UserIdentity identity = new UserIdentity();
        identity.provider = provider;
        identity.providerSubject = subject;
        identity.user = user;
        identity.providerEmail = email;
        identity.providerEmailVerified = true;
        identity.createdAt = now;
        identity.lastLoginAt = now;
        identity.nicknameSetupAssessed = true;
        return identity;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public void markNicknameSetupAssessed() {
        this.nicknameSetupAssessed = true;
    }
}
