package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bridges the application's database/Redis-backed session cookie into Spring Security.
 *
 * <p>The authentication credential is intentionally accepted only from the HttpOnly
 * cookie. It is never copied into logs, response headers, or request URLs.</p>
 */
public final class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE = "TWN_SESSION";
    public static final String USER_SESSION_ATTRIBUTE = "USER_SESSION";

    private final SessionValidationService sessionValidationService;

    public SessionAuthenticationFilter(SessionValidationService sessionValidationService) {
        this.sessionValidationService = sessionValidationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String sessionId = sessionCookie(request);
            if (sessionId != null) {
                authenticate(request, sessionId);
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String sessionId) {
        try {
            UserSession userSession = sessionValidationService.validateSession(sessionId);
            if (userSession == null || userSession.getUserId() == null) {
                return;
            }

            request.setAttribute(USER_SESSION_ATTRIBUTE, userSession);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userSession.getUserIdStr(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException ignored) {
            // Missing, expired, or malformed credentials remain anonymous. The
            // authorization rules decide whether the requested endpoint is public.
        }
    }

    private String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
