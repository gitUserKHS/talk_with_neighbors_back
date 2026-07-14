package com.talkwithneighbors.auth.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthClientConfig {
    @Bean
    FilterRegistrationBean<OAuthReturnToCaptureFilter> oauthReturnToContainerRegistration(
            OAuthReturnToCaptureFilter filter) {
        FilterRegistrationBean<OAuthReturnToCaptureFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    ClientRegistrationRepository clientRegistrationRepository(OAuthProperties properties) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (properties.googleEnabled()) registrations.add(google(properties));
        if (properties.kakaoEnabled()) registrations.add(kakao(properties));
        return new InMemoryClientRegistrationRepository(registrations);
    }

    private ClientRegistration google(OAuthProperties properties) {
        OAuthProperties.Provider provider = properties.getGoogle();
        return base("google", provider, properties)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    private ClientRegistration kakao(OAuthProperties properties) {
        OAuthProperties.Provider provider = properties.getKakao();
        return base("kakao", provider, properties)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .scope("openid", "profile_nickname", "profile_image", "account_email")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json")
                .issuerUri("https://kauth.kakao.com")
                .userInfoUri("https://kapi.kakao.com/v1/oidc/userinfo")
                .userNameAttributeName("sub")
                .clientName("Kakao")
                .build();
    }

    private ClientRegistration.Builder base(
            String registrationId, OAuthProperties.Provider provider, OAuthProperties properties) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.getPublicBaseUrl() + "/api/login/oauth2/code/{registrationId}");
    }

    static class AnyProviderConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String googleId = context.getEnvironment().getProperty("app.auth.oauth.google.client-id", "");
            String googleSecret = context.getEnvironment().getProperty("app.auth.oauth.google.client-secret", "");
            String kakaoId = context.getEnvironment().getProperty("app.auth.oauth.kakao.client-id", "");
            String kakaoSecret = context.getEnvironment().getProperty("app.auth.oauth.kakao.client-secret", "");
            return complete(googleId, googleSecret) || complete(kakaoId, kakaoSecret);
        }

        private boolean complete(String id, String secret) {
            return !id.isBlank() && !secret.isBlank();
        }
    }
}
