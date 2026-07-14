package com.talkwithneighbors.handler;

import com.talkwithneighbors.security.SessionAuthenticationFilter;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.RedisSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomHandshakeHandlerTest {

    @Test
    void authenticatesFromSessionCookie() {
        RedisSessionService sessions = mock(RedisSessionService.class);
        when(sessions.getSession("cookie-session"))
                .thenReturn(UserSession.of(7L, "neighbor", "hidden@example.test", "Neighbor"));
        TestableHandshakeHandler handler = new TestableHandshakeHandler(sessions);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setCookies(new Cookie(
                SessionAuthenticationFilter.SESSION_COOKIE, "cookie-session"));

        Map<String, Object> attributes = new HashMap<>();
        Principal principal = handler.determine(
                new ServletServerHttpRequest(servletRequest), mock(WebSocketHandler.class), attributes);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo("7");
        assertThat(attributes)
                .containsEntry(CustomHandshakeHandler.SESSION_ATTRIBUTE, "cookie-session");
        verify(sessions).getSession("cookie-session");
    }

    @Test
    void ignoresLegacyQueryToken() {
        RedisSessionService sessions = mock(RedisSessionService.class);
        TestableHandshakeHandler handler = new TestableHandshakeHandler(sessions);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString("sessionId=query-session");

        Principal principal = handler.determine(
                new ServletServerHttpRequest(servletRequest), mock(WebSocketHandler.class));

        assertThat(principal).isNull();
        verifyNoInteractions(sessions);
    }

    private static final class TestableHandshakeHandler extends CustomHandshakeHandler {
        private TestableHandshakeHandler(RedisSessionService redisSessionService) {
            super(redisSessionService);
        }

        private Principal determine(ServerHttpRequest request, WebSocketHandler handler) {
            return determine(request, handler, new HashMap<>());
        }

        private Principal determine(
                ServerHttpRequest request,
                WebSocketHandler handler,
                Map<String, Object> attributes
        ) {
            return super.determineUser(request, handler, attributes);
        }
    }
}
