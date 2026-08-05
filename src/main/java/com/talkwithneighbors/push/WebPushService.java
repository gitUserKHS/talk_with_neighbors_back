package com.talkwithneighbors.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 웹푸시 발송과 구독 관리.
 *
 * 앱은 이미 웹소켓으로 실시간 알림을 보내고, 받지 못한 알림은 알림함에 쌓는다.
 * 푸시는 그 둘 사이의 빈칸, 즉 브라우저를 닫아 둔 시간을 메우는 용도다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(WebPushProperties.class)
public class WebPushService {

    private final WebPushProperties properties;
    private final WebPushSubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    private volatile PushService pushService;

    public boolean isEnabled() {
        return properties.isUsable();
    }

    public String publicKey() {
        return properties.isUsable() ? properties.getPublicKey() : "";
    }

    @Transactional
    public void subscribe(Long userId, String endpoint, String p256dh, String auth) {
        subscriptionRepository.findByEndpoint(endpoint).ifPresentOrElse(
                existing -> {
                    if (!existing.getUserId().equals(userId)) {
                        // 공용 기기에서 계정을 바꿔 로그인한 경우다. 이전 소유자의 구독을 남겨두면
                        // 다른 사람의 알림이 이 브라우저로 간다.
                        subscriptionRepository.delete(existing);
                        subscriptionRepository.save(new WebPushSubscription(userId, endpoint, p256dh, auth));
                        return;
                    }
                    existing.refreshKeys(p256dh, auth);
                    subscriptionRepository.save(existing);
                },
                () -> subscriptionRepository.save(new WebPushSubscription(userId, endpoint, p256dh, auth))
        );
    }

    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        subscriptionRepository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    /**
     * 사용자의 모든 기기로 알림을 보낸다.
     *
     * 알림 저장 흐름을 막지 않도록 비동기로 실행하고 예외를 삼킨다.
     * 푸시 실패가 알림함 저장이나 채팅 전송을 되돌리게 두어서는 안 된다.
     */
    @Async
    public void sendToUser(Long userId, String title, String body, String url) {
        if (!properties.isUsable()) {
            return;
        }

        List<WebPushSubscription> subscriptions = subscriptionRepository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            return;
        }

        String payload = payload(title, body, url);
        for (WebPushSubscription subscription : subscriptions) {
            send(subscription, payload);
        }
    }

    private void send(WebPushSubscription subscription, String payload) {
        try {
            Notification notification = Notification.builder()
                    .endpoint(subscription.getEndpoint())
                    .userPublicKey(subscription.getP256dh())
                    .userAuth(subscription.getAuth())
                    .payload(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build();

            HttpResponse response = pushService().send(notification);
            int status = response.getStatusLine().getStatusCode();

            // 404와 410은 브라우저가 구독을 폐기했다는 뜻이다. 다시 보내도 영원히 실패하므로 지운다.
            if (status == 404 || status == 410) {
                subscriptionRepository.deleteByEndpoint(subscription.getEndpoint());
                log.info("Removed an expired push subscription");
            } else if (status >= 400) {
                log.warn("Push delivery rejected with status {}", status);
            }
        } catch (Exception exception) {
            log.warn("Push delivery failed", exception);
        }
    }

    private String payload(String title, String body, String url) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("body", body);
        if (url != null && !url.isBlank()) {
            data.put("url", url);
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception exception) {
            // 제목만이라도 전달되도록 최소 형태로 물러난다.
            return "{\"title\":\"이웃톡\"}";
        }
    }

    /** PushService는 스레드 안전하고 생성 비용이 있어 한 번만 만든다. */
    private PushService pushService() throws Exception {
        PushService local = pushService;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (pushService == null) {
                pushService = new PushService(
                        properties.getPublicKey(),
                        properties.getPrivateKey(),
                        properties.getSubject());
            }
            return pushService;
        }
    }
}
