package com.talkwithneighbors.auth.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("app.auth.email")
public class EmailVerificationProperties {
    private boolean enabled = false;
    private boolean required = false;
    private String sender = "disabled";
    private String hmacSecret = "";
    private String from = "";
    private String sesRegion = "ap-northeast-2";
    private Duration codeTtl = Duration.ofMinutes(10);
    private Duration proofTtl = Duration.ofMinutes(10);
    private Duration resendCooldown = Duration.ofMinutes(1);
    private int maxAttempts = 5;
    private String proofCookieName = "TWN_EMAIL_PROOF";
    private boolean proofCookieSecure = true;
    private int requestLimitPerWindow = 5;
    private Duration requestWindow = Duration.ofMinutes(10);

    public boolean hasSafeHmacSecret() {
        return hmacSecret != null && hmacSecret.length() >= 32;
    }
}
