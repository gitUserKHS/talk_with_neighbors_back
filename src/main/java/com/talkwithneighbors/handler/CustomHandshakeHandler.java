package com.talkwithneighbors.handler;

import com.sun.security.auth.UserPrincipal;
import com.talkwithneighbors.security.SessionAuthenticationFilter;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.RedisSessionService;
import jakarta.servlet.http.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/** Resolves a STOMP principal exclusively from the HttpOnly session cookie. */
public class CustomHandshakeHandler extends DefaultHandshakeHandler {

    public static final String SESSION_ATTRIBUTE =
            CustomHandshakeHandler.class.getName() + ".authenticatedSessionId";
    private static final Logger log = LoggerFactory.getLogger(CustomHandshakeHandler.class);
    private final RedisSessionService redisSessionService;

    public CustomHandshakeHandler(RedisSessionService redisSessionService) {
        this.redisSessionService = redisSessionService;
    }

    @Override
    protected Principal determineUser(
            @NonNull ServerHttpRequest request,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes
    ) {
        String sessionId = sessionCookie(request);
        if (sessionId != null) {
            try {
                UserSession userSession = redisSessionService.getSession(sessionId);
                if (userSession != null && userSession.getUserIdStr() != null) {
                    // Keep the opaque credential server-side and revalidate it
                    // for every authorized client STOMP frame.
                    attributes.put(SESSION_ATTRIBUTE, sessionId);
                    return new UserPrincipal(userSession.getUserIdStr());
                }
            } catch (RuntimeException exception) {
                log.warn("WebSocket handshake rejected an invalid or expired session.");
            }
        }

        return super.determineUser(request, wsHandler, attributes);
    }

    private String sessionCookie(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        Cookie[] cookies = servletRequest.getServletRequest().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SessionAuthenticationFilter.SESSION_COOKIE.equals(cookie.getName())
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
