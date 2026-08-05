package com.talkwithneighbors.auth.password;

import java.time.Duration;

/**
 * 재설정 코드를 실제로 보내는 수단.
 *
 * 구현을 갈아끼울 수 있게 분리했다. 현재 SES 구현이 있지만 발송 수단은 배포마다 다를 수 있고,
 * 사용 가능한 구현이 없으면 기능 전체가 꺼진 상태로 동작한다.
 */
public interface PasswordResetEmailSender {

    boolean isAvailable();

    void sendResetCode(String email, String code, Duration validity);
}
