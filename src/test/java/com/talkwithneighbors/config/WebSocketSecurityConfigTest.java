package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketSecurityConfigTest {

    private final AuthorizationManager<Message<?>> manager =
        new WebSocketSecurityConfig().messageAuthorizationManager();
    private final Authentication authenticatedUser =
        UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of());

    @Test
    void authenticatedUserCanSubscribeOnlyThroughUserDestination() {
        assertGranted(SimpMessageType.SUBSCRIBE, "/user/queue/chat/read-status");
        assertDenied(SimpMessageType.SUBSCRIBE, "/queue/chat/read-status-userother");
        assertDenied(SimpMessageType.SUBSCRIBE, "/topic/chat/room/other/read-status");
    }

    @Test
    void authenticatedUserCanSendOnlyToApplicationDestination() {
        assertGranted(SimpMessageType.MESSAGE, "/app/chat/read");
        assertDenied(SimpMessageType.MESSAGE, "/queue/chat/read-status-userother");
        assertDenied(SimpMessageType.MESSAGE, "/topic/chat/room/other/read-status");
    }

    private void assertGranted(SimpMessageType type, String destination) {
        assertThat(authorize(type, destination).isGranted()).isTrue();
    }

    private void assertDenied(SimpMessageType type, String destination) {
        assertThat(authorize(type, destination).isGranted()).isFalse();
    }

    private AuthorizationResult authorize(SimpMessageType type, String destination) {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
            .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, type)
            .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination)
            .build();
        AuthorizationResult result = manager.authorize(() -> authenticatedUser, message);
        assertThat(result).isNotNull();
        return result;
    }
}
