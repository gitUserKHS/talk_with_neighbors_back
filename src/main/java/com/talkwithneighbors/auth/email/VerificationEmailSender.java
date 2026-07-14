package com.talkwithneighbors.auth.email;

import java.time.Duration;

public interface VerificationEmailSender {
    boolean isAvailable();
    void sendVerificationCode(String email, String code, Duration validity);
}
