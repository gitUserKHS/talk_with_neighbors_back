package com.talkwithneighbors.auth.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class EmailVerificationConfig {
    @Bean
    EmailVerificationRateLimitFilter emailVerificationRateLimitFilter(
            EmailVerificationProperties properties) {
        return new EmailVerificationRateLimitFilter(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.auth.email.sender", havingValue = "ses")
    SesV2Client sesV2Client(EmailVerificationProperties properties) {
        return SesV2Client.builder()
                .region(Region.of(properties.getSesRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(VerificationEmailSender.class)
    VerificationEmailSender disabledVerificationEmailSender() {
        return new VerificationEmailSender() {
            @Override public boolean isAvailable() { return false; }
            @Override public void sendVerificationCode(String email, String code, java.time.Duration validity) {
                throw new EmailVerificationException(
                        "email_verification_unavailable",
                        "Email verification sender is not configured.",
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            }
        };
    }
}
