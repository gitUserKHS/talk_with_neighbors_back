package com.talkwithneighbors.auth.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.auth.email.sender", havingValue = "ses")
public class SesVerificationEmailSender implements VerificationEmailSender {
    private final SesV2Client client;
    private final EmailVerificationProperties properties;

    public SesVerificationEmailSender(SesV2Client client, EmailVerificationProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && properties.getFrom() != null && !properties.getFrom().isBlank();
    }

    @Override
    public void sendVerificationCode(String email, String code, Duration validity) {
        if (!isAvailable()) {
            throw new EmailVerificationException(
                    "email_verification_unavailable",
                    "Email verification sender is not fully configured.",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            String text = "이웃톡 이메일 인증번호는 " + code + " 입니다. "
                    + validity.toMinutes() + "분 안에 입력해 주세요.";
            Message message = Message.builder()
                    .subject(Content.builder().data("이웃톡 이메일 인증").charset("UTF-8").build())
                    .body(Body.builder().text(Content.builder().data(text).charset("UTF-8").build()).build())
                    .build();
            client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(properties.getFrom())
                    .destination(Destination.builder().toAddresses(email).build())
                    .content(EmailContent.builder().simple(message).build())
                    .build());
        } catch (RuntimeException exception) {
            throw new EmailVerificationException(
                    "email_delivery_failed",
                    "Verification email could not be sent. Please try again later.",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    null,
                    exception);
        }
    }
}
