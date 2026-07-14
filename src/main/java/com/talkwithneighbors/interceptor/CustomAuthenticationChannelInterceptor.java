package com.talkwithneighbors.interceptor;

import com.talkwithneighbors.handler.CustomHandshakeHandler;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.websocket.AuthenticatedWebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Revalidates the server-side handshake credential for every client frame and
 * converts the current session user into the message Authentication. This
 * rejects subsequent client frames after logout or Redis expiry. The transport
 * registry also closes every live socket that belongs to a revoked session.
 */
@Component
@RequiredArgsConstructor
public class CustomAuthenticationChannelInterceptor implements ExecutorChannelInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";
    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationChannelInterceptor.class);
    private static final List<SimpleGrantedAuthority> USER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
    private static final Set<StompCommand> AUTHENTICATED_COMMANDS = Set.of(
            StompCommand.CONNECT,
            StompCommand.SEND,
            StompCommand.SUBSCRIBE,
            StompCommand.UNSUBSCRIBE,
            StompCommand.ACK,
            StompCommand.NACK,
            StompCommand.BEGIN,
            StompCommand.COMMIT,
            StompCommand.ABORT
    );

    private final SessionValidationService sessionValidationService;
    private final AuthenticatedWebSocketSessionRegistry webSocketSessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Inbound channel threads are pooled. Never let context from a previous
        // message influence current authentication or authorization.
        SecurityContextHolder.clearContext();

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Object credential = sessionAttributes != null
                ? sessionAttributes.get(CustomHandshakeHandler.SESSION_ATTRIBUTE)
                : null;
        StompCommand command = accessor.getCommand();
        boolean requiresAuthentication = command != null && AUTHENTICATED_COMMANDS.contains(command);
        if (!(credential instanceof String sessionId) || sessionId.isBlank()) {
            if (!requiresAuthentication) {
                return message;
            }
            throw new AccessDeniedException("WebSocket session is missing or expired.");
        }

        // A null command represents an inbound heartbeat. Revalidate it too so
        // an otherwise passive connection is closed promptly after expiry.
        if (!requiresAuthentication && command != null) {
            return message;
        }

        final UserSession userSession;
        try {
            userSession = sessionValidationService.validateSessionWithoutTouch(sessionId);
        } catch (RuntimeException exception) {
            webSocketSessionRegistry.closeSessionsForCredential(sessionId);
            throw new AccessDeniedException("WebSocket session is missing or expired.", exception);
        }

        if (command == null) {
            return message;
        }

        String principalName = userSession != null ? userSession.getUserIdStr() : null;
        if (!isValidUserId(principalName)) {
            log.warn("Rejected a STOMP message with an invalid validated user identity.");
            throw new AccessDeniedException("WebSocket user identity is invalid.");
        }

        Principal handshakePrincipal = accessor.getUser();
        if (handshakePrincipal != null && !principalName.equals(handshakePrincipal.getName())) {
            log.warn("Rejected a STOMP message whose session user no longer matches the handshake user.");
            throw new AccessDeniedException("WebSocket session user changed.");
        }

        // Existing @MessageMapping handlers read the user id from the
        // server-side simpSessionAttributes map. Always overwrite it with the
        // freshly validated value rather than trusting a client frame.
        sessionAttributes.put(USER_ID_ATTRIBUTE, principalName);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principalName,
                null,
                USER_AUTHORITIES
        );
        StompHeaderAccessor authenticatedAccessor = StompHeaderAccessor.wrap(message);
        authenticatedAccessor.setUser(authentication);
        return MessageBuilder.createMessage(message.getPayload(), authenticatedAccessor.getMessageHeaders());
    }

    @Override
    public void afterSendCompletion(
            Message<?> message,
            MessageChannel channel,
            boolean sent,
            Exception exception
    ) {
        SecurityContextHolder.clearContext();
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        SecurityContextHolder.clearContext();
        return message;
    }

    @Override
    public void afterMessageHandled(
            Message<?> message,
            MessageChannel channel,
            MessageHandler handler,
            Exception exception
    ) {
        SecurityContextHolder.clearContext();
    }

    private boolean isValidUserId(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(principalName) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
