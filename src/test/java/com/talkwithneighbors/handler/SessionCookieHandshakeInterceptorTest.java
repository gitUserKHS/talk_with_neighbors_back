package com.talkwithneighbors.handler;

import com.talkwithneighbors.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionCookieHandshakeInterceptorTest {
    @Test
    void copiesCookieIntoServerSideAttributesUsedBySockJsFallbackTransports() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/1/xhr_streaming");
        servletRequest.setCookies(new Cookie(
                SessionAuthenticationFilter.SESSION_COOKIE, "cookie-session"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new SessionCookieHandshakeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes)
                .containsEntry(CustomHandshakeHandler.SESSION_ATTRIBUTE, "cookie-session");
    }

    @Test
    void neverAcceptsTheRemovedQueryStringCredential() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString("sessionId=query-session");
        Map<String, Object> attributes = new HashMap<>();

        new SessionCookieHandshakeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes);

        assertThat(attributes).doesNotContainKey(CustomHandshakeHandler.SESSION_ATTRIBUTE);
    }
}
