package com.talkwithneighbors.auth.email;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class EmailVerificationCookieFactory {
    private final EmailVerificationProperties properties;

    public EmailVerificationCookieFactory(EmailVerificationProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie proof(String value, Duration ttl) {
        return ResponseCookie.from(properties.getProofCookieName(), value)
                .httpOnly(true)
                .secure(properties.isProofCookieSecure())
                .sameSite("Lax")
                .path("/api/auth/register")
                .maxAge(ttl)
                .build();
    }

    public ResponseCookie expiredProof() {
        return proof("", Duration.ZERO);
    }
}
