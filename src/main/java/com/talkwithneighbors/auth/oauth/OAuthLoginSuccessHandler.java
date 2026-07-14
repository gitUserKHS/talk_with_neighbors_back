package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.auth.session.SessionCookieFactory;
import com.talkwithneighbors.auth.session.SessionIssuer;
import com.talkwithneighbors.entity.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final OAuthIdentityService identities;
    private final SessionIssuer sessionIssuer;
    private final SessionCookieFactory cookies;
    private final OAuthProperties properties;
    private final boolean secureCookie;

    public OAuthLoginSuccessHandler(
            OAuthIdentityService identities,
            SessionIssuer sessionIssuer,
            SessionCookieFactory cookies,
            OAuthProperties properties,
            @Value("${app.session.cookie-secure:false}") boolean secureCookie) {
        this.identities = identities;
        this.sessionIssuer = sessionIssuer;
        this.cookies = cookies;
        this.properties = properties;
        this.secureCookie = secureCookie;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        String returnTo = returnTo(request);
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            OAuthProvider provider = OAuthProvider.valueOf(token.getAuthorizedClientRegistrationId().toUpperCase());
            OAuth2User principal = token.getPrincipal();
            String subject = principal.getAttribute("sub");
            String email = principal.getAttribute("email");
            boolean verified = verifiedEmail(principal);
            User user = identities.resolveOrCreate(provider, subject, email, verified);
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(sessionIssuer.issue(user)).toString());
            clearTemporarySession(request, response);
            response.sendRedirect(callback("success", null, returnTo));
        } catch (OAuthLoginException exception) {
            clearTemporarySession(request, response);
            response.sendRedirect(callback("error", exception.getCode(), returnTo));
        } catch (RuntimeException exception) {
            log.error("OAuth login completion failed for provider {} ({})",
                    authentication instanceof OAuth2AuthenticationToken oauthToken
                            ? oauthToken.getAuthorizedClientRegistrationId() : "unknown",
                    exception.getClass().getSimpleName());
            clearTemporarySession(request, response);
            response.sendRedirect(callback("error", "OAUTH_FAILED", returnTo));
        }
    }

    private boolean verifiedEmail(OAuth2User principal) {
        Object claim = principal.getAttribute("email_verified");
        if (claim instanceof Boolean value) return value;
        return claim != null && Boolean.parseBoolean(claim.toString());
    }

    private String returnTo(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(OAuthReturnToCaptureFilter.SESSION_ATTRIBUTE);
        return OAuthReturnToCaptureFilter.sanitize(value == null ? null : value.toString());
    }

    private String callback(String status, String code, String returnTo) {
        StringBuilder url = new StringBuilder(properties.getFrontendBaseUrl())
                .append("/auth/callback?status=").append(status)
                .append("&returnTo=").append(encode(returnTo));
        if (code != null) url.append("&error=").append(encode(code));
        return url.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void clearTemporarySession(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("SESSIONID", "")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build().toString());
    }
}
