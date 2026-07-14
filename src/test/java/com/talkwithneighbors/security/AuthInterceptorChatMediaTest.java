package com.talkwithneighbors.security;

import com.talkwithneighbors.service.SessionValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorChatMediaTest {

    @Mock
    SessionValidationService sessionValidationService;

    private AuthInterceptor interceptor;
    private ResourceHttpRequestHandler resourceHandler;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(sessionValidationService);
        resourceHandler = new ResourceHttpRequestHandler();
    }

    @Test
    void rejectsAnonymousChatMediaEvenWhenHandledAsAStaticResource() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/uploads/chat/private.webp"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, resourceHandler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("private", "no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        verify(sessionValidationService, never()).validateSession(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void authenticatesChatMediaFromTheSessionHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/uploads/chat/private.webp"
        );
        request.addHeader("X-Session-Id", "valid-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserSession session = UserSession.of(7L, "neighbor", "neighbor@example.test", "Neighbor");
        when(sessionValidationService.validateSession("valid-session")).thenReturn(session);

        boolean allowed = interceptor.preHandle(request, response, resourceHandler);

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute("USER_SESSION")).isSameAs(session);
    }

    @Test
    void stillAllowsAnonymousFeedResources() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/uploads/feed/public.webp"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, resourceHandler);

        assertThat(allowed).isTrue();
        verify(sessionValidationService, never()).validateSession(org.mockito.ArgumentMatchers.anyString());
    }
}
