package com.talkwithneighbors.config;

import com.talkwithneighbors.handler.CustomHandshakeHandler;
import com.talkwithneighbors.interceptor.CustomAuthenticationChannelInterceptor;
import com.talkwithneighbors.service.RedisSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    // WebSocketSecurityConfig에서 생성된 AuthorizationManager<Message<?>> 빈을 주입
    @Autowired
    private AuthorizationManager<Message<?>> messageAuthorizationManager;

    @Autowired
    private RedisSessionService redisSessionService;

    @Autowired
    private CustomAuthenticationChannelInterceptor customAuthenticationChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 메시지 브로커 설정
        config.enableSimpleBroker("/topic", "/queue", "/user")
              .setHeartbeatValue(new long[]{10000, 10000}) // 10초 하트비트
              .setTaskScheduler(taskScheduler()); // 명시적 태스크 스케줄러 설정
        // 클라이언트에서 서버로 메시지를 보낼 때의 prefix
        config.setApplicationDestinationPrefixes("/app");
        // 특정 사용자에게 메시지를 보낼 때의 prefix
        config.setUserDestinationPrefix("/user");
    }

    @Bean
    public org.springframework.scheduling.TaskScheduler taskScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler = 
            new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("websocket-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 엔드포인트 설정
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.split(","))
                .setHandshakeHandler(new CustomHandshakeHandler(redisSessionService))
                .withSockJS(); // SockJS 폴백 옵션 추가
    }
    
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // WebSocket 메시지 크기 제한 설정
        registration.setMessageSizeLimit(64 * 1024) // 64KB
                    .setSendBufferSizeLimit(512 * 1024) // 512KB
                    .setSendTimeLimit(20000); // 20초
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 1. CustomAuthenticationChannelInterceptor를 먼저 등록
        registration.interceptors(customAuthenticationChannelInterceptor);

        // 2. 그 다음에 AuthorizationChannelInterceptor를 등록
        AuthorizationChannelInterceptor authorizationChannelInterceptor = 
            new AuthorizationChannelInterceptor(messageAuthorizationManager);
        registration.interceptors(authorizationChannelInterceptor);
    }
}