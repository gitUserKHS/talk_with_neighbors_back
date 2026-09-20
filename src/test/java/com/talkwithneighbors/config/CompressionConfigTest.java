package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CompressionConfigTest {

    private static final String ENABLED = "server.compression.enabled";
    private static final String MIN_RESPONSE_SIZE = "server.compression.min-response-size";
    private static final String MIME_TYPES = "server.compression.mime-types";

    @Test
    void jsonResponsesAreCompressedButMediaBytesAreNot() throws IOException {
        List<PropertySource<?>> base = load("application.yml");

        assertThat(property(base, ENABLED)).isEqualTo(Boolean.TRUE);
        assertThat(property(base, MIN_RESPONSE_SIZE)).isEqualTo("1KB");
        assertThat(String.valueOf(property(base, MIME_TYPES)))
                .contains("application/json")
                .doesNotContain("image/")
                .doesNotContain("video/");
    }

    /**
     * Tomcat의 CompressionConfig.useCompression은 강한 ETag가 붙은 응답을 gzip하지 않으므로,
     * /api/public/* 에 거는 ETag 필터는 반드시 약한 ETag(W/)를 내야 한다.
     */
    @Test
    void publicContentEtagFilterEmitsWeakEtagSoTomcatCanCompress() throws Exception {
        ShallowEtagHeaderFilter filter = new WebConfig(mock(com.talkwithneighbors.repository.MessageRepository.class))
                .publicContentEtagFilter()
                .getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req,
                                 jakarta.servlet.http.HttpServletResponse res) throws IOException {
                res.setContentType("application/json");
                res.getWriter().write("{\"content\":[]}");
            }
        });

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("ETag")).startsWith("W/\"");
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
