package com.talkwithneighbors.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹푸시 설정. 키가 없으면 기능 전체가 꺼진 채로 동작한다.
 */
@Getter
@Setter
@ConfigurationProperties("app.push")
public class WebPushProperties {

    private boolean enabled = false;

    /** base64url로 인코딩된 VAPID 공개 키. 브라우저 구독에 그대로 전달한다. */
    private String publicKey = "";

    /** base64url로 인코딩된 VAPID 개인 키. */
    private String privateKey = "";

    /** VAPID 연락처. mailto: 또는 https: 형식이어야 한다. */
    private String subject = "";

    /**
     * 키가 모두 갖춰졌을 때만 실제로 발송할 수 있다.
     * 설정을 켜 두고 키를 빠뜨린 배포에서 조용히 실패하지 않도록 여기서 한 번에 판단한다.
     */
    public boolean isUsable() {
        return enabled
                && publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank()
                && subject != null && !subject.isBlank();
    }
}
