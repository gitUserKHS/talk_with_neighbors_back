package com.talkwithneighbors.auth.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("app.auth.oauth")
public class OAuthProperties {
    private String publicBaseUrl = "http://localhost:8080";
    private String frontendBaseUrl = "http://localhost:3000";
    private Provider google = new Provider();
    private Provider kakao = new Provider();

    public boolean googleEnabled() { return google.configured(); }
    public boolean kakaoEnabled() { return kakao.configured(); }
    public boolean anyEnabled() { return googleEnabled() || kakaoEnabled(); }

    @Getter
    @Setter
    public static class Provider {
        private String clientId = "";
        private String clientSecret = "";

        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
