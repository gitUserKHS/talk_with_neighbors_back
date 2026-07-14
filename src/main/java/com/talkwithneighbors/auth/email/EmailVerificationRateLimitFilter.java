package com.talkwithneighbors.auth.email;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class EmailVerificationRateLimitFilter extends OncePerRequestFilter {
    private final EmailVerificationProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public EmailVerificationRateLimitFilter(EmailVerificationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && "/api/auth/email-verifications".equals(request.getRequestURI())) {
            Instant now = clock.instant();
            Decision decision = decide(request.getRemoteAddr(), now);
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"email_verification_rate_limited\","
                        + "\"message\":\"Too many verification requests.\","
                        + "\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Decision decide(String source, Instant now) {
        long windowSeconds = properties.getRequestWindow().toSeconds();
        Window window = windows.compute(source, (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt.plusSeconds(windowSeconds))) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        boolean allowed = window.count <= properties.getRequestLimitPerWindow();
        long retry = Math.max(1, window.startedAt.plusSeconds(windowSeconds).getEpochSecond() - now.getEpochSecond());
        return new Decision(allowed, retry);
    }

    @Scheduled(fixedDelay = 600_000)
    void removeExpiredWindows() {
        Instant cutoff = clock.instant().minus(properties.getRequestWindow());
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt.isBefore(cutoff));
    }

    private record Window(Instant startedAt, int count) {}
    private record Decision(boolean allowed, long retryAfterSeconds) {}
}
