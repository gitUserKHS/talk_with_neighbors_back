package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.media.storage.MediaObjectMetadata;
import com.talkwithneighbors.service.media.storage.S3MediaObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3MediaResourceControllerTest {

    @Mock
    S3MediaObjectStorage storage;

    @Mock
    HttpServletRequest request;

    private S3MediaResourceController controller;

    @BeforeEach
    void setUp() {
        controller = new S3MediaResourceController(storage);
        when(storage.metadata("feed/video.mp4")).thenReturn(new MediaObjectMetadata(
                10L, "video/mp4", "etag", Instant.parse("2026-07-14T00:00:00Z")
        ));
    }

    @Test
    void streamsPrivateObjectThroughStableUploadsUrl() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        doAnswer(invocation -> {
            invocation.<java.io.OutputStream>getArgument(2).write(new byte[] {1, 2, 3});
            return null;
        }).when(storage).writeTo(eq("feed/video.mp4"), eq(null), any());

        var response = controller.resource("feed", "video.mp4", null, request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(output.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void translatesBrowserRangeToS3Range() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        var response = controller.resource("feed", "video.mp4", "bytes=2-5", request);
        response.getBody().writeTo(new ByteArrayOutputStream());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        verify(storage).writeTo(eq("feed/video.mp4"), eq("bytes=2-5"), any());
    }

    @Test
    void supportsSuffixRangesUsedByMediaClients() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        var response = controller.resource("feed", "video.mp4", "bytes=-3", request);
        response.getBody().writeTo(new ByteArrayOutputStream());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10");
        verify(storage).writeTo(eq("feed/video.mp4"), eq("bytes=7-9"), any());
    }

    @Test
    void returnsRangeNotSatisfiableWithoutFetchingObject() throws Exception {
        var response = controller.resource("feed", "video.mp4", "bytes=20-30", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        assertThat(response.getBody()).isNull();
        verify(storage, never()).writeTo(any(), any(), any());
    }

    @Test
    void headReturnsMetadataWithoutDownloadingObject() throws Exception {
        when(request.getMethod()).thenReturn("HEAD");

        var response = controller.resource("feed", "video.mp4", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(response.getBody()).isNull();
        verify(storage, never()).writeTo(any(), any(), any());
    }

    @Test
    void keepsFeedMediaPublicButPreventsSharedCachingForChatMedia() {
        when(request.getMethod()).thenReturn("HEAD");
        when(storage.metadata("chat/video.mp4")).thenReturn(new MediaObjectMetadata(
                10L, "video/mp4", "chat-etag", Instant.parse("2026-07-14T00:00:00Z")
        ));

        var feedResponse = controller.resource("feed", "video.mp4", null, request);
        var chatResponse = controller.resource("chat", "video.mp4", null, request);

        assertThat(feedResponse.getHeaders().getCacheControl())
                .contains("public")
                .doesNotContain("no-store");
        assertThat(chatResponse.getHeaders().getCacheControl())
                .contains("private", "no-store")
                .doesNotContain("public");
    }
}
