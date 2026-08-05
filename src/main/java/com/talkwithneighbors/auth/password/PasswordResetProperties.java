package com.talkwithneighbors.auth.password;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("app.auth.password-reset")
public class PasswordResetProperties {

    /**
     * 기능 자체의 켜짐 여부. 메일 발송 수단이 준비되지 않은 배포에서는 꺼 둔다.
     */
    private boolean enabled = false;

    private Duration codeTtl = Duration.ofMinutes(10);

    private Duration resendCooldown = Duration.ofMinutes(1);

    private int maxAttempts = 5;
}
