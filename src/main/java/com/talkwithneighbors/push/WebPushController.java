package com.talkwithneighbors.push;

import com.talkwithneighbors.security.UserSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class WebPushController {

    private final WebPushService webPushService;

    public record SubscriptionRequest(
            @NotBlank @Size(max = 512) String endpoint,
            @NotBlank @Size(max = 255) String p256dh,
            @NotBlank @Size(max = 255) String auth
    ) {
    }

    public record UnsubscribeRequest(@NotBlank @Size(max = 512) String endpoint) {
    }

    /**
     * 브라우저가 구독할 때 필요한 VAPID 공개 키.
     * 기능이 꺼져 있으면 빈 키와 enabled=false를 돌려주어 프런트가 구독 UI를 감춘다.
     */
    @GetMapping("/public-key")
    public ResponseEntity<Map<String, Object>> publicKey() {
        return ResponseEntity.ok(Map.of(
                "enabled", webPushService.isEnabled(),
                "publicKey", webPushService.publicKey()
        ));
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody SubscriptionRequest request, UserSession session) {
        webPushService.subscribe(session.getUserId(), request.endpoint(), request.p256dh(), request.auth());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody UnsubscribeRequest request, UserSession session) {
        webPushService.unsubscribe(session.getUserId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
