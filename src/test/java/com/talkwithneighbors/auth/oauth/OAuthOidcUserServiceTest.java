package com.talkwithneighbors.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OAuthOidcUserServiceTest {
    @Test
    void kakaoRetrievesUserInfoForItsNonStandardEmailScope() {
        OidcUserService service = new OAuthClientConfig().oidcUserService();
        OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfoService = userInfoService();
        service.setOauth2UserService(userInfoService);
        when(userInfoService.loadUser(any())).thenReturn(new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "sub", "kakao-subject",
                        "email", "dayun@example.com",
                        "email_verified", true),
                "sub"));

        OidcUser user = service.loadUser(request(
                "kakao", Set.of("openid", "account_email", "profile_nickname")));

        verify(userInfoService).loadUser(any(OAuth2UserRequest.class));
        assertEquals("dayun@example.com", user.getAttribute("email"));
        assertEquals(Boolean.TRUE, user.getAttribute("email_verified"));
    }

    @Test
    void standardProviderKeepsTheFrameworkScopeBoundary() {
        OidcUserService service = new OAuthClientConfig().oidcUserService();
        OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfoService = userInfoService();
        service.setOauth2UserService(userInfoService);

        OidcUser user = service.loadUser(request("google", Set.of("openid")));

        verifyNoInteractions(userInfoService);
        assertNull(user.getAttribute("email_verified"));
    }

    @SuppressWarnings("unchecked")
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfoService() {
        return mock(OAuth2UserService.class);
    }

    private OidcUserRequest request(String registrationId, Set<String> scopes) {
        Instant now = Instant.now();
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://talk.example/api/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .jwkSetUri("https://provider.example/jwks")
                .issuerUri("https://provider.example")
                .userInfoUri("https://provider.example/userinfo")
                .userNameAttributeName("sub")
                .clientName(registrationId)
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-token",
                now,
                now.plusSeconds(300),
                scopes);
        OidcIdToken idToken = new OidcIdToken(
                "id-token",
                now,
                now.plusSeconds(300),
                Map.of(
                        "iss", "https://provider.example",
                        "sub", "kakao-subject",
                        "aud", List.of("client-id"),
                        "email", "dayun@example.com"));
        return new OidcUserRequest(registration, accessToken, idToken);
    }
}
