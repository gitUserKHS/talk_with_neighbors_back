package com.talkwithneighbors.config;

import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.ChatMediaAuthorizationInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
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

    /**
     * 게스트 공개 피드는 60초 public 캐시와 함께 ETag를 붙여, 같은 내용을 다시 묻는 클라이언트에는
     * 본문 없이 304만 돌려준다. 응답을 버퍼링하므로 작은 JSON을 내는 /api/public에만 건다.
     * Tomcat은 강한 ETag가 붙은 응답을 gzip하지 않으므로(CompressionConfig.useCompression)
     * 약한 ETag(W/)를 써서 server.compression이 이 응답에도 적용되게 한다.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> publicContentEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/public/*");
        registration.setName("publicContentEtagFilter");
        return registration;
    }
}
