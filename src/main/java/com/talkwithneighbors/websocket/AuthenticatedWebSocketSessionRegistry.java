package com.talkwithneighbors.websocket;

import com.talkwithneighbors.handler.CustomHandshakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks live transports by opaque application session without logging it. */
@Component
public class AuthenticatedWebSocketSessionRegistry {
    private static final Logger log =
            LoggerFactory.getLogger(AuthenticatedWebSocketSessionRegistry.class);

    private final ConcurrentMap<String, Set<WebSocketSession>> sessionsByCredential =
            new ConcurrentHashMap<>();

    public WebSocketHandlerDecoratorFactory decoratorFactory() {
        return delegate -> new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                String credential = credential(session);
                if (credential != null) {
                    sessionsByCredential
                            .computeIfAbsent(credential, ignored -> ConcurrentHashMap.newKeySet())
                            .add(session);
                }
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    unregister(credential, session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    unregister(credential(session), session);
                }
            }
        };
    }

    public void closeSessionsForCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByCredential.remove(credential);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                }
            } catch (Exception exception) {
                log.debug("Could not close a revoked WebSocket transport. transportId={}",
                         session.getId());
            }
        }
    }

    private String credential(WebSocketSession session) {
        Object value = session.getAttributes().get(CustomHandshakeHandler.SESSION_ATTRIBUTE);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private void unregister(String credential, WebSocketSession session) {
        if (credential == null) {
            return;
        }
        sessionsByCredential.computeIfPresent(credential, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }
}
