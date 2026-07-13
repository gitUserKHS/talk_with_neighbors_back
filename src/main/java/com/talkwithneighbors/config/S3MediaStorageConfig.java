package com.talkwithneighbors.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.media.storage-type", havingValue = "s3")
public class S3MediaStorageConfig {

    @Bean
    public S3Client mediaS3Client(
            @Value("${app.media.s3.region}") String region
    ) {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("app.media.s3.region is required when S3 media storage is enabled");
        }
        return S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }
}
