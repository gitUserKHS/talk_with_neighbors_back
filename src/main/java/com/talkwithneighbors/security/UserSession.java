package com.talkwithneighbors.security;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
public class UserSession implements Serializable {
    private String userId;
    private String username;
    private String email;

    public static UserSession of(String userId, String username, String email) {
        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setUsername(username);
        session.setEmail(email);
        return session;
    }
} 