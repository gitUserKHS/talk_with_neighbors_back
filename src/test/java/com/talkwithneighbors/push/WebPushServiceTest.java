package com.talkwithneighbors.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPushServiceTest {

    @Mock WebPushSubscriptionRepository repository;

    WebPushProperties properties;
    WebPushService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new WebPushProperties();
        service = new WebPushService(properties, repository, new ObjectMapper());
    }

    private void configureKeys() {
        properties.setEnabled(true);
        properties.setPublicKey("public-key");
        properties.setPrivateKey("private-key");
        properties.setSubject("mailto:ops@example.test");
    }

    @Test
    void featureStaysOffUntilEveryVapidFieldIsPresent() {
        properties.setEnabled(true);
        assertThat(service.isEnabled()).isFalse();

        properties.setPublicKey("public-key");
        assertThat(service.isEnabled()).isFalse();

        properties.setPrivateKey("private-key");
        assertThat(service.isEnabled()).isFalse();

        properties.setSubject("mailto:ops@example.test");
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void disabledFeatureNeverExposesTheConfiguredKey() {
        properties.setPublicKey("public-key");

        assertThat(service.publicKey()).isEmpty();
    }

    @Test
    void sendingIsSkippedEntirelyWhenTheFeatureIsOff() {
        service.sendToUser(1L, "이웃톡", "새 메시지", "/chat");

        verify(repository, never()).findByUserId(anyLong());
    }

    @Test
    void newEndpointIsStored() {
        when(repository.findByEndpoint("https://push.example.test/a")).thenReturn(Optional.empty());

        service.subscribe(7L, "https://push.example.test/a", "key", "auth");

        ArgumentCaptor<WebPushSubscription> saved = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getEndpoint()).isEqualTo("https://push.example.test/a");
    }

    @Test
    void resubscribingTheSameBrowserRefreshesKeysInsteadOfDuplicating() {
        WebPushSubscription existing = new WebPushSubscription(7L, "https://push.example.test/a", "old", "old-auth");
        when(repository.findByEndpoint("https://push.example.test/a")).thenReturn(Optional.of(existing));

        service.subscribe(7L, "https://push.example.test/a", "fresh", "fresh-auth");

        assertThat(existing.getP256dh()).isEqualTo("fresh");
        assertThat(existing.getAuth()).isEqualTo("fresh-auth");
        verify(repository).save(existing);
    }

    @Test
    void endpointClaimedByAnotherAccountIsReassignedNotShared() {
        WebPushSubscription previousOwner =
                new WebPushSubscription(1L, "https://push.example.test/shared", "k", "a");
        when(repository.findByEndpoint("https://push.example.test/shared"))
                .thenReturn(Optional.of(previousOwner));

        service.subscribe(2L, "https://push.example.test/shared", "k2", "a2");

        // 이전 소유자의 구독이 남아 있으면 공용 기기에서 남의 알림을 받게 된다.
        verify(repository).delete(previousOwner);
        ArgumentCaptor<WebPushSubscription> saved = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(2L);
    }

    @Test
    void sendingStopsQuietlyWhenTheUserHasNoSubscriptions() {
        configureKeys();
        when(repository.findByUserId(9L)).thenReturn(List.of());

        service.sendToUser(9L, "이웃톡", "새 메시지", "/chat");

        verify(repository, never()).deleteByEndpoint(anyString());
    }

    @Test
    void unsubscribeRemovesOnlyTheCallersEndpoint() {
        service.unsubscribe(7L, "https://push.example.test/a");

        verify(repository).deleteByUserIdAndEndpoint(7L, "https://push.example.test/a");
        verify(repository, never()).deleteByEndpoint(any());
    }
}
