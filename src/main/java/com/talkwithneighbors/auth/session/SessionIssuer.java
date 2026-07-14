package com.talkwithneighbors.auth.session;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.RedisSessionService;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SessionIssuer {
    private final RedisSessionService sessions;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionIssuer(RedisSessionService sessions) {
        this.sessions = sessions;
    }

    public String issue(User user) {
        byte[] credentialBytes = new byte[32];
        secureRandom.nextBytes(credentialBytes);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialBytes);
        sessions.saveSession(credential, UserSession.of(
                user.getId(), user.getUsername(), user.getEmail(), user.getUsername()));
        return credential;
    }
}
