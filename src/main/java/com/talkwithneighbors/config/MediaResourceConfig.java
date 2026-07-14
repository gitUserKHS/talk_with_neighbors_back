package com.talkwithneighbors.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "app.media.storage-type", havingValue = "local", matchIfMissing = true)
public class MediaResourceConfig implements WebMvcConfigurer {
    private final Path storageDirectory;

    public MediaResourceConfig(
            @Value("${app.media.storage-directory:./uploads}") String storageDirectory
    ) {
        this.storageDirectory = Paths.get(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String category : List.of("feed", "profile", "chat")) {
            String resourceLocation = storageDirectory.resolve(category).toUri().toString();
            if (!resourceLocation.endsWith("/")) {
                resourceLocation += "/";
            }
            registry.addResourceHandler("/uploads/" + category + "/**")
                    .addResourceLocations(resourceLocation)
                    .setCacheControl(cacheControlFor(category))
                    .resourceChain(false)
                    .addResolver(new PathResourceResolver());
        }
    }

    static CacheControl cacheControlFor(String category) {
        if ("chat".equals(category)) {
            return CacheControl.noStore().cachePrivate();
        }
        return CacheControl.maxAge(Duration.ofDays(30)).cachePublic();
    }
}
