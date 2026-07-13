package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import static org.assertj.core.api.Assertions.assertThat;

class SessionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SessionConfig.class);

    @Test
    void appliesOperationalCookieOverrides() {
        contextRunner
                .withPropertyValues(
                        "app.session.cookie-secure=true",
                        "app.session.cookie-same-site=Strict",
                        "app.session.cookie-name=OPERATIONS_SESSION"
                )
                .run(context -> {
                    CookieSerializer serializer = context.getBean(CookieSerializer.class);
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    serializer.writeCookieValue(new CookieSerializer.CookieValue(
                            new MockHttpServletRequest(), response, "session-value"));

                    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                            .startsWith("OPERATIONS_SESSION=")
                            .contains("Secure")
                            .contains("HttpOnly")
                            .contains("SameSite=Strict");
                });
    }
}
