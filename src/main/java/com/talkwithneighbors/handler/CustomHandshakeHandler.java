package com.talkwithneighbors.handler;

import com.sun.security.auth.UserPrincipal;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.RedisSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class CustomHandshakeHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomHandshakeHandler.class);
    private final RedisSessionService redisSessionService;

    public CustomHandshakeHandler(RedisSessionService redisSessionService) {
        this.redisSessionService = redisSessionService;
    }

    @Override
    protected Principal determineUser(@NonNull ServerHttpRequest request,
                                      @NonNull WebSocketHandler wsHandler,
                                      @NonNull Map<String, Object> attributes) {
        String sessionId = null;
        String clientIp = request.getRemoteAddress() != null ? request.getRemoteAddress().toString() : "Unknown IP";

        if (request instanceof ServletServerHttpRequest) {
            List<String> sessionIdParams = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get("sessionId");
            if (sessionIdParams != null && !sessionIdParams.isEmpty()) {
                sessionId = sessionIdParams.get(0);
            }
        }

        log.info("Attempting to determine user for WebSocket handshake. Session ID from query: {}, IP: {}", sessionId, clientIp);

        if (sessionId != null) {
            try {
                UserSession userSession = redisSessionService.getSession(sessionId);
                if (userSession != null && userSession.getUserIdStr() != null) {
                    String userIdString = userSession.getUserIdStr();
                    log.info("User determined from session ID {}. Using User ID for Principal: '{}'", 
                             sessionId, userIdString);
                    return new UserPrincipal(userIdString);
                } else {
                    log.warn("No valid UserSession found for session ID {} or userId is null.", sessionId);
                }
            } catch (Exception e) {
                log.error("Error determining user from session ID {}: {}", sessionId, e.getMessage(), e);
            }
        }
        log.warn("Could not determine user from query parameter 'sessionId'. WebSocket connection will be anonymous or based on other Principal.");
        return super.determineUser(request, wsHandler, attributes);
    }
} 