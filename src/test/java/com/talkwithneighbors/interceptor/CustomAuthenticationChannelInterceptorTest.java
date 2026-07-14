package com.talkwithneighbors.interceptor;

import com.sun.security.auth.UserPrincipal;
import com.talkwithneighbors.handler.CustomHandshakeHandler;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.websocket.AuthenticatedWebSocketSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomAuthenticationChannelInterceptorTest {

    private final SessionValidationService sessions = mock(SessionValidationService.class);
    private final AuthenticatedWebSocketSessionRegistry webSocketSessions =
            mock(AuthenticatedWebSocketSessionRegistry.class);
    private final CustomAuthenticationChannelInterceptor interceptor =
            new CustomAuthenticationChannelInterceptor(sessions, webSocketSessions);
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void validSession() {
        when(sessions.validateSessionWithoutTouch("cookie-session"))
                .thenReturn(UserSession.of(42L, "neighbor", "hidden@example.test", "Neighbor"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revalidatesServerSideSessionAndBuildsMessageAuthentication() {
        Message<?> converted = interceptor.preSend(
                message(new UserPrincipal("42"), "cookie-session"), channel);

        Authentication authentication = (Authentication) StompHeaderAccessor.wrap(converted).getUser();
        assertThat(authentication.getName()).isEqualTo("42");
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(StompHeaderAccessor.wrap(converted).getSessionAttributes())
                .containsEntry(CustomAuthenticationChannelInterceptor.USER_ID_ATTRIBUTE, "42");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(sessions).validateSessionWithoutTouch("cookie-session");
    }

    @Test
    void rejectsFramesAfterLogoutOrRedisExpiry() {
        when(sessions.validateSessionWithoutTouch("expired-session"))
                .thenThrow(new RuntimeException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(
                message(new UserPrincipal("42"), "expired-session"), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("expired");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(webSocketSessions).closeSessionsForCredential("expired-session");
    }

    @Test
    void rejectsAFrameWhenTheValidatedUserNoLongerMatchesTheHandshakeUser() {
        assertThatThrownBy(() -> interceptor.preSend(
                message(new UserPrincipal("99"), "cookie-session"), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void anonymousFrameCannotReuseAuthenticationLeftOnPooledThread() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "99", null, java.util.List.of()));

        assertThatThrownBy(() -> interceptor.preSend(message(null, null), channel))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void executorThreadContextIsClearedAfterMessageHandling() {
        Message<?> converted = interceptor.preSend(
                message(new UserPrincipal("42"), "cookie-session"), channel);
        SecurityContextChannelInterceptor contextInterceptor = new SecurityContextChannelInterceptor();
        MessageHandler handler = mock(MessageHandler.class);

        Message<?> beforeHandle = interceptor.beforeHandle(converted, channel, handler);
        Message<?> secured = contextInterceptor.beforeHandle(beforeHandle, channel, handler);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("42");

        contextInterceptor.afterMessageHandled(secured, channel, handler, null);
        interceptor.afterMessageHandled(secured, channel, handler, null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void heartbeatWithAHandshakeCredentialIsRevalidatedWithoutNullCommandFailure() {
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setSessionAttributes(new HashMap<>(Map.of(
                CustomHandshakeHandler.SESSION_ATTRIBUTE, "cookie-session")));
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> converted = interceptor.preSend(heartbeat, channel);

        assertThat(converted).isSameAs(heartbeat);
        verify(sessions).validateSessionWithoutTouch("cookie-session");
    }

    private Message<byte[]> message(UserPrincipal principal, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat/messages");
        accessor.setUser(principal);
        Map<String, Object> attributes = new HashMap<>();
        if (sessionId != null) {
            attributes.put(CustomHandshakeHandler.SESSION_ATTRIBUTE, sessionId);
        }
        accessor.setSessionAttributes(attributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
