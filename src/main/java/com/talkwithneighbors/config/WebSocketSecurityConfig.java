package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import java.util.function.Supplier;

@Configuration
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager() {
        MessageMatcherDelegatingAuthorizationManager.Builder messages = 
            MessageMatcherDelegatingAuthorizationManager.builder();
        messages
            // 1. 구독(SUBSCRIBE) 요청에 대한 세부 규칙 (더 구체적인 것이 먼저 와야 함)
            .simpSubscribeDestMatchers("/user/**").authenticated()  // 예: /user/queue/errors, /user/queue/messages
            .simpSubscribeDestMatchers("/topic/**").authenticated() // 예: /topic/greetings, /topic/chat/room/{id}
            // 2. 메시지 발행(SEND, MESSAGE) 요청에 대한 세부 규칙
            .simpDestMatchers("/app/**").authenticated()     // 클라이언트가 서버의 @MessageMapping 메서드로 보내는 메시지
            // 3. 특정 메시지 타입에 대한 일반 규칙 (위에서 명시적으로 처리되지 않은 경우)
            //    이미 위에서 SUBSCRIBE, MESSAGE(SEND를 통해 @MessageMapping 호출 시)는 대부분 커버됨.
            .simpTypeMatchers(SimpMessageType.MESSAGE, SimpMessageType.SUBSCRIBE).authenticated() 
            // 4. 그 외 모든 메시지(CONNECT, DISCONNECT, HEARTBEAT 등)는 우선 허용.
            //    CONNECT 시에는 HandshakeHandler에서 Principal을 설정하고, 이후 인터셉터에서 이 Principal을 기반으로 인증을 확인.
            .anyMessage().permitAll(); 
        return messages.build();
    }

    // isUserAuthenticated 메서드는 .authenticated() 사용 시 불필요하므로 제거
    // private AuthorizationDecision isUserAuthenticated(Supplier<Authentication> authenticationSupplier, Object object) {
    //     Authentication authentication = authenticationSupplier.get();
    //     return new AuthorizationDecision(authentication != null && authentication.isAuthenticated());
    // }
} 