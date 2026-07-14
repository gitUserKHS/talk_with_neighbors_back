package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager() {
        MessageMatcherDelegatingAuthorizationManager.Builder messages =
            MessageMatcherDelegatingAuthorizationManager.builder();
        messages
            // Clients may subscribe only through Spring's user destination prefix.
            .simpSubscribeDestMatchers("/user/**").authenticated()
            .simpSubscribeDestMatchers("/topic/**").denyAll()
            // Application messages must target @MessageMapping handlers, never broker queues directly.
            .simpDestMatchers("/app/**").authenticated()
            .simpTypeMatchers(SimpMessageType.MESSAGE, SimpMessageType.SUBSCRIBE).denyAll()
            // CONNECT, DISCONNECT and heartbeat frames are authenticated by the channel interceptor.
            .anyMessage().permitAll();
        return messages.build();
    }
}
