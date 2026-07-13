package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionConfig {
    @Bean
    public CookieSerializer cookieSerializer(
            @Value("${app.session.cookie-secure:false}") boolean secure,
            @Value("${app.session.cookie-same-site:Lax}") String sameSite,
            @Value("${app.session.cookie-name:JSESSIONID}") String cookieName
    ) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setSameSite(sameSite);
        serializer.setUseSecureCookie(secure);
        serializer.setCookieName(cookieName);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        return serializer;
    }
}
