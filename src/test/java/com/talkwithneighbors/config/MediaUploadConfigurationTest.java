package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaUploadConfigurationTest {
    @Test
    void multipartParserRejectsLargeBodiesBeforeTheyReachMediaProcessing() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertEquals("30MB", properties.getProperty("spring.servlet.multipart.max-file-size"));
        assertEquals("125MB", properties.getProperty("spring.servlet.multipart.max-request-size"));
    }
}
