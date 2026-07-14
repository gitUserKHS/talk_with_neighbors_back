package com.talkwithneighbors.config;

import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.ChatMediaAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ChatMediaAuthorizationInterceptor chatMediaAuthorizationInterceptor;

    public WebConfig(MessageRepository messageRepository) {
        this.chatMediaAuthorizationInterceptor =
                new ChatMediaAuthorizationInterceptor(messageRepository);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chatMediaAuthorizationInterceptor)
                .addPathPatterns("/uploads/chat/**")
                .order(0);
    }
}
