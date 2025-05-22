package com.talkwithneighbors.interceptor;

import com.talkwithneighbors.service.RedisSessionService; // 직접 사용하지 않으므로 제거 가능
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class CustomAuthenticationChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationChannelInterceptor.class);

    // UserDetailsService를 주입받아 UserDetails 로드
    private final UserDetailsService userDetailsService;

    @Autowired
    public CustomAuthenticationChannelInterceptor(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {
            Principal principal = accessor.getUser(); // CustomHandshakeHandler에서 설정한 Principal

            // SecurityContext에 Authentication 객체가 아직 없거나, 현재 Principal과 다른 경우에만 설정
            Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
            if (principal != null && principal.getName() != null && 
                (currentAuth == null || !currentAuth.getName().equals(principal.getName()))) {
                
                log.debug("Attempting to populate SecurityContext for STOMP principal: {}", principal.getName());
                try {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(principal.getName());

                    if (userDetails != null) {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.info("SecurityContextHolder populated with Authentication for user: {}, authorities: {}", 
                                 userDetails.getUsername(), userDetails.getAuthorities());
                    } else {
                        log.warn("UserDetails not found for principal name: {}. Cannot set SecurityContext.", principal.getName());
                    }
                } catch (Exception e) {
                    log.error("Error loading UserDetails or setting SecurityContext for principal {}: {}", principal.getName(), e.getMessage(), e);
                }
            } else if (principal != null && currentAuth != null && currentAuth.getName().equals(principal.getName())) {
                 log.trace("SecurityContextHolder already contains valid Authentication for user: {}", principal.getName());
            } else if (principal == null && accessor.getCommand() != org.springframework.messaging.simp.stomp.StompCommand.CONNECT) {
                // CONNECT가 아닌데 Principal이 없는 경우 (일반적으로 발생하지 않아야 함, HandshakeHandler가 설정하므로)
                log.warn("STOMP Principal is null for command: {}. SecurityContext will not be populated.", accessor.getCommand());
            }
        }
        return message;
    }
} 