package com.talkwithneighbors.auth.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
    private final OAuthProperties properties;
    private final boolean secureCookie;

    public OAuthLoginFailureHandler(
            OAuthProperties properties,
            @Value("${app.session.cookie-secure:false}") boolean secureCookie) {
        this.properties = properties;
        this.secureCookie = secureCookie;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        Object saved = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(OAuthReturnToCaptureFilter.SESSION_ATTRIBUTE);
        String returnTo = OAuthReturnToCaptureFilter.sanitize(saved == null ? null : saved.toString());
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("SESSIONID", "")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build().toString());
        String error = exception instanceof OAuth2AuthenticationException oauthException
                && "access_denied".equals(oauthException.getError().getErrorCode())
                ? "ACCESS_DENIED" : "OAUTH_FAILED";
        response.sendRedirect(properties.getFrontendBaseUrl()
                + "/auth/callback?status=error&error=" + error + "&returnTo="
                + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
    }
}
