package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionLoggingConfigTest {

    private static final String STOMP_LOGGER = "logging.level.org.springframework.messaging.simp.stomp";
    private static final String SOCKJS_LOGGER = "logging.level.org.springframework.web.socket.sockjs";

    @Test
    void verboseMessagingLoggersAreAbsentFromBaseAndPinnedToWarnInProduction() throws IOException {
        List<PropertySource<?>> base = load("application.yml");
        List<PropertySource<?>> production = load("application-production.yml");

        assertThat(property(base, STOMP_LOGGER)).isNull();
        assertThat(property(base, SOCKJS_LOGGER)).isNull();
        assertThat(property(production, STOMP_LOGGER)).isEqualTo("WARN");
        assertThat(property(production, SOCKJS_LOGGER)).isEqualTo("WARN");
    }

    private List<PropertySource<?>> load(String resource) throws IOException {
        return new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
    }

    private Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
