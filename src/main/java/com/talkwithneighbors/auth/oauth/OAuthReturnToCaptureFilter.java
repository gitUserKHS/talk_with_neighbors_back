package com.talkwithneighbors.auth.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

@Component
public class OAuthReturnToCaptureFilter extends OncePerRequestFilter {
    public static final String SESSION_ATTRIBUTE = "TWN_OAUTH_RETURN_TO";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/oauth2/authorization/")) {
            request.getSession(true).setAttribute(
                    SESSION_ATTRIBUTE, sanitize(request.getParameter("returnTo")));
        }
        filterChain.doFilter(request, response);
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "/";
        if (!value.startsWith("/") || value.startsWith("//")
                || value.contains("\\") || value.contains("\r") || value.contains("\n")) return "/";
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() || uri.getHost() != null ? "/" : value;
        } catch (IllegalArgumentException ignored) {
            return "/";
        }
    }
}
