package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
public class OAuthIdentityService {
    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthIdentityService(
            UserIdentityRepository identities, UserRepository users, PasswordEncoder passwordEncoder) {
        this.identities = identities;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User resolveOrCreate(OAuthProvider provider, String subject, String rawEmail, boolean emailVerified) {
        if (subject == null || subject.isBlank()) {
            throw new OAuthLoginException(
                    "PROVIDER_SUBJECT_REQUIRED",
                    "OAuth provider did not return a stable subject.", HttpStatus.UNAUTHORIZED);
        }
        Instant now = Instant.now();
        UserIdentity existing = identities.findByProviderAndProviderSubject(provider, subject).orElse(null);
        if (existing != null) {
            existing.recordLogin(now);
            return existing.getUser();
        }
        if (!emailVerified || rawEmail == null || rawEmail.isBlank()) {
            throw new OAuthLoginException(
                    "PROVIDER_EMAIL_REQUIRED",
                    "A verified provider email is required.", HttpStatus.UNAUTHORIZED);
        }

        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        // An existing local email must be linked through a future, authenticated
        // account-link flow. Never silently merge it during OAuth login.
        if (users.existsByEmail(email)) {
            throw new OAuthLoginException(
                    "ACCOUNT_LINK_REQUIRED",
                    "This email already has an account. Sign in and link it explicitly.", HttpStatus.CONFLICT);
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(uniqueUsername(provider, subject));
        byte[] passwordBytes = new byte[32];
        secureRandom.nextBytes(passwordBytes);
        user.setPassword(passwordEncoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes)));
        user.setAccountType(UserAccountType.MEMBER);
        user.setPasswordLoginEnabled(false);
        user.setAge(0);
        user.setGender("");
        user.setLatitude(0.0);
        user.setLongitude(0.0);
        user.setAddress("");
        User saved = users.save(user);
        identities.save(UserIdentity.create(provider, subject, saved, email, now));
        return saved;
    }

    private String uniqueUsername(OAuthProvider provider, String subject) {
        String compact = subject.replaceAll("[^A-Za-z0-9]", "");
        if (compact.isBlank()) compact = Integer.toUnsignedString(subject.hashCode(), 36);
        compact = compact.substring(0, Math.min(12, compact.length())).toLowerCase(Locale.ROOT);
        String base = provider.name().toLowerCase(Locale.ROOT) + "_" + compact;
        String candidate = base;
        int suffix = 1;
        while (users.existsByUsername(candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }
}
