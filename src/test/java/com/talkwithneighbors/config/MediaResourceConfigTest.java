package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaResourceConfigTest {

    @Test
    void chatResourcesArePrivateAndPublicMediaKeepsLongLivedCaching() {
        assertThat(MediaResourceConfig.cacheControlFor("chat").getHeaderValue())
                .contains("private", "no-store")
                .doesNotContain("public");
        assertThat(MediaResourceConfig.cacheControlFor("feed").getHeaderValue())
                .contains("public", "max-age=2592000")
                .doesNotContain("no-store");
        assertThat(MediaResourceConfig.cacheControlFor("profile").getHeaderValue())
                .contains("public", "max-age=2592000")
                .doesNotContain("no-store");
    }
}
