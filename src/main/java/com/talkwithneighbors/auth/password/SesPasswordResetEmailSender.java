package com.talkwithneighbors.auth.password;

import com.talkwithneighbors.auth.email.EmailVerificationProperties;
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

/**
 * 이메일 인증과 같은 SES 설정을 재사용한다. 발신 주소와 리전을 두 벌로 관리할 이유가 없다.
 */
@Component
@ConditionalOnProperty(name = "app.auth.email.sender", havingValue = "ses")
public class SesPasswordResetEmailSender implements PasswordResetEmailSender {

    private final SesV2Client client;
    private final EmailVerificationProperties properties;

    public SesPasswordResetEmailSender(SesV2Client client, EmailVerificationProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.getFrom() != null && !properties.getFrom().isBlank();
    }

    @Override
    public void sendResetCode(String email, String code, Duration validity) {
        String text = "이웃톡 비밀번호 재설정 인증번호는 " + code + " 입니다. "
                + validity.toMinutes() + "분 안에 입력해 주세요. "
                + "본인이 요청하지 않았다면 이 메일을 무시해 주세요.";

        Message message = Message.builder()
                .subject(Content.builder().data("이웃톡 비밀번호 재설정").charset("UTF-8").build())
                .body(Body.builder().text(Content.builder().data(text).charset("UTF-8").build()).build())
                .build();

        client.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(properties.getFrom())
                .destination(Destination.builder().toAddresses(email).build())
                .content(EmailContent.builder().simple(message).build())
                .build());
    }
}
