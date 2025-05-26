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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.talkwithneighbors.service.impl.UserServiceImpl; // UserServiceImpl import

@Component
public class CustomAuthenticationChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationChannelInterceptor.class);

    private final UserServiceImpl userDetailsService; // 타입을 UserServiceImpl로 변경

    @Autowired
    public CustomAuthenticationChannelInterceptor(UserServiceImpl userDetailsService) { // 생성자 주입 타입 변경
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {
            java.security.Principal principal = accessor.getUser(); // 타입 명시
            Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();

            if (principal != null && principal.getName() != null) {
                String principalName = principal.getName(); // Principal 이름 (사용자 ID 문자열)
                
                try {
                    Long userId = Long.parseLong(principalName);
                    log.debug("Attempting to populate SecurityContext. Principal Name (User ID): {}", userId);

                    // WebSocket 세션 속성에 userId 설정 (매우 중요!)
                    accessor.getSessionAttributes().put("userId", principalName);
                    log.info("Set userId in WebSocket session attributes: {}", principalName);

                    // UserDetails의 username과 Principal의 name (ID)이 다를 수 있으므로, UserDetails 자체로 비교하거나, UserDetails의 username으로 비교합니다.
                    // 여기서는 UserDetails.getUsername()으로 비교합니다.
                    if (currentAuth == null || !(currentAuth.getPrincipal() instanceof UserDetails) || 
                        !((UserDetails)currentAuth.getPrincipal()).getUsername().equals(userDetailsService.loadUserById(userId).getUsername())) {
                        
                        UserDetails userDetails = userDetailsService.loadUserById(userId); // 사용자 ID로 조회

                        if (userDetails != null) {
                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            log.info("SecurityContextHolder populated with Authentication for user ID: {}, username: {}, authorities: {}",
                                     userId, userDetails.getUsername(), userDetails.getAuthorities());
                        } else {
                            log.warn("UserDetails not found for user ID: {}. Cannot set SecurityContext.", userId);
                        }
                    } else {
                        log.trace("SecurityContextHolder already contains valid Authentication for user ID: {}", userId);
                    }
                } catch (NumberFormatException nfe) {
                    log.error("Failed to parse principal name to Long (User ID): {}. Error: {}", principalName, nfe.getMessage());
                } catch (Exception e) {
                    log.error("Error loading UserDetails or setting SecurityContext for principal name (User ID) {}: {}", 
                              principalName, e.getMessage(), e);
                }
            } else if (accessor.getCommand() != org.springframework.messaging.simp.stomp.StompCommand.CONNECT && accessor.getCommand() != org.springframework.messaging.simp.stomp.StompCommand.CONNECTED) {
                // CONNECT, CONNECTED 외의 STOMP 명령어에 대해 Principal이 null이면 경고 로그 (DISCONNECT 등은 Principal이 없을 수 있음)
                 if (accessor.getUser() == null && 
                    (accessor.getCommand() == org.springframework.messaging.simp.stomp.StompCommand.SEND || 
                     accessor.getCommand() == org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE || 
                     accessor.getCommand() == org.springframework.messaging.simp.stomp.StompCommand.UNSUBSCRIBE)) {
                    log.warn("STOMP Principal is null for command: {}. This might indicate an issue if authentication is expected.", accessor.getCommand());
                 }
            }
        }
        return message;
    }
} 