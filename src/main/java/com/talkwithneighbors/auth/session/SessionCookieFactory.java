package com.talkwithneighbors.auth.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SessionCookieFactory {
    public static final String COOKIE_NAME = "TWN_SESSION";

    private final boolean secure;

    public SessionCookieFactory(@Value("${app.session.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie create(String credential) {
        return ResponseCookie.from(COOKIE_NAME, credential)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/")
                .maxAge(Duration.ofHours(24)).build();
    }

    public ResponseCookie expire() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build();
    }
}
