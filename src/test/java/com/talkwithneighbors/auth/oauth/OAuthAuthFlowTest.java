package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.auth.email.EmailVerificationService;
import com.talkwithneighbors.auth.session.SessionCookieFactory;
import com.talkwithneighbors.auth.session.SessionIssuer;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OAuthAuthFlowTest {
    @Test
    void returnToAllowsOnlyLocalRelativePaths() {
        assertEquals("/meetups/1?tab=chat", OAuthReturnToCaptureFilter.sanitize("/meetups/1?tab=chat"));
        assertEquals("/", OAuthReturnToCaptureFilter.sanitize("https://evil.example"));
        assertEquals("/", OAuthReturnToCaptureFilter.sanitize("//evil.example/path"));
        assertEquals("/", OAuthReturnToCaptureFilter.sanitize("/\\evil"));
        assertEquals("/", OAuthReturnToCaptureFilter.sanitize("/ok\r\nLocation: evil"));
    }

    @Test
    void repeatIdentityLoginReturnsFetchedLocalUserWithoutRelinkingEmail() {
        UserIdentityRepository identities = mock(UserIdentityRepository.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        OAuthIdentityService service = new OAuthIdentityService(identities, users, encoder);
        User user = User.builder().id(7L).email("dayun@example.com").username("dayun").build();
        UserIdentity identity = UserIdentity.create(
                OAuthProvider.GOOGLE, "stable-sub", user, user.getEmail(), Instant.now());
        when(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "stable-sub"))
                .thenReturn(Optional.of(identity));

        assertSame(user, service.resolveOrCreate(
                OAuthProvider.GOOGLE, "stable-sub", "changed@example.com", true));
        verifyNoInteractions(users, encoder);
    }

    @Test
    void accountLinkRequirementRedirectsWithStableErrorAndInvalidatesSession() throws Exception {
        OAuthIdentityService identities = mock(OAuthIdentityService.class);
        SessionIssuer sessions = mock(SessionIssuer.class);
        SessionCookieFactory cookies = mock(SessionCookieFactory.class);
        OAuthProperties properties = new OAuthProperties();
        properties.setFrontendBaseUrl("https://talk.example");
        OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(
                identities, sessions, cookies, properties, true);
        when(identities.resolveOrCreate(eq(OAuthProvider.GOOGLE), anyString(), anyString(), eq(true)))
                .thenThrow(new OAuthLoginException(
                        "ACCOUNT_LINK_REQUIRED", "link required", HttpStatus.CONFLICT));

        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "stable-sub", "email", "dayun@example.com", "email_verified", true),
                "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "google");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        session.setAttribute(OAuthReturnToCaptureFilter.SESSION_ATTRIBUTE, "/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://talk.example/auth/callback?status=error&returnTo=%2Ffeed&error=ACCOUNT_LINK_REQUIRED",
                response.getRedirectedUrl());
        assertTrue(session.isInvalid());
    }

    @Test
    void kakaoUserInfoVerifiedEmailReachesIdentityResolution() throws Exception {
        OAuthIdentityService identities = mock(OAuthIdentityService.class);
        SessionIssuer sessions = mock(SessionIssuer.class);
        SessionCookieFactory cookies = mock(SessionCookieFactory.class);
        OAuthProperties properties = new OAuthProperties();
        properties.setFrontendBaseUrl("https://talk.example");
        OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(
                identities, sessions, cookies, properties, true);
        when(identities.resolveOrCreate(
                OAuthProvider.KAKAO, "kakao-subject", "dayun@example.com", true))
                .thenThrow(new OAuthLoginException(
                        "ACCOUNT_LINK_REQUIRED", "link required", HttpStatus.CONFLICT));

        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "id-token",
                now,
                now.plusSeconds(300),
                Map.of(
                        "iss", "https://kauth.kakao.com",
                        "sub", "kakao-subject",
                        "aud", List.of("client-id"),
                        "email", "dayun@example.com"));
        OidcUserInfo userInfo = new OidcUserInfo(Map.of(
                "sub", "kakao-subject",
                "email", "dayun@example.com",
                "email_verified", true));
        DefaultOidcUser principal = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, userInfo, "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "kakao");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(identities).resolveOrCreate(
                OAuthProvider.KAKAO, "kakao-subject", "dayun@example.com", true);
        assertTrue(session.isInvalid());
        assertEquals(
                "https://talk.example/auth/callback?status=error&returnTo=%2F&error=ACCOUNT_LINK_REQUIRED",
                response.getRedirectedUrl());
    }

    @Test
    void newIdentityStillRejectsEmailWithoutProviderVerification() {
        UserIdentityRepository identities = mock(UserIdentityRepository.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        OAuthIdentityService service = new OAuthIdentityService(identities, users, encoder);
        when(identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, "kakao-subject"))
                .thenReturn(Optional.empty());

        OAuthLoginException exception = assertThrows(OAuthLoginException.class, () ->
                service.resolveOrCreate(
                        OAuthProvider.KAKAO, "kakao-subject", "dayun@example.com", false));

        assertEquals("PROVIDER_EMAIL_REQUIRED", exception.getCode());
        verifyNoInteractions(users, encoder);
    }

    @Test
    void providersReportDisabledProvidersAsBooleans() {
        OAuthProperties properties = new OAuthProperties();
        EmailVerificationService email = mock(EmailVerificationService.class);
        when(email.availability()).thenReturn(
                new EmailVerificationService.Availability(false, "email_verification_unavailable"));
        AuthProviderController.ProviderStatus status =
                new AuthProviderController(properties, email).providers();

        assertEquals(2, status.providers().size());
        assertTrue(status.providers().stream().noneMatch(AuthProviderController.Provider::enabled));
        assertFalse(status.emailVerification().enabled());
    }
}
