package com.talkwithneighbors.websocket;

import com.talkwithneighbors.handler.CustomHandshakeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedWebSocketSessionRegistryTest {
    @Test
    void closesEveryLiveTransportWhenTheApplicationSessionIsRevoked() throws Exception {
        AuthenticatedWebSocketSessionRegistry registry =
                new AuthenticatedWebSocketSessionRegistry();
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler decorated = registry.decoratorFactory().decorate(delegate);
        WebSocketSession session = session("transport-1", "cookie-session");

        decorated.afterConnectionEstablished(session);
        registry.closeSessionsForCredential("cookie-session");

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void removesNormallyClosedTransportsFromTheRevocationIndex() throws Exception {
        AuthenticatedWebSocketSessionRegistry registry =
                new AuthenticatedWebSocketSessionRegistry();
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler decorated = registry.decoratorFactory().decorate(delegate);
        WebSocketSession session = session("transport-2", "cookie-session");

        decorated.afterConnectionEstablished(session);
        decorated.afterConnectionClosed(session, CloseStatus.NORMAL);
        registry.closeSessionsForCredential("cookie-session");

        verify(session, never()).close(CloseStatus.POLICY_VIOLATION);
    }

    private WebSocketSession session(String id, String credential) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CustomHandshakeHandler.SESSION_ATTRIBUTE, credential);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
