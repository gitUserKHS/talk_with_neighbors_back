package com.talkwithneighbors.config;

import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.AuthInterceptor;
import com.talkwithneighbors.security.ChatMediaAuthorizationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods}")
    private String allowedMethods;

    private final AuthInterceptor authInterceptor;
    private final ChatMediaAuthorizationInterceptor chatMediaAuthorizationInterceptor;

    public WebConfig(
            AuthInterceptor authInterceptor,
            MessageRepository messageRepository
    ) {
        this.authInterceptor = authInterceptor;
        this.chatMediaAuthorizationInterceptor =
                new ChatMediaAuthorizationInterceptor(messageRepository);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(split(allowedOrigins))
            .allowedMethods(split(allowedMethods))
            .allowedHeaders("*")
            .exposedHeaders("X-Session-Id") // 응답 헤더에 X-Session-Id를 노출
            .allowCredentials(true)
            .maxAge(3600);
    }

    private String[] split(String values) {
        return values.split("\\s*,\\s*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(chatMediaAuthorizationInterceptor)
                .addPathPatterns("/uploads/chat/**")
                .order(1);
    }
}
