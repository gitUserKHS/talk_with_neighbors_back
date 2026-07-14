package com.talkwithneighbors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TalkWithNeighborsApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void livenessProbeIsAvailableWithoutAuthentication() {
        var response = restTemplate.getForEntity("/actuator/health/liveness", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void mediaStorageHealthIsExposedSeparatelyFromReadiness() {
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,redis,diskSpace");
        assertThat(environment.getProperty("management.endpoint.health.group.storage.include"))
                .isEqualTo("mediaStorage");

        var response = restTemplate.getForEntity("/actuator/health/storage", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void protectedApiRejectsAnonymousAndLegacyHeaderOnlyRequests() {
        assertThat(restTemplate.getForEntity("/api/chat/rooms", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", "legacy-header-token");
        var response = restTemplate.exchange(
                "/api/chat/rooms", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutAlwaysExpiresTheBrowserCookieEvenWhenTheSessionAlreadyExpired() {
        var response = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, HttpEntity.EMPTY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("TWN_SESSION=")
                .contains("Max-Age=0");
    }

}
